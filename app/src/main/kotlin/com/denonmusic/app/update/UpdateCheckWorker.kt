package com.denonmusic.app.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.denonmusic.app.BuildConfig

/** Background half of the update check - just refreshes [UpdatePrefsStore]'s cached release, same as
 *  [UpdateViewModel.check]; the About tab picks up whatever this last found next time it's opened. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val store = UpdatePrefsStore(applicationContext)
        val latest = UpdateManager.checkLatest() ?: return Result.success()
        if (UpdateManager.isNewer(latest.versionName, BuildConfig.VERSION_NAME)) {
            store.saveLatestRelease(latest)
        } else {
            store.clearLatestRelease()
        }
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
