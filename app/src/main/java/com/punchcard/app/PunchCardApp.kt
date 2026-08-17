package com.punchcard.app

import android.app.Application
import com.punchcard.app.backup.BackupScheduler

class PunchCardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Real OS-level background scheduling (WorkManager) — this is the
        // whole reason this is a native app: it can check "is it night and
        // on wifi yet?" even while the app itself is closed.
        BackupScheduler.schedule(this)
    }
}
