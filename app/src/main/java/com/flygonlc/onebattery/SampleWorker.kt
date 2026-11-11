package com.flygonlc.onebattery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class SampleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        record(applicationContext)
        BatteryDb.get(applicationContext).prune(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(365))
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SampleWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("battery-sample", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun record(context: Context) {
            BatteryReader.read(context)?.let { BatteryDb.get(context).insert(it) }
        }
    }
}
