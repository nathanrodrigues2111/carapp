package com.lazyneil.lazycar

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.os.ParcelFileDescriptor

/** Auto Backup (fullBackupOnly) restores settings; this drops the volatile run state that came along with them. */
class Backup : BackupAgent() {
    override fun onBackup(o: ParcelFileDescriptor?, d: BackupDataOutput?, n: ParcelFileDescriptor?) {}
    override fun onRestore(d: BackupDataInput?, v: Int, n: ParcelFileDescriptor?) {}
    override fun onRestoreFinished() {
        Prefs(this).clearSnap()
        // musicTree: the folder grant does not survive a reinstall, a restored folder would list nothing.
        // tabletPlayer: legacy, the tablet's player is CarApp itself (TabletSpeaker.tabletPlayer).
        getSharedPreferences("lazycar", MODE_PRIVATE).edit().remove("state").remove("stateTs").remove("shutDown")
            .remove("musicTree").remove("tabletPlayer").commit()
        getSharedPreferences("rearview", MODE_PRIVATE).edit().remove("perms_asked").commit()
    }
}
