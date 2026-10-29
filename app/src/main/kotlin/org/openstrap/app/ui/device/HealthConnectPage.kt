package org.openstrap.app.ui.device

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.health.connect.client.HealthConnectClient
import org.openstrap.app.data.settings.HealthConnectAuto
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowSwitch
import org.openstrap.app.ui.glow.PageHeader
import org.openstrap.app.ui.glow.PageRow
import org.openstrap.app.ui.glow.PageRows
import org.openstrap.app.ui.theme.AppIcons

/**
 * The Health Connect page, from the You page: auto sync and its switch (on asks for the permissions first), then a
 * copy now, a restore and Health Connect's own screen for OpenStrap, where its permissions and data are.
 */
@Composable
fun HealthConnectPage(
    auto: HealthConnectAuto,
    contentPadding: PaddingValues,
    onAuto: (Boolean) -> Unit,
    onCopyNow: () -> Unit,
    onRestore: () -> Unit,
    onOpen: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader("Health Connect", onBack) }
        item {
            PageRows {
                val line = healthConnectLine(auto, System.currentTimeMillis() / 1000, java.time.ZoneId.systemDefault())
                PageRow("Auto sync to Health Connect", line, onClick = { onAuto(!auto.on) }, below = true, divider = false, icon = AppIcons.Refresh04) {
                    GlowSwitch(auto.on, onAuto)
                }
            }
        }
        item { PanelHeading("Any time") }
        item {
            PageRows {
                PageRow("Copy now", "The last 30 days, to Android Health Connect", onClick = onCopyNow, below = true, divider = false, icon = AppIcons.Heartbeat)
                PageRow("Restore", "Bring back what OpenStrap saved there", onClick = onRestore, below = true, icon = AppIcons.ArrowCounterClockwise)
                PageRow("Open Health Connect", "Permissions and data", onClick = onOpen, below = true, icon = AppIcons.SlidersHorizontal)
            }
        }
    }
}

/**
 * Opens Health Connect on OpenStrap's own data and permissions, or on its main settings where that screen isn't there.
 * False when neither opens (Health Connect missing or disabled).
 */
fun openHealthConnect(context: Context): Boolean {
    val intents = listOf(
        { HealthConnectClient.getHealthConnectManageDataIntent(context) },
        { Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS) },
    )
    return intents.any { make ->
        runCatching { context.startActivity(make().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}
