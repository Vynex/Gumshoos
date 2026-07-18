package com.vynex.gumshoos

import android.app.Application

class GumshoosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Installed here so it covers the service process too, not just the activity.
        CrashLog.install(this)
    }
}
