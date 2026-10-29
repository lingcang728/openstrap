package org.openstrap.app.ui.device

import org.openstrap.app.data.settings.BackupState
import org.openstrap.app.export.AutoBackup
import org.openstrap.app.export.BackupFiles
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The words of backup on the You and Backup pages and in its messages. */
object BackupCopy {
    /** The Automatic backup row's value: off, waiting for the first one, the last one, or why the last one failed. */
    fun status(state: BackupState, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        if (!state.on) return "Off. A zip each day in a folder you choose"
        state.failed?.let { return "Last backup failed. ${failure(it)}" }
        val done = state.lastDone ?: return "Each day while the phone charges. No backup yet"
        return "Last backup ${whenDone(done, now, zone, locale)}"
    }

    /** The You page's Backup row: off, waiting for the first one, the last one, or that it failed (the page says why). */
    fun summary(state: BackupState, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        if (!state.on) return "Off"
        if (state.failed != null) return "Last backup failed"
        val done = state.lastDone ?: return "On. No backup yet"
        return "Last backup ${whenDone(done, now, zone, locale)}"
    }

    /** Why a backup failed, said once. */
    fun failure(f: BackupFiles.Failure): String = when (f) {
        BackupFiles.Failure.FolderGone -> "The folder is no longer available."
        BackupFiles.Failure.PermissionLost -> "OpenStrap can no longer reach the folder. Choose it again."
        BackupFiles.Failure.NoSpace -> "There isn't enough space for it."
        BackupFiles.Failure.CantWrite -> "OpenStrap couldn't write to the folder."
        BackupFiles.Failure.Other -> "It tries again tomorrow."
    }

    /** "today at 03:12", "yesterday at 03:12", "on Tue 20 Oct". */
    fun whenDone(epochSeconds: Long, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val at = Instant.ofEpochSecond(epochSeconds).atZone(zone)
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val clock = at.format(DateTimeFormatter.ofPattern("HH:mm", locale))
        return when (at.toLocalDate()) {
            today -> "today at $clock"
            today.minusDays(1) -> "yesterday at $clock"
            else -> "on ${at.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))}"
        }
    }

    /**
     * Where the folder picker opens when backup is first turned on. The top of the phone's storage can't be chosen
     * ("Can't use this folder"), Documents can, and a folder made inside it too.
     */
    val DOCUMENTS: android.net.Uri
        get() = android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")

    /**
     * What a restore says, short enough for a toast: what came back, and rows that couldn't be read. Records already on
     * this phone aren't counted, since restoring only fills gaps.
     */
    fun restored(records: Int, settings: Boolean, skipped: Int): String {
        val what = when {
            records > 0 && settings -> "Restored %,d records and your settings.".format(records)
            records > 0 -> "Restored %,d records.".format(records)
            settings -> "Restored your settings. Your records were already here."
            else -> "Nothing to restore. It's all already here."
        }
        return if (skipped > 0) "$what %,d rows couldn't be read.".format(skipped) else what
    }

    /** The Backup folder row's value. */
    fun folder(state: BackupState): String = state.folderName ?: "The folder you chose"

    /** What Back up now says when it's done. */
    fun outcome(o: AutoBackup.Outcome): String = when (o) {
        is AutoBackup.Outcome.Done -> "Backed up as ${o.name}."
        AutoBackup.Outcome.Recording -> "A workout is recording. The backup runs after it ends."
        AutoBackup.Outcome.Off -> "Automatic backup is off."
        is AutoBackup.Outcome.Failed -> "Couldn't back up. ${failure(o.failure)}"
    }
}
