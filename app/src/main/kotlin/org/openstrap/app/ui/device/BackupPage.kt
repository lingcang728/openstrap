package org.openstrap.app.ui.device

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.openstrap.app.data.settings.BackupState
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowSwitch
import org.openstrap.app.ui.glow.PageHeader
import org.openstrap.app.ui.glow.PageRow
import org.openstrap.app.ui.glow.PageRows
import org.openstrap.app.ui.theme.AppIcons

/**
 * The Backup page, from the You page: automatic backup first (the switch, with the last backup or why it failed under
 * it, then, while on, the folder, Back up now and Restore from a backup), then a copy saved by hand and restoring from
 * any zip. Turning backup on asks for the folder first ([onTurnOn]).
 */
@Composable
fun BackupPage(
    state: BackupState,
    backingUp: Boolean,
    contentPadding: PaddingValues,
    onTurnOn: () -> Unit,
    onTurnOff: () -> Unit,
    onFolder: () -> Unit,
    onBackUpNow: () -> Unit,
    onRestore: () -> Unit,
    onSaveCopy: () -> Unit,
    onRestoreFile: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // Read again whenever the state changes, so "today" follows the last backup.
    val now = remember(state, backingUp) { System.currentTimeMillis() / 1000 }
    val toggle = { on: Boolean -> if (on) onTurnOn() else onTurnOff() }
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader("Backup", onBack) }
        item {
            PageRows {
                PageRow("Automatic backup", BackupCopy.status(state, now), onClick = { toggle(!state.on) }, below = true, divider = false, icon = AppIcons.ShieldCheck) {
                    GlowSwitch(state.on, toggle)
                }
                if (state.on) {
                    PageRow("Backup folder", BackupCopy.folder(state), onClick = onFolder, below = true, icon = AppIcons.FolderOpen)
                    PageRow(
                        "Back up now", if (backingUp) "Backing up" else "Saves today's zip in that folder",
                        onClick = onBackUpNow, below = true, enabled = !backingUp, icon = AppIcons.CloudArrowDown,
                    )
                    PageRow("Restore from a backup", "Choose one of the zips in that folder", onClick = onRestore, below = true, icon = AppIcons.ArrowCounterClockwise)
                }
            }
        }
        item { PanelHeading("Copies you keep") }
        item {
            PageRows {
                PageRow("Save a copy", "A zip of every synced record, saved where you choose", onClick = onSaveCopy, below = true, divider = false, icon = AppIcons.DownloadSimple)
                PageRow("Restore from a file", "Any OpenStrap zip. Fills in what this phone is missing", onClick = onRestoreFile, below = true, icon = AppIcons.UploadSimple)
            }
        }
    }
}

/** The file picker for Restore from a backup, opening on the backup folder (a tree URI) when there is one. */
class PickBackup : androidx.activity.result.contract.ActivityResultContract<android.net.Uri?, android.net.Uri?>() {
    override fun createIntent(context: android.content.Context, input: android.net.Uri?): android.content.Intent =
        android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            .apply {
                if (input != null) runCatching {
                    android.provider.DocumentsContract.buildDocumentUriUsingTree(input, android.provider.DocumentsContract.getTreeDocumentId(input))
                }.getOrNull()?.let { putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, it) }
            }

    override fun parseResult(resultCode: Int, intent: android.content.Intent?): android.net.Uri? =
        intent?.data?.takeIf { resultCode == android.app.Activity.RESULT_OK }
}
