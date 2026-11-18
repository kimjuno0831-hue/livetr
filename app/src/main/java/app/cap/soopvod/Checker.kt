package app.cap.soopvod

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/** 실제 확인 작업 (알람·WorkManager·버튼 모두 여기로) */
object Checker {
    private val running = AtomicBoolean(false)

    private const val HOT_AFTER_END = 3 * 60 * 60 * 1000L   // 방송 종료 후 3시간 동안 자주 확인
    private const val HOT_AFTER_FOUND = 20 * 60 * 1000L     // 찾은 뒤에도 20분은 더 (다시보기가 여러 개로 나뉠 때)

    /** 이미 돌고 있으면 false */
    fun run(c: Context): Boolean {
        if (!Store.enabled(c)) return false
        if (!running.compareAndSet(false, true)) return false
        try {
            for (id in Store.streamers(c)) checkOne(c, id)
            Store.setLastCheck(c, System.currentTimeMillis())
        } finally {
            running.set(false)
        }
        return true
    }

    private fun checkOne(c: Context, id: String) {
        val now = System.currentTimeMillis()

        // 1) 방송 상태 (빠른 확인 모드일 때만)
        if (Store.smart(c)) {
            when (Soop.isLive(id)) {
                true -> if (!Store.wasLive(c, id)) {
                    Store.setWasLive(c, id, true)
                    Store.log(c, "$id 방송 시작 감지")
                }
                false -> if (Store.wasLive(c, id)) {
                    Store.setWasLive(c, id, false)
                    Store.setHotUntil(c, id, now + HOT_AFTER_END)
                    Store.log(c, "$id 방송 종료 감지 → 다시보기 나올 때까지 자주 확인")
                }
                null -> {}
            }
        }

        // 2) 다시보기 목록
        val vods = try {
            Soop.latestReplays(id)
        } catch (e: Exception) {
            Store.log(c, "$id 확인 실패: ${e.message}")
            return
        }
        val seen = Store.seen(c, id)

        if (!Store.isInit(c, id)) {
            seen.addAll(vods.map { it.no })
            Store.setSeen(c, id, seen)
            Store.setInit(c, id)
            Store.log(c, "$id 등록 완료 (기존 다시보기 ${vods.size}개는 건너뜀)")
            return
        }

        val minSeen = seen.mapNotNull { it.toLongOrNull() }.minOrNull()
        val fresh = vods.filter { v ->
            v.no !in seen && (minSeen == null || (v.no.toLongOrNull() ?: 0L) > minSeen)
        }.reversed()

        for (v in fresh) {
            val sent = Store.autoSend(c) && Handoff.send(c, v.url)
            Notifier.newVod(c, id, v, sent)
            Store.log(c, "$id 새 다시보기: ${v.title} → " + if (sent) "보냄" else "알림만")
            seen.add(v.no)
            Store.setSeen(c, id, seen)
            if (sent) Thread.sleep(4000)
        }
        if (fresh.isNotEmpty() && !Store.wasLive(c, id)) {
            Store.setHotUntil(c, id, now + HOT_AFTER_FOUND)
        }
    }
}
