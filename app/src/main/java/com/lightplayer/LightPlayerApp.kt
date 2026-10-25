package com.lightplayer

import android.app.Application

class LightPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The crash screen lives in its own process; don't hook crashes there.
        if (!getProcessName().endsWith(":crash")) CrashHandler.install(this)
    }
}
