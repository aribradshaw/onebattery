package com.flygonlc.onebattery

import android.app.Application

class OneBatteryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SampleWorker.schedule(this)
    }
}
