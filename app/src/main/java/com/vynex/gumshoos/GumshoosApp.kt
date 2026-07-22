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
        // Same reasoning as the config: the activity reads this too, and either it or the service
        // can be the first thing to run in the process.
        LaunchHistory.load(this)
        // The last weather reading, for the same reason: the widget provider may be first to run
        // and draws from this cache without ever fetching.
        WeatherRepository.load(this)
        EventLog.init(this)
        EventLog.record("process start")
    }
}
