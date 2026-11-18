package app.cap.soopvod

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** "지금 확인" 버튼 + 알람이 끊겼을 때를 대비한 15분 예비 확인 */
class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext
        withContext(Dispatchers.IO) { Checker.run(c) }
        Scheduler.scheduleNext(c) // 알람 사슬이 끊겼어도 여기서 다시 이어짐
        return Result.success()
    }

    companion object {
        private val net = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(c: Context) {
            val req = PeriodicWorkRequestBuilder<CheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(net)
                .build()
            WorkManager.getInstance(c)
                .enqueueUniquePeriodicWork("check", ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun runNow(c: Context) {
            val req = OneTimeWorkRequestBuilder<CheckWorker>().setConstraints(net).build()
            WorkManager.getInstance(c).enqueueUniqueWork("now", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
