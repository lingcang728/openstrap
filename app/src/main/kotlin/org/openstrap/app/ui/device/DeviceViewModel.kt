package org.openstrap.app.ui.device

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openstrap.app.AppContainer
import org.openstrap.app.ble.FoundDevice
import org.openstrap.app.data.settings.PairedDevice
import org.openstrap.app.device.StrapController
import org.openstrap.app.zepp.BoundDevice
import org.openstrap.app.zepp.DeviceListing

data class ScanUiState(
    val scanning: Boolean = false,
    val found: List<FoundDevice> = emptyList(),
    val error: String? = null,
)

class DeviceViewModel(private val container: AppContainer) : ViewModel() {
    val strap: StrapController get() = container.strap
    val companion: org.openstrap.app.device.CompanionLink get() = container.companion
    val paired: StateFlow<PairedDevice?> =
        container.settings.pairedDevice.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val profile: StateFlow<org.openstrap.app.data.settings.UserProfile> =
        container.settings.profile.stateIn(viewModelScope, SharingStarted.Eagerly, org.openstrap.app.data.settings.UserProfile())

    fun saveProfile(p: org.openstrap.app.data.settings.UserProfile) = viewModelScope.launch { container.settings.saveProfile(p) }

    private val _scan = MutableStateFlow(ScanUiState())
    val scan = _scan.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    private var scanJob: Job? = null

