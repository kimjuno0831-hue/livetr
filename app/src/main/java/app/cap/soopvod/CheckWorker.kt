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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** 주기적으로 다시보기 목록을 확인하고 새 것만 넘기는 작업 */
class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = lock.withLock {
        val c = applicationContext
        for (id in Store.streamers(c)) {
            val vods = try {
                withContext(Dispatchers.IO) { Soop.latestReplays(id) }
            } catch (e: Exception) {
                Store.log(c, "$id 확인 실패: ${e.message}")
                continue
            }
            val seen = Store.seen(c, id)

            // 처음 등록한 스트리머: 지금 있는 다시보기는 건너뛰고 기준점만 잡음
            if (!Store.isInit(c, id)) {
                seen.addAll(vods.map { it.no })
                Store.setSeen(c, id, seen)
                Store.setInit(c, id)
                Store.log(c, "$id 등록 완료 (기존 다시보기 ${vods.size}개는 건너뜀)")
                continue
            }

            // 다시보기가 지워져서 오래된 게 목록에 밀려 올라온 경우는 무시
            val minSeen = seen.mapNotNull { it.toLongOrNull() }.minOrNull()
            val fresh = vods.filter { v ->
                v.no !in seen && (minSeen == null || (v.no.toLongOrNull() ?: 0L) > minSeen)
            }.reversed() // 오래된 것부터

            for (v in fresh) {
                val sent = Store.autoSend(c) && Handoff.send(c, v.url)
                Notifier.newVod(c, id, v, sent)
                Store.log(c, "$id 새 다시보기: ${v.title} → " + if (sent) "보냄" else "알림만")
                seen.add(v.no)
                Store.setSeen(c, id, seen)
                if (sent) delay(4000)
            }
        }
        Store.setLastCheck(c, System.currentTimeMillis())
        Result.success()
    }

    companion object {
        private val lock = Mutex()

        private val net = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(c: Context) {
            val req = PeriodicWorkRequestBuilder<CheckWorker>(Store.interval(c).toLong(), TimeUnit.MINUTES)
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
