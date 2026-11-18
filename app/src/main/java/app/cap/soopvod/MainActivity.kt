package app.cap.soopvod

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var logView: TextView
    private lateinit var intervalBtn: Button
    private lateinit var fastBtn: Button
    private lateinit var smartBox: CheckBox
    private lateinit var bgBox: CheckBox
    private lateinit var masterSw: android.widget.Switch
    private lateinit var targetBtn: Button
    private lateinit var overlayBtn: Button
    private lateinit var batteryBtn: Button
    private lateinit var autoBox: CheckBox

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        fun header(t: String) = TextView(this).apply {
            text = t
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(6))
        }.also { root.addView(it) }

        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick() }
        }.also { root.addView(it) }

        masterSw = android.widget.Switch(this).apply {
            text = "숲 자동 다운로드"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(12))
            setOnClickListener {
                val on = isChecked
                val c = this@MainActivity
                Store.setEnabled(c, on)
                if (on) {
                    Store.resetBaselines(c)
                    Store.log(c, "자동 다운로드 켬 (꺼져 있던 동안 올라온 다시보기는 건너뜀)")
                    Scheduler.start(c)
                    CheckWorker.runNow(c)
                    refreshSoon()
                } else {
                    Scheduler.stop(c)
                    Store.log(c, "자동 다운로드 끔")
                }
                refresh()
            }
        }
        root.addView(masterSw)

        status = TextView(this).apply { textSize = 14f }
        root.addView(status)

        // ── 스트리머 ──
        header("스트리머")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(this).apply {
            hint = "아이디 또는 방송국 주소"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
        }
        val addBtn = Button(this).apply { text = "추가"; isAllCaps = false }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(addBtn)
        root.addView(row)
        val add = {
            val id = Soop.parseId(input.text.toString())
            if (id.isEmpty()) toast("아이디를 넣어줘")
            else if (!Store.addStreamer(this, id)) toast("이미 있어")
            else {
                input.setText("")
                Store.log(this, "$id 추가함, 기존 다시보기 확인 중…")
                Scheduler.start(this)
                CheckWorker.runNow(this)
                refreshSoon()
            }
            refresh()
        }
        addBtn.setOnClickListener { add() }
        input.setOnEditorActionListener { _, _, _ -> add(); true }

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        // ── 설정 ──
        header("설정")
        autoBox = CheckBox(this).apply {
            text = "새 다시보기를 찾으면 바로 다운로드 앱으로 보내기\n(끄면 알림만 띄움)"
            setOnCheckedChangeListener { _, b -> Store.setAutoSend(this@MainActivity, b) }
        }
        root.addView(autoBox)

        bgBox = CheckBox(this).apply {
            text = "YTDLnis에 '카드 없이 바로 받기' 신호 같이 보내기\n(끄면 YTDLnis 다운로드 카드 설정을 따름)"
            setOnCheckedChangeListener { _, b -> Store.setBgFlag(this@MainActivity, b) }
        }
        root.addView(bgBox)

        intervalBtn = button("") {
            pickMinutes("평소 확인 간격", intArrayOf(1, 2, 3, 5, 10, 15, 30, 60)) {
                Store.setInterval(this, it)
            }
        }

        smartBox = CheckBox(this).apply {
            text = "방송 중이거나 방송이 끝난 직후엔 더 자주 확인\n(다시보기가 올라오면 바로 잡기 위해, 종료 후 최대 3시간)"
            setOnCheckedChangeListener { _, b ->
                Store.setSmart(this@MainActivity, b)
                Scheduler.scheduleNext(this@MainActivity)
                refresh()
            }
        }
        root.addView(smartBox)

        fastBtn = button("") {
            pickMinutes("방송 중·종료 직후 확인 간격", intArrayOf(1, 2, 3, 5)) {
                Store.setFastInterval(this, it)
            }
        }

        targetBtn = button("") { pickTarget() }

        overlayBtn = button("") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        batteryBtn = button("") {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        // ── 실행 ──
        header("실행")
        button("지금 확인") {
            if (Store.streamers(this).isEmpty()) toast("스트리머부터 추가해줘")
            else if (!Store.enabled(this)) toast("맨 위 스위치가 꺼져 있어")
            else {
                CheckWorker.runNow(this)
                toast("확인 중… 잠시 뒤 기록에 나와")
                refreshSoon()
            }
        }
        button("테스트: 최신 다시보기 1개 보내보기") { testSend() }

        header("기록")
        button("기록 지우기") { Store.clearLogs(this); refresh() }
        logView = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Store.streamers(this).isNotEmpty()) Scheduler.start(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val last = Store.lastCheck(this)
        val lastTxt = if (last == 0L) "아직 없음"
        else SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(last))
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
        val next = Store.nextCheck(this)
        val nextTxt = if (next == 0L) "예약 없음" else fmt.format(Date(next))
        val mode = if (Store.smart(this) && Store.isHot(this)) "빠른 확인 중 (${Scheduler.currentIntervalMin(this)}분마다)"
        else "평소 (${Store.interval(this)}분마다)"
        val on = Store.enabled(this)
        masterSw.isChecked = on
        status.text = if (!on) "⏸ 꺼져 있음 — 확인도 다운로드도 안 함\n보낼 앱: ${Handoff.targetLabel(this)}"
        else "마지막 확인: $lastTxt\n다음 확인: $nextTxt · $mode\n보낼 앱: ${Handoff.targetLabel(this)}"

        listBox.removeAllViews()
        val ids = Store.streamers(this)
        if (ids.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "아직 없음. 위에 아이디를 넣고 추가해줘."
                setPadding(0, dp(6), 0, dp(6))
            })
        }
        for (id in ids) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val now = System.currentTimeMillis()
            val tag = when {
                Store.wasLive(this, id) -> "  🔴 방송 중"
                Store.hotUntil(this, id) > now -> "  ⏳ 다시보기 기다리는 중"
                else -> ""
            }
            r.addView(TextView(this).apply {
                text = id + tag
                textSize = 16f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(Button(this).apply {
                text = "삭제"
                isAllCaps = false
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("$id 를 목록에서 뺄까?")
                        .setPositiveButton("빼기") { _, _ ->
                            Store.removeStreamer(this@MainActivity, id)
                            Scheduler.scheduleNext(this@MainActivity)
                            refresh()
                        }
                        .setNegativeButton("취소", null).show()
                }
            })
            listBox.addView(r)
        }

        autoBox.isChecked = Store.autoSend(this)
        bgBox.isChecked = Store.bgFlag(this)
        intervalBtn.text = "평소 확인 간격: ${Store.interval(this)}분마다 (바꾸기)"
        smartBox.isChecked = Store.smart(this)
        fastBtn.text = "방송 중·종료 직후 간격: ${Store.fastInterval(this)}분마다 (바꾸기)"
        fastBtn.visibility = if (Store.smart(this)) View.VISIBLE else View.GONE
        targetBtn.text = "보낼 앱 고르기 (지금: ${Handoff.targetLabel(this)})"

        val overlayOk = Handoff.canAutoStart(this)
        overlayBtn.text = if (overlayOk) "✓ 다른 앱 위에 표시 권한 있음"
        else "⚠ 다른 앱 위에 표시 권한 켜기 (자동으로 보내려면 필요)"

        val pm = getSystemService(PowerManager::class.java)
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        batteryBtn.text = if (batteryOk) "✓ 배터리 최적화 제외됨"
        else "⚠ 배터리 최적화에서 빼기 (안 하면 화면 꺼진 동안 9분 이하 간격이 안 지켜짐)"

        logView.text = Store.logs(this).ifBlank { "(기록 없음)" }
    }

    /** 작업이 끝날 즈음 화면 다시 그리기 */
    private fun refreshSoon() {
        for (ms in longArrayOf(2500, 6000, 12000)) ui.postDelayed({ refresh() }, ms)
    }

    /** 분 단위 간격 고르기 (+ 직접 입력) */
    private fun pickMinutes(title: String, opts: IntArray, save: (Int) -> Unit) {
        val labels = opts.map { "${it}분" }.toMutableList().apply { add("직접 입력…") }
        AlertDialog.Builder(this).setTitle(title)
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < opts.size) {
                    save(opts[i]); Scheduler.scheduleNext(this); refresh()
                } else {
                    val et = EditText(this).apply {
                        inputType = InputType.TYPE_CLASS_NUMBER
                        hint = "분 (1 이상)"
                    }
                    AlertDialog.Builder(this).setTitle(title).setView(et)
                        .setPositiveButton("확인") { _, _ ->
                            val v = et.text.toString().toIntOrNull()
                            if (v == null || v < 1) toast("1 이상 숫자로 넣어줘")
                            else {
                                save(v); Scheduler.scheduleNext(this); refresh()
                            }
                        }
                        .setNegativeButton("취소", null).show()
                }
            }.show()
    }

    private fun pickTarget() {
        val list = Handoff.targets(this).sortedBy { it.loadLabel(packageManager).toString() }
        if (list.isEmpty()) {
            toast("링크를 받을 수 있는 앱이 없어")
            return
        }
        val labels = list.map {
            val app = it.activityInfo.applicationInfo.loadLabel(packageManager).toString()
            val act = it.loadLabel(packageManager).toString()
            if (app == act) app else "$app – $act"
        }.toMutableList()
        labels.add(0, "자동 (YTDLnis 찾기)")
        AlertDialog.Builder(this).setTitle("다운로드 링크를 보낼 앱")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i == 0) Store.setTarget(this, "")
                else {
                    val ai = list[i - 1].activityInfo
                    Store.setTarget(this, "${ai.packageName}/${ai.name}")
                }
                refresh()
            }.show()
    }

    private fun testSend() {
        val id = Store.streamers(this).firstOrNull()
        if (id == null) {
            toast("스트리머부터 추가해줘")
            return
        }
        toast("$id 최신 다시보기 찾는 중…")
        Thread {
            val res = try {
                Result.success(Soop.latestReplays(id, 5))
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                res.onFailure {
                    Store.log(this, "테스트 실패 ($id): ${it.message}")
                    refresh()
                }
                res.onSuccess { vods ->
                    val v = vods.firstOrNull()
                    if (v == null) {
                        Store.log(this, "테스트: $id 다시보기가 하나도 없음")
                        refresh()
                        return@onSuccess
                    }
                    Store.log(this, "테스트: ${v.title} (${v.url})")
                    refresh()
                    if (!Handoff.send(this, v.url, fromForeground = true)) {
                        startActivity(Handoff.intentFor(this, v.url))
                    }
                }
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
