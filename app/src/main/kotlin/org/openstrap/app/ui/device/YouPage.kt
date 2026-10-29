package org.openstrap.app.ui.device

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.openstrap.app.BuildConfig
import org.openstrap.app.data.settings.Goals
import org.openstrap.app.data.settings.UserProfile
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.PageHeader
import org.openstrap.app.ui.glow.PageRow
import org.openstrap.app.ui.glow.PageRows
import org.openstrap.app.ui.theme.AppIcons

/** "7 h 30", or "8 h" on the hour. */
fun hoursMinutes(minutes: Int): String = if (minutes % 60 == 0) "${minutes / 60} h" else "${minutes / 60} h ${"%02d".format(minutes % 60)}"

/** The Goals row's value: "8,000 steps, 8 active hours". */
fun goalsLine(goals: Goals): String = "%,d steps, %d active hours".format(goals.steps, goals.activeHours)

/** The Sleep goal row's value, with the sleep need Recovery uses beside it: "7 h 30. Your sleep need is 8 h". */
fun sleepGoalLine(goals: Goals, needMinutes: Int): String =
    "${hoursMinutes(goals.sleepMinutes)}. Your sleep need is ${hoursMinutes(needMinutes)}"

/**
 * The You page (spec 3.4), from the avatar on any tab: who you are and what happens to your data, things visited
 * monthly. A page like the Strap page rather than a sheet, because most rows open pages of their own and Back from
 * those should come back here. Every row shows its current value under its name, with an icon on its left.
 */
@Composable
fun YouPage(
    profile: UserProfile,
    goals: Goals,
    /** The sleep need Recovery uses, in minutes: the profile's own or the one for your age. */
    sleepNeedMinutes: Int,
    logLines: Int,
    contentPadding: PaddingValues,
    onProfile: () -> Unit,
    onGoals: () -> Unit,
    onSaveCopy: () -> Unit,
    onRestoreCopy: () -> Unit,
    onHealthConnect: () -> Unit,
    onRestoreHealthConnect: () -> Unit,
    onDiagnostics: () -> Unit,
    onSampleData: (() -> Unit)?,
    onBack: () -> Unit,
    /** The row whose page is open beside the list on a wide screen, by its title ("Profile"), or null. */
    selected: String? = null,
    /** Automatic backup's rows ([AutoBackupRows]), under Your data. */
    autoBackup: (@Composable () -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader("You", onBack) }
        item {
            PageRows {
                PageRow("Profile", profile.summary(), onClick = onProfile, below = true, divider = false, icon = AppIcons.User, selected = selected == "Profile")
                PageRow("Goals", goalsLine(goals), onClick = onGoals, below = true, icon = AppIcons.Target)
                PageRow("Sleep goal", sleepGoalLine(goals, sleepNeedMinutes), onClick = onGoals, below = true, icon = AppIcons.Moon)
            }
        }
        item { Text("Your data", color = Color.White.copy(alpha = 0.6f), style = GlowType.secondary, modifier = Modifier.padding(start = 18.dp, top = 4.dp)) }
        item {
            PageRows {
                PageRow("Save a copy", "A zip of every synced record, saved where you choose", onClick = onSaveCopy, below = true, divider = false, icon = AppIcons.DownloadSimple)
                PageRow("Restore from a copy", "For days before the strap's history", onClick = onRestoreCopy, below = true, icon = AppIcons.UploadSimple)
                PageRow("Health Connect", "Copy your data to Android Health Connect", onClick = onHealthConnect, below = true, icon = AppIcons.Heartbeat)
                PageRow("Restore from Health Connect", "Bring back what OpenStrap saved there", onClick = onRestoreHealthConnect, below = true, icon = AppIcons.ArrowCounterClockwise)
            }
        }
        if (autoBackup != null) item { autoBackup() }
        item {
            PageRows {
                PageRow("Diagnostics", "$logLines lines, for bug reports", onClick = onDiagnostics, below = true, divider = false, icon = AppIcons.Bug, selected = selected == "Diagnostics")
                if (onSampleData != null) PageRow("Load sample data", "Debug builds only", onClick = onSampleData, below = true, icon = AppIcons.Flask)
            }
        }
        item {
            Text(
                "OpenStrap ${BuildConfig.VERSION_NAME}. Everything stays on this phone.",
                Modifier.fillMaxWidth().padding(top = 4.dp),
                color = Color.White.copy(alpha = 0.45f), style = GlowType.secondary, textAlign = TextAlign.Center,
            )
        }
    }
}