    fun startScan() {
        scanJob?.cancel()
        _scan.value = ScanUiState(scanning = true)
        scanJob = viewModelScope.launch {
            container.scanner.scan()
                .catch { e -> _scan.update { it.copy(scanning = false, error = e.message) } }
                .collect { d -> _scan.update { s -> s.copy(found = (s.found.filter { it.address != d.address } + d).sortedByDescending { it.rssi }) } }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _scan.update { it.copy(scanning = false) }
    }

    fun pair(address: String, name: String, authKey: String) {
        val key = normalizeAuthKey(authKey)
        if (key == null) {
            val n = authKey.trim().removePrefix("0x").removePrefix("0X").count { it.isLetterOrDigit() }
            _message.value = "The auth key must be 32 characters of 0–9 and a–f. You entered $n."
            return
        }
        if (!MAC.matches(address.uppercase())) {
            _message.value = "The Bluetooth address should look like C8:0F:10:AB:CD:EF. Scan to fill it in."
            return
        }
        stopScan()
        viewModelScope.launch {
            container.settings.savePairedDevice(PairedDevice(address.uppercase(), key, name))
            container.strap.connect()
            container.companion.refresh().join()
            if (container.companion.supported && !container.companion.associated.value) _offerBackground.value = true
        }
    }

    /** Keeps the Zepp sign-in for firmware checks and, when Zepp returned the strap's key, pairs with it straight away. */
    fun signInAndPair(listing: DeviceListing, strap: BoundDevice?) = viewModelScope.launch {
        container.settings.saveZeppAccount(listing.session.toAccount(listing.apiBase, strap?.china == true, System.currentTimeMillis()))
        val key = strap?.authKey
        if (strap != null && key != null) pair(strap.macAddress, DEFAULT_NAME, key)
        else _message.value = "Zepp sign-in saved, but no auth key came with it. Get the key on a computer and enter it by hand."
    }

    fun loadSampleData() = viewModelScope.launch {
        org.openstrap.app.data.SampleData.load(container.database.healthDao())
        _message.value = "Loaded a week of sample data."
    }

    /** Set at the end of pairing: the strap page then asks Android to associate the strap, once. */
    private val _offerBackground = MutableStateFlow(false)
    val offerBackground = _offerBackground.asStateFlow()
    fun backgroundOffered() { _offerBackground.value = false }

    val companionDeclined: StateFlow<Boolean> =
        container.settings.companionDeclined.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun showMessage(text: String) { _message.value = text }

    /** Find strap: buzzes it until stopped or Zepp's 60 s are up. */
    fun findStrap() = viewModelScope.launch {
        runCatching { container.strap.findStrap() }.onFailure { _message.value = it.message ?: "Couldn't buzz the strap" }
    }

    fun stopFindingStrap() = viewModelScope.launch {
        runCatching { container.strap.stopFindingStrap() }.onFailure { _message.value = it.message ?: "Couldn't stop the strap buzzing" }
    }

    /** Restarts the strap; the link drops and the usual reconnect brings it back. */
    fun restartStrap() = viewModelScope.launch {
        _message.value = runCatching { container.strap.restartStrap(); "Restarting the strap. OpenStrap reconnects when it's back." }
            .getOrElse { it.message ?: "Couldn't restart the strap" }
    }

    fun forget() = viewModelScope.launch {
        container.strap.disconnect()
        paired.value?.let { container.companion.disassociate(it.address) }
        container.settings.forgetDevice()
    }

    fun exportCsv(uri: Uri) = viewModelScope.launch {
        _message.value = runCatching {
            withContext(Dispatchers.IO) {
                container.app.contentResolver.openOutputStream(uri)!!.use { container.csv.exportZip(it) }
            }
            "Saved a copy."
        }.getOrElse { "Couldn't save the copy: ${it.message}" }
    }

    /** Restores a zip from [exportCsv] into the days before the strap's history. Safe to run twice. */
    fun importCsv(uri: Uri) = viewModelScope.launch {
        _message.value = runCatching {
            val result = withContext(Dispatchers.IO) {
                container.app.contentResolver.openInputStream(uri)!!.use { org.openstrap.app.export.CsvImporter(container.database).importZip(it) }
            }
            "Restored %,d records from before the strap's history. Left out %,d from days it has.".format(result.total, result.present) +
                (if (result.skipped > 0) " %,d unreadable rows skipped.".format(result.skipped) else "")
        }.getOrElse { "Couldn't restore the copy: ${it.message}" }
    }

    /** Brings back what OpenStrap exported to Health Connect, filling gaps only. */
    fun importHealthConnect() = viewModelScope.launch {
        _message.value = runCatching {
            val n = withContext(Dispatchers.IO) { container.healthConnect.import() }
            if (n == 0) "Health Connect has no OpenStrap data to restore." else "Restored %,d records from Health Connect.".format(n)
        }.getOrElse { "Couldn't restore from Health Connect: ${it.message}" }
    }

    fun exportHealthConnect() = viewModelScope.launch {
        _message.value = runCatching {
            val now = System.currentTimeMillis() / 1000
            val n = container.healthConnect.export(now - 30L * 24 * 3600, now)
            container.settings.setHealthConnectSynced(now)
            "Wrote $n records to Health Connect (last 30 days)."
        }.getOrElse { "Couldn't write to Health Connect: ${it.message}" }
    }

    /** Auto sync to Health Connect, for the You page's row. */
    val healthConnectAuto: StateFlow<org.openstrap.app.data.settings.HealthConnectAuto> =
        container.settings.healthConnectAuto.stateIn(viewModelScope, SharingStarted.Eagerly, org.openstrap.app.data.settings.HealthConnectAuto())

    /** The auto sync switch. On is called once the write permissions are granted, and writes the last 30 days at once. */
    fun setAutoHealthConnect(on: Boolean) = viewModelScope.launch {
        if (!on) { container.settings.setHealthConnectAuto(false); return@launch }
        _message.value = runCatching {
            val n = container.autoHealthConnect.turnOn()
            "Auto sync is on. Wrote $n records to Health Connect (last 30 days)."
        }.getOrElse { "Auto sync is on, but the first copy didn't work: ${it.message}" }
    }

    /** The You page opened: auto sync turns off, saying why, if Health Connect or its permissions went away. */
    fun checkAutoHealthConnect() = viewModelScope.launch {
        runCatching { container.autoHealthConnect.check() }
    }

    companion object {
        const val DEFAULT_NAME = "Amazfit Helio Strap"
        private val MAC = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

        fun isAddress(input: String) = MAC.matches(input.trim().uppercase())

        /** Accepts "0x…", spaces or colons; returns 32 lowercase hex chars or null. */
        fun normalizeAuthKey(input: String): String? {
            val hex = input.trim().removePrefix("0x").removePrefix("0X").filter { it.isLetterOrDigit() }.lowercase()
            return hex.takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        }
    }
}
