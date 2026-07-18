package com.vynex.gumshoos

import android.app.Application

class GumshoosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Installed here so it covers the service process too, not just the activity.
        CrashLog.install(this)

        // Loaded here rather than in the service: the widget provider and the activity can each be
        // the first thing to run in this process, and both read config. Loading it only in
        // OverlayService left them reading defaults.
        GumshoosConfig.load(this)
        EventLog.init(this)
        EventLog.record("process start")
    }
}
