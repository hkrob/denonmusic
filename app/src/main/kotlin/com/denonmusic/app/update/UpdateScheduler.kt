package com.denonmusic.app.update

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "update_check"

object UpdateScheduler {
    fun schedule(context: Context, frequency: UpdateCheckFrequency) {
        val workManager = WorkManager.getInstance(context)
        if (frequency == UpdateCheckFrequency.NEVER) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(frequency.days, TimeUnit.DAYS).build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
