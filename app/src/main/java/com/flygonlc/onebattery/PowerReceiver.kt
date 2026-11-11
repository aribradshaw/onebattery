package com.flygonlc.onebattery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Logs a sample at every plug/unplug, and restarts the monitor after boot or an app update. */
class PowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SampleWorker.record(context)
        SampleWorker.schedule(context)
        MonitorService.start(context)
    }
}
