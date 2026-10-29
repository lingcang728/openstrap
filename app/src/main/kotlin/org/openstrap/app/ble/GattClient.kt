package org.openstrap.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

class GattException(message: String) : Exception(message)

/** A characteristic notification. */
class Notification(val characteristic: UUID, val value: ByteArray)

/**
 * Minimal suspend-friendly wrapper around [BluetoothGatt]. Android only allows one outstanding GATT
 * operation at a time, so every operation is serialized through [opLock] and completes via the callback.
 */
@SuppressLint("MissingPermission")
class GattClient(private val context: Context, private val address: String) {

    private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()
    private var pending: CompletableDeferred<Any?>? = null
    /** Set while our own MTU request is the pending op; the stack or the strap can also start an exchange. */
    @Volatile private var awaitingMtu = false

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _notifications = MutableSharedFlow<Notification>(
        extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val notifications: SharedFlow<Notification> = _notifications

    var mtu: Int = 23
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connected.value = true
                complete(Unit)
            } else {
                _connected.value = false
                fail("Disconnected (status $status)")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) =
            if (status == BluetoothGatt.GATT_SUCCESS) complete(Unit) else fail("Service discovery failed ($status)")

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            // A refused MTU request isn't fatal; we keep the default. An exchange we didn't ask for must not
            // complete whatever else is pending.
            if (awaitingMtu) complete(Unit)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            if (status == BluetoothGatt.GATT_SUCCESS) complete(Unit) else fail("Write to ${c.uuid} failed ($status)")

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            if (status == BluetoothGatt.GATT_SUCCESS) complete(value) else fail("Read of ${c.uuid} failed ($status)")

        @Deprecated("Pre-Android 13 callback")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            onCharacteristicRead(g, c, c.value ?: ByteArray(0), status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            if (status == BluetoothGatt.GATT_SUCCESS) complete(Unit) else fail("Descriptor write failed ($status)")

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            _notifications.tryEmit(Notification(c.uuid, value))
        }

        @Deprecated("Pre-Android 13 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            _notifications.tryEmit(Notification(c.uuid, c.value?.copyOf() ?: ByteArray(0)))
        }
    }

    private fun Throwable.isDeadBinder(): Boolean =
        generateSequence(this) { it.cause }.any { it is android.os.DeadObjectException }

    /** Called when Bluetooth turns off: no callback reports the link's end then either. */
    fun lost() {
        _connected.value = false
        fail("Bluetooth turned off")
    }

    private fun complete(value: Any?) { pending?.complete(value) }
    private fun fail(message: String) { pending?.completeExceptionally(GattException(message)) }

    private suspend fun <T> op(timeoutMs: Long = 10_000, start: () -> Boolean): T = opLock.withLock {
        val d = CompletableDeferred<Any?>()
        pending = d
        try {
            val started = try {
                start()
            } catch (e: RuntimeException) {
                // The phone's Bluetooth service died (a stack restart): this handle is dead and no disconnect callback
                // will ever come, so report the link lost here and let the owner reconnect with a fresh one.
                if (!e.isDeadBinder()) throw e
                _connected.value = false
                throw GattException("Bluetooth restarted on the phone")
            }
            if (!started) throw GattException("GATT operation could not be started")
            @Suppress("UNCHECKED_CAST")
            withTimeout(timeoutMs) { d.await() } as T
        } finally {
            pending = null
        }
    }

    /** [refreshCache] drops Android's cached service table first; needed after a firmware update. */
    suspend fun connect(refreshCache: Boolean = false) {
        val device: BluetoothDevice = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        op<Unit>(timeoutMs = 30_000) {
            gatt = if (Build.VERSION.SDK_INT >= 37) {
                // Android 17's settings form. Automatic MTU stays off, as the older call leaves it, and callbacks run
                // on the binder thread like before.
                val settings = android.bluetooth.BluetoothGattConnectionSettings.Builder()
                    .setTransport(BluetoothDevice.TRANSPORT_LE)
                    .setAutoConnectEnabled(false)
                    .setAutomaticMtuEnabled(false)
                    .build()
                device.connectGatt(settings, { it.run() }, callback)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            }
            gatt != null
        }
        gatt!!.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        if (refreshCache) {
            // Hidden API, but the only way to force re-discovery; harmless if it fails.
            runCatching { BluetoothGatt::class.java.getMethod("refresh").invoke(gatt) }
            kotlinx.coroutines.delay(500)
        }
        op<Unit> { gatt!!.discoverServices() }
    }

    suspend fun requestMtu(size: Int): Int {
        try {
            op<Unit> {
                awaitingMtu = true
                gatt!!.requestMtu(size)
            }
        } finally {
            awaitingMtu = false
        }
        return mtu
    }

    fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean =
        gatt?.getService(service)?.getCharacteristic(characteristic) != null

    private fun characteristic(service: UUID, uuid: UUID): BluetoothGattCharacteristic =
        gatt?.getService(service)?.getCharacteristic(uuid) ?: throw GattException("Characteristic $uuid not found")

    suspend fun enableNotifications(service: UUID, uuid: UUID) {
        val c = characteristic(service, uuid)
        gatt!!.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD) ?: throw GattException("No CCCD on $uuid")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op<Unit> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt!!.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                gatt!!.writeDescriptor(cccd)
            }
        }
    }

    suspend fun write(service: UUID, uuid: UUID, value: ByteArray, withResponse: Boolean = false) {
        val c = characteristic(service, uuid)
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        op<Unit> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt!!.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = value
                @Suppress("DEPRECATION")
                gatt!!.writeCharacteristic(c)
            }
        }
    }

    suspend fun read(service: UUID, uuid: UUID): ByteArray {
        val c = characteristic(service, uuid)
        return op { gatt!!.readCharacteristic(c) }
    }

    fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        _connected.value = false
        fail("Closed")
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
