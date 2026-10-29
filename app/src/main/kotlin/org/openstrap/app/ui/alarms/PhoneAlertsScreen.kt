package org.openstrap.app.ui.alarms

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import org.openstrap.app.data.settings.BuzzPattern
import org.openstrap.app.data.settings.CallBuzz
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.device.PhoneAlerts
import org.openstrap.app.device.StrapNotificationListener
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.PageButton
import org.openstrap.app.ui.glow.PageHeader
import org.openstrap.app.ui.glow.PageNote
import org.openstrap.app.ui.glow.PagePanel
import org.openstrap.app.ui.glow.PageSwitchRow
import androidx.compose.ui.graphics.Color
import org.openstrap.app.ui.glow.GlowSegments
import org.openstrap.app.ui.theme.MetricColors

/** What the Strap page's Phone alerts row says: "Calls and notifications", "Calls", "Notifications" or "Off". */
fun phoneAlertsSummary(calls: Boolean, notifications: Boolean): String = when {
    calls && notifications -> "Calls and notifications"
    calls -> "Calls"
    notifications -> "Notifications"
    else -> "Off"
}

/**
 * Buzzing the strap on calls and notifications, opened from the Strap page. These are phone settings, so they work
 * offline too. (The off-body reminder is a row of its own on the Strap page.)
 */
@Composable
fun PhoneAlertsScreen(alerts: PhoneAlerts, settings: SettingsStore, contentPadding: PaddingValues, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val calls by settings.buzzOnCalls.collectAsState(false)
    val notifications by settings.buzzOnNotifications.collectAsState(false)
    val callPermission by alerts.callPermission.collectAsState()
    val notificationAccess by alerts.notificationAccess.collectAsState()
    val pattern by settings.notificationPattern.collectAsState(BuzzPattern.TWO_SHORT)
    val callStyle by settings.callBuzzStyle.collectAsState(CallBuzz.STEADY)
    var previewError by remember { mutableStateOf<String?>(null) }
    var callPreviewError by remember { mutableStateOf<String?>(null) }

    // The user may grant access in system settings and come back.
    LifecycleResumeEffect(Unit) { alerts.refresh(); onPauseOrDispose { } }

    val askPhone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        alerts.refresh()
        if (granted) scope.launch { settings.setBuzzOnCalls(true) }
    }
    val openNotificationAccess = {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, StrapNotificationListener::class.java).flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        Unit
    }

    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader("Phone alerts", onBack) }
        item {
            PagePanel {
                PageSwitchRow(
                    "Incoming calls", "Buzzes while your phone rings. App calls like WhatsApp need notification access.",
                    calls && callPermission,
                    { on ->
                        when {
                            !on -> scope.launch { settings.setBuzzOnCalls(false) }
                            callPermission -> scope.launch { settings.setBuzzOnCalls(true) }
                            else -> askPhone.launch(Manifest.permission.READ_PHONE_STATE)
                        }
                    },
                    divider = false,
                )
                if (calls && callPermission) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Call buzz", style = GlowType.body, color = Color.White)
                        // Picking one plays it for a few seconds, so the two can be compared on the wrist.
                        GlowSegments(CallBuzz.entries.map { it.label }, callStyle.ordinal, { i ->
                            val style = CallBuzz.entries[i]
                            scope.launch {
                                settings.setCallBuzzStyle(style)
                                callPreviewError = runCatching { alerts.previewCall(style) }.exceptionOrNull()?.let { it.message ?: "Couldn't buzz the strap" }
                            }
                        })
                        callPreviewError?.let { PageNote(it, color = MetricColors.warn) }
                    }
                }
                PageSwitchRow("Notifications", "Buzzes for notifications that make a sound.", notifications && notificationAccess, { on ->
                    scope.launch { settings.setBuzzOnNotifications(on) }
                    if (on && !notificationAccess) openNotificationAccess()
                })
                if (notifications && !notificationAccess) {
                    PageNote("Needs notification access.", color = MetricColors.warn)
                    PageButton("Open settings", openNotificationAccess, Modifier.fillMaxWidth())
                }
                if (notifications && notificationAccess) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Buzz pattern", style = GlowType.body, color = Color.White)
                        // Picking a pattern plays it once, so it can be felt before the next notification.
                        GlowSegments(BuzzPattern.entries.map { it.label }, pattern.ordinal, { i ->
                            val p = BuzzPattern.entries[i]
                            scope.launch {
                                settings.setNotificationPattern(p)
                                previewError = runCatching { alerts.preview(p) }.exceptionOrNull()?.let { it.message ?: "Couldn't buzz the strap" }
                            }
                        })
                        previewError?.let { PageNote(it, color = MetricColors.warn) }
                    }
                }
                // Do Not Disturb is said once, here, for calls and notifications both.
                PageNote("Follows Do Not Disturb and silent apps. Never reads what a notification says.")
            }
        }
    }
}
