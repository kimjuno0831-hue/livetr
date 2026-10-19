package app.cap.livetr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var engineGroup: RadioGroup
    private lateinit var keyBox: EditText
    private lateinit var modelBox: EditText
    private lateinit var geminiArea: LinearLayout
    private lateinit var glossaryBox: EditText
    private lateinit var worksBox: LinearLayout
    private lateinit var asrGroup: RadioGroup
    private lateinit var asrStatus: TextView
    private lateinit var asrDownload: Button
    @Volatile private var downloading = false
    private val reqPerm = 1
    private val reqCapture = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs.get(this)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun title(t: String) = TextView(this).apply {
            text = t
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (20 * d).toInt(), 0, (4 * d).toInt())
        }
        fun note(t: String) = TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(Color.GRAY)
        }
        fun check(label: String, key: String, def: Boolean) = CheckBox(this).apply {
            text = label
            isChecked = p.getBoolean(key, def)
            setOnCheckedChangeListener { _, v -> p.edit().putBoolean(key, v).apply() }
        }

        // ── 실행 (맨 위)
        status = TextView(this).apply {
            textSize = 15f
            text = "[자막 시작]을 누르고 권한을 모두 허용한 뒤, 빌리빌리에서 영상을 재생하세요."
        }
        col.addView(status)
        val runRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        runRow.addView(Button(this).apply {
            text = "자막 시작"
            setOnClickListener { save(); begin() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        runRow.addView(Button(this).apply {
            text = "자막 중지"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                status.text = "중지했어요."
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(runRow)

        // ── 번역기
        col.addView(title("번역기"))
        engineGroup = RadioGroup(this)
        engineGroup.addView(RadioButton(this).apply { id = 101; text = "구글 번역 (무료, 키 필요 없음)" })
        engineGroup.addView(RadioButton(this).apply {
            id = 102
            text = "제미나이 번역 — 폰이 받아 적은 글자를 제미나이가 번역. 용어집 적용 (무료 키 필요)"
        })
        engineGroup.addView(RadioButton(this).apply { id = 103; text = "기기 안 번역 (인터넷 없이, 품질 낮음)" })
        engineGroup.addView(RadioButton(this).apply {
            id = 105
            text = "제미나이 듣고 번역 (추천) — 말소리를 제미나이가 직접 듣고 번역. 받아쓰기 실수가 적고 용어집 적용 (무료 키 필요)"
        })
        engineGroup.addView(RadioButton(this).apply {
            id = 104
            text = "제미나이 통역 (실험) — 소리를 제미나이가 바로 통역. 가장 빠름, 용어집 미적용 (무료 키 필요)"
        })
        engineGroup.check(
            when (Prefs.engine(this)) {
                Prefs.ENGINE_GEMINI -> 102
                Prefs.ENGINE_MLKIT -> 103
                Prefs.ENGINE_LIVE -> 104
                Prefs.ENGINE_HEAR -> 105
                else -> 101
            }
        )
        col.addView(engineGroup)

        geminiArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        geminiArea.addView(note("제미나이 API 키 (aistudio.google.com/apikey 에서 무료 발급)"))
        keyBox = EditText(this).apply {
            hint = "AIza로 시작하는 키 붙여넣기"
            setText(Prefs.geminiKey(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
        }
        geminiArea.addView(keyBox)
        geminiArea.addView(note("모델 이름 (기본: ${Prefs.DEFAULT_MODEL}. 더 똑똑하지만 느린 것: gemini-3.6-flash, gemini-3.8-flash)"))
        modelBox = EditText(this).apply {
            setText(Prefs.geminiModel(this@MainActivity))
            setSingleLine(true)
        }
        geminiArea.addView(modelBox)
        geminiArea.addView(note("제미나이가 실패하거나 한도를 넘으면 자동으로 구글 번역으로 바꿔서 보여줘요 (자막 옆에 ↩구글 표시)."))
        col.addView(geminiArea)
        geminiArea.visibility = if (engineGroup.checkedRadioButtonId in listOf(102, 104, 105)) View.VISIBLE else View.GONE
        engineGroup.setOnCheckedChangeListener { _, id ->
            geminiArea.visibility = if (id == 102 || id == 104 || id == 105) View.VISIBLE else View.GONE
            save()
        }
        col.addView(Button(this).apply {
            text = "번역 테스트 (제대로 번역되는지 확인)"
            setOnClickListener { runTest() }
        })

        // ── 지금 보는 작품
        col.addView(title("지금 보는 작품"))
        col.addView(note("작품을 등록하고 지금 보는 것에 체크하세요 (여러 개 가능). 작품마다 용어집이 따로 있고, " +
            "체크한 작품의 용어집만 번역에 쓰여요. 제미나이 번역과 표기 찾기에도 참고해요."))
        worksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(worksBox)
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val newWork = EditText(this).apply {
            hint = "작품 이름 (예: 림버스 컴퍼니)"
            setSingleLine(true)
        }
        addRow.addView(newWork, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addRow.addView(Button(this).apply {
            text = "추가"
            setOnClickListener {
                val n = newWork.text.toString().trim()
                if (n.isNotEmpty()) {
                    Prefs.addWork(this@MainActivity, n)
                    Prefs.setActive(this@MainActivity, n, true)
                    newWork.setText("")
                    refreshWorks()
                }
            }
        })
        col.addView(addRow)
        refreshWorks()

        // ── 용어집
        col.addView(title("공통 용어집"))
        col.addView(note("작품과 상관없이 늘 쓰는 단어. 한 줄에 하나씩 '중국어=한국어'로 적으면 그 말은 항상 이렇게 번역돼요.\n" +
            "예) 主播=스트리머   弹幕=탄막\n작품 용어집은 위 '지금 보는 작품'의 [용어집] 버튼에서 고쳐요."))
        glossaryBox = EditText(this).apply {
            setText(Prefs.glossaryText(this@MainActivity))
            hint = "原神=원신"
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        col.addView(glossaryBox)
        col.addView(check("발음이 같은데 글자만 다르게 인식되면 용어집 단어로 자동 교정", "pinyinFix", true))
        col.addView(Button(this).apply {
            text = "용어집 점검 (공통 + 체크한 작품 용어집을 웹 검색·제미나이로 확인)"
            isAllCaps = false
            setOnClickListener { save(); checkGlossary() }
        })

        // ── 음성 인식 모델
        col.addView(title("음성 인식 모델"))
        asrGroup = RadioGroup(this)
        Models.all.forEachIndexed { i, s ->
            asrGroup.addView(RadioButton(this).apply { id = 201 + i; text = s.label })
        }
        val curIdx = Models.all.indexOfFirst { it.id == Prefs.asrModel(this) }.coerceAtLeast(0)
        asrGroup.check(201 + curIdx)
        col.addView(asrGroup)
        asrStatus = note("")
        col.addView(asrStatus)
        asrDownload = Button(this).apply {
            text = "선택한 모델 내려받기 (와이파이 권장)"
            setOnClickListener { downloadModel() }
        }
        col.addView(asrDownload)
        col.addView(note("모델을 바꾸면 자막을 중지했다가 다시 시작해야 적용돼요. Fun-ASR Nano는 한국어 판별을 못 해서 '중국어만'이 덜 정확할 수 있고, 폰에 따라 느릴 수 있어요. 미리 보기(▸ 줄)는 SenseVoice에서만 돼요."))
        asrGroup.setOnCheckedChangeListener { _, id ->
            val s = Models.all[id - 201]
            p.edit().putString("asrModel", s.id).apply()
            refreshAsr()
        }
        refreshAsr()

        // ── 자막
        col.addView(title("자막"))
        col.addView(note("자막 끊는 길이 (짧을수록 빨리 뜨고, 길수록 문장이 덜 잘려요. 자막을 다시 시작해야 적용)"))
        val segGroup = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        val segKeys = listOf("short", "normal", "long")
        listOf("짧게", "보통", "길게").forEachIndexed { i, label ->
            segGroup.addView(RadioButton(this).apply { id = 301 + i; text = label })
        }
        segGroup.check(301 + segKeys.indexOf(Prefs.segLen(this)).coerceAtLeast(0))
        segGroup.setOnCheckedChangeListener { _, id -> p.edit().putString("segLen", segKeys[id - 301]).apply() }
        col.addView(segGroup)
        col.addView(check("말하는 도중에 중국어 미리 보여주기 (▸ 줄, 폰이 뜨거워지면 끄세요)", "partial", true))
        col.addView(check("미리 보기 줄도 한국어로 미리 번역 (구글 번역 사용, 확정되면 바뀜)", "partialTr", true))
        col.addView(check("중국어만 자막으로 (한국어 등 다른 말은 숨김)", "onlyChinese", true))
        col.addView(check("빌리빌리 앱 소리만 잡기 (브라우저로 볼 땐 끄세요)", "biliOnly", true))
        col.addView(check("중국어 원문도 같이 보기 (자막 창의 原 버튼으로도 전환)", "showOrig", true))
        col.addView(check("자막 창 잠금 (켜면 자막 위를 눌러도 영상이 조작되고, 이동·스크롤은 안 돼요)", "locked", false))
        val sizeLabel = note("글자 크기: ${Prefs.textSize(this)}")
        col.addView(sizeLabel)
        col.addView(SeekBar(this).apply {
            max = 18
            progress = Prefs.textSize(this@MainActivity) - 12
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    sizeLabel.text = "글자 크기: ${v + 12}"
                    p.edit().putInt("textSize", v + 12).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
        col.addView(note(
            "자막 창 사용법\n" +
                "· 맨 위 줄 아무 데나 끌면 창이 이동해요.\n" +
                "· 오른쪽 아래 ◢ 를 끌면 창 크기가 바뀌어요.\n" +
                "· 창 안을 위아래로 밀면 지난 자막을 볼 수 있어요.\n" +
                "· 原 버튼으로 원문을 켜고 끌 수 있어요.\n" +
                "· 자막 줄을 길게 누르면 번역을 고쳐서 용어집에 저장할 수 있어요.\n" +
                "· 말이 한동안 없으면 창이 흐려져요."
        ))
        col.addView(Button(this).apply {
            text = "자막 창 위치·크기 초기화"
            setOnClickListener {
                p.edit().remove("posX").remove("posY").remove("boxW").remove("boxH").apply()
                Toast.makeText(this@MainActivity, "다음에 자막을 시작할 때 적용돼요", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(ScrollView(this).apply { addView(col) })

        // 예전 버전에서 따로 받아둔 SenseVoice 2025 파일 정리 (이제 앱에 내장)
        thread { runCatching { Models.cleanupOld(applicationContext) } }
    }

    override fun onResume() {
        super.onResume()
        // 자막 창의 '번역 고치기'로 용어집이 바뀌었을 수 있으니 다시 불러옴
        val g = Prefs.glossaryText(this)
        if (glossaryBox.text.toString() != g) glossaryBox.setText(g)
        refreshWorks()
    }

    // 작품 목록 그리기: [체크] 작품 이름  [용어집 n] [자동 만들기] [삭제]
    private fun refreshWorks() {
        if (!::worksBox.isInitialized) return
        worksBox.removeAllViews()
        val active = Prefs.activeWorks(this)
        val all = Prefs.works(this)
        if (all.isEmpty()) {
            worksBox.addView(TextView(this).apply {
                text = "아직 등록한 작품이 없어요."
                setTextColor(Color.GRAY)
            })
        }
        for (w in all) {
            val n = Prefs.parse(Prefs.workGlossaryText(this, w)).size
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(CheckBox(this).apply {
                text = w
                isChecked = w in active
                setOnCheckedChangeListener { _, on -> Prefs.setActive(this@MainActivity, w, on) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            fun small(t: String, f: () -> Unit) = Button(this).apply {
                text = t
                isAllCaps = false
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding((8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt(), 0)
                setOnClickListener { f() }
            }
            row.addView(small("용어집 $n") { editWorkGlossary(w) })
            row.addView(small("자동 만들기") { buildWorkGlossary(w) })
            row.addView(small("삭제") {
                android.app.AlertDialog.Builder(this)
                    .setMessage("'$w'와 그 용어집을 지울까요?")
                    .setPositiveButton("지우기") { _, _ -> Prefs.removeWork(this, w); refreshWorks() }
                    .setNegativeButton("취소", null)
                    .show()
            })
            worksBox.addView(row)
        }
    }

    private fun editWorkGlossary(w: String) {
        val box = EditText(this).apply {
            setText(Prefs.workGlossaryText(this@MainActivity, w))
            hint = "중국어=한국어 (한 줄에 하나)"
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("$w 용어집")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                Prefs.setWorkGlossary(this, w, box.text.toString())
                refreshWorks()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // (나) 작품 용어집 자동 만들기: 웹 검색 + 제미나이로 초안 → 체크해서 추가
    private fun buildWorkGlossary(w: String) {
        save()
        if (Prefs.geminiKey(this).isEmpty()) {
            Toast.makeText(this, "제미나이 키가 필요해요 (번역기 칸에서 입력)", Toast.LENGTH_LONG).show()
            return
        }
        @Suppress("DEPRECATION")
        val progress = android.app.ProgressDialog(this).apply {
            setMessage("'$w' 용어를 찾는 중… (웹 검색 후 제미나이가 정리해요, 30초쯤 걸릴 수 있어요)")
            setCancelable(false)
            show()
        }
        thread {
            val res = runCatching { Lookup.buildWorkGlossary(applicationContext, w) }
            runOnUiThread {
                progress.dismiss()
                val list = res.getOrElse {
                    Toast.makeText(this, "실패: ${it.message}", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (list.isEmpty()) {
                    Toast.makeText(this, "찾은 용어가 없어요", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val existing = Prefs.parse(Prefs.workGlossaryText(this, w)).map { it.first }.toSet()
                val items = list.map { (k, z, note) ->
                    "$k ← $z" + (if (note.isNotEmpty()) "   ($note)" else "") + (if (z in existing) "  [이미 있음]" else "")
                }.toTypedArray()
                val checked = BooleanArray(list.size) { list[it].second !in existing }
                android.app.AlertDialog.Builder(this)
                    .setTitle("$w 용어집에 넣을 항목 (틀린 건 체크 해제)")
                    .setMultiChoiceItems(items, checked) { _, which, on -> checked[which] = on }
                    .setPositiveButton("넣기") { _, _ ->
                        var added = 0
                        list.forEachIndexed { i, (k, z, _) ->
                            if (checked[i]) { Prefs.putEntry(this, w, z, k); added++ }
                        }
                        refreshWorks()
                        Toast.makeText(this, "$added 개를 넣었어요", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun save() {
        val engine = when (engineGroup.checkedRadioButtonId) {
            102 -> Prefs.ENGINE_GEMINI
            103 -> Prefs.ENGINE_MLKIT
            104 -> Prefs.ENGINE_LIVE
            105 -> Prefs.ENGINE_HEAR
            else -> Prefs.ENGINE_GOOGLE
        }
        Prefs.get(this).edit()
            .putString("engine", engine)
            .putString("geminiKey", keyBox.text.toString().trim())
            .putString("geminiModel", modelBox.text.toString().trim())
            .putString("glossary", glossaryBox.text.toString())
            .apply()
    }

    // 용어집 점검: 공통 + 체크한 작품 용어집을 작품 기준(웹 검색 + 제미나이)으로 확인
    private fun checkGlossary() {
        if (Prefs.geminiKey(this).isEmpty()) {
            Toast.makeText(this, "제미나이 키가 필요해요 (번역기 칸에서 입력)", Toast.LENGTH_LONG).show()
            return
        }
        val stores = listOf<String?>(null) + Prefs.activeWorks(this)
        val work = stores.filter { Prefs.parse(Prefs.glossaryTextOf(this, it)).isNotEmpty() }
        if (work.isEmpty()) {
            Toast.makeText(this, "점검할 용어집이 비어 있어요", Toast.LENGTH_SHORT).show()
            return
        }
        @Suppress("DEPRECATION")
        val progress = android.app.ProgressDialog(this).apply {
            setMessage("용어집 점검 중…")
            setCancelable(false)
            show()
        }
        thread {
            // (용어집, 기존 중국어, 한국어, 제안 중국어, 근거)
            val sugg = ArrayList<Array<String?>>()
            for (st in work) {
                runOnUiThread { progress.setMessage("${st ?: "공통"} 용어집 점검 중…") }
                val entries = Prefs.parse(Prefs.glossaryTextOf(this, st))
                runCatching { Lookup.check(applicationContext, st, entries) }.getOrNull()?.forEach { (i, zh, why) ->
                    sugg.add(arrayOf(st, entries[i].first, entries[i].second, zh, why))
                }
            }
            runOnUiThread {
                progress.dismiss()
                showSuggestions(sugg)
            }
        }
    }

    private fun showSuggestions(sugg: List<Array<String?>>) {
        if (sugg.isEmpty()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("용어집 점검")
                .setMessage("바꿀 만한 항목을 못 찾았어요.")
                .setPositiveButton("확인", null)
                .show()
            return
        }
        val items = sugg.map { "[${it[0] ?: "공통"}] ${it[1]} → ${it[3]}   (${it[2]})" + (if (!it[4].isNullOrEmpty()) "\n${it[4]}" else "") }
            .toTypedArray()
        val checked = BooleanArray(sugg.size)
        android.app.AlertDialog.Builder(this)
            .setTitle("바꿀 항목을 고르세요")
            .setMultiChoiceItems(items, checked) { _, which, on -> checked[which] = on }
            .setPositiveButton("고른 항목 바꾸기") { _, _ ->
                sugg.forEachIndexed { i, sg ->
                    if (!checked[i]) return@forEachIndexed
                    val st = sg[0]
                    val lines = Prefs.glossaryTextOf(this, st).lines().toMutableList()
                    val idx = lines.indexOfFirst { it.substringBefore('=').trim() == sg[1] }
                    if (idx >= 0) {
                        lines[idx] = "${sg[3]}=${sg[2]}"
                        Prefs.setGlossaryOf(this, st, lines.joinToString("\n"))
                    }
                }
                glossaryBox.setText(Prefs.glossaryText(this))
                refreshWorks()
                Toast.makeText(this, "용어집을 고쳤어요", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("그대로 두기", null)
            .show()
    }

    private fun refreshAsr() {
        if (downloading) return
        val id = Prefs.asrModel(this)
        val ready = Models.isReady(this, id)
        asrStatus.text = if (ready) "✔ 사용 가능" else "아직 내려받지 않았어요"
        asrDownload.visibility = if (ready) View.GONE else View.VISIBLE
    }

    private fun downloadModel() {
        if (downloading) return
        val id = Prefs.asrModel(this)
        downloading = true
        asrDownload.isEnabled = false
        asrStatus.text = "내려받는 중… 화면을 끄지 말고 기다려주세요"
        thread {
            val msg = try {
                Models.download(applicationContext, id) { got, total ->
                    val pct = if (total > 0) (got * 100 / total).toInt() else 0
                    runOnUiThread {
                        asrStatus.text = "내려받는 중… $pct% (${got / 1_000_000}MB / ${total / 1_000_000}MB)"
                    }
                }
                null
            } catch (e: Exception) {
                "실패: ${e.message}"
            }
            runOnUiThread {
                downloading = false
                asrDownload.isEnabled = true
                refreshAsr()
                if (msg != null) asrStatus.text = msg
            }
        }
    }

    private fun runTest() {
        save()
        status.text = "번역 테스트 중…"
        val sample = "大家好，今天我们来聊一聊这个游戏的新版本，更新内容还挺多的。"
        thread {
            val tr = Translators(applicationContext)
            val res = try {
                tr.test(sample)
            } catch (e: Exception) {
                "오류: ${e.message}"
            } finally {
                tr.close()
            }
            runOnUiThread {
                status.text = "원문: $sample\n(뜻: 여러분 안녕하세요, 오늘은 이 게임의 새 버전에 대해 얘기해볼게요. " +
                    "업데이트 내용이 꽤 많아요.)\n\n$res"
            }
        }
    }

    private fun begin() {
        if (!Models.isReady(this, Prefs.asrModel(this))) {
            status.text = "선택한 음성 인식 모델을 먼저 내려받아 주세요 (아래 '음성 인식 모델')."
            return
        }
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), reqPerm)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "'다른 앱 위에 표시'를 허용하고 돌아와서 다시 눌러주세요", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            begin()
        } else {
            status.text = "오디오 녹음 권한이 필요해요. (마이크가 아니라 소리 캡처용이에요)"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, CaptureService::class.java)
                    .putExtra("code", resultCode)
                    .putExtra("data", data)
                startForegroundService(i)
                status.text = "시작했어요! 빌리빌리로 가서 영상을 재생하세요."
                moveTaskToBack(true)
            } else {
                status.text = "화면/소리 캡처 허용이 취소됐어요."
            }
        }
    }
}
