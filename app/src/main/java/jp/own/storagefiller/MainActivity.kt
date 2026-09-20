package jp.own.storagefiller

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class MainActivity : Activity(), FillEngine.Listener {

    private lateinit var donutChart: DonutChartView
    private lateinit var statsText: TextView
    private lateinit var uptimeText: TextView
    private lateinit var uptimeNoSleepText: TextView
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var stopButton: Button
    private lateinit var deviceInfoText: TextView
    private lateinit var permissionStatusText: TextView
    private lateinit var permissionButton: Button
    private lateinit var dummyListContainer: LinearLayout
    private lateinit var dummyHeader: TextView
    private lateinit var logText: TextView
    private lateinit var setupContainer: LinearLayout
    private lateinit var catchButton: Button
    private lateinit var catchImage: ImageView
    private lateinit var catchStatusText: TextView
    private lateinit var updateStatusText: TextView
    private lateinit var updateCheckButton: Button
    private lateinit var updateInstallButton: Button

    @Volatile
    private var catching = false

    /** 「アップデートを確認」で見つかった新バージョン。未確認・最新のときは null */
    private var pendingUpdate: UpdateChecker.Release? = null

    @Volatile
    private var updateBusy = false

    private lateinit var engine: FillEngine
    private val handler = Handler(Looper.getMainLooper())

    private var lastScan: StorageAnalyzer.Result? = null

    @Volatile
    private var scanning = false

    private data class Chip(val view: TextView, val dir: String, val color: Int, var selected: Boolean)

    private lateinit var chips: List<Chip>

    private val pieColors by lazy {
        mapOf(
            StorageAnalyzer.CAT_PHOTO to color(R.color.cat_photo),
            StorageAnalyzer.CAT_VIDEO to color(R.color.cat_video),
            StorageAnalyzer.CAT_MUSIC to color(R.color.cat_music),
            StorageAnalyzer.CAT_DL to color(R.color.cat_dl),
            StorageAnalyzer.CAT_APP to color(R.color.cat_app),
            StorageAnalyzer.CAT_OTHER to color(R.color.cat_other)
        )
    }
    private val freeColor by lazy { color(R.color.cat_free) }
    private val systemColor by lazy { color(R.color.cat_system) }
    private val dummyColor by lazy { color(R.color.cat_dummy) }

    private fun color(id: Int): Int = resources.getColor(id, null)

    /** ドットフォント。assets 同梱の PixelMplus 12（M+ FONT LICENSE） */
    private val pixelFont by lazy { Typeface.createFromAsset(assets, "fonts/PixelMplus12-Regular.ttf") }
    private val pixelFontBold by lazy { Typeface.createFromAsset(assets, "fonts/PixelMplus12-Bold.ttf") }

    /** 書込中の円グラフ連動用。liveWritten が 0 以上のときだけ書込中とみなす */
    private var fillBaseFree = 0L
    private var fillBaseDummy = 0L
    private var liveWritten = -1L

    private val uptimeTicker = object : Runnable {
        override fun run() {
            uptimeText.text = "稼働: ${DeviceInfo.formatDuration(SystemClock.elapsedRealtime())}"
            uptimeNoSleepText.text =
                "稼働(スリープ除外): ${DeviceInfo.formatDuration(SystemClock.uptimeMillis())}"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        donutChart = findViewById(R.id.donutChart)
        statsText = findViewById(R.id.statsText)
        uptimeText = findViewById(R.id.uptimeText)
        uptimeNoSleepText = findViewById(R.id.uptimeNoSleepText)
        statusText = findViewById(R.id.statusText)
        progressText = findViewById(R.id.progressText)
        stopButton = findViewById(R.id.stopButton)
        deviceInfoText = findViewById(R.id.deviceInfoText)
        permissionStatusText = findViewById(R.id.permissionStatusText)
        permissionButton = findViewById(R.id.permissionButton)
        dummyListContainer = findViewById(R.id.dummyListContainer)
        dummyHeader = findViewById(R.id.dummyHeader)
        logText = findViewById(R.id.logText)
        setupContainer = findViewById(R.id.setupContainer)
        catchButton = findViewById(R.id.catchButton)
        catchImage = findViewById(R.id.catchImage)
        catchStatusText = findViewById(R.id.catchStatusText)
        updateStatusText = findViewById(R.id.updateStatusText)
        updateCheckButton = findViewById(R.id.updateCheckButton)
        updateInstallButton = findViewById(R.id.updateInstallButton)

        engine = FillEngine(this, this)

        buildSetupList()
        applyPixelFont(findViewById(android.R.id.content))
        donutChart.setTypeface(pixelFontBold)

        setupChips()
        setupPresetButtons()

        stopButton.setOnClickListener {
            engine.cancel()
            appendLog("停止要求を送信")
        }
        dummyHeader.setOnClickListener {
            dummyListContainer.visibility =
                if (dummyListContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            refreshDummyList()
        }
        findViewById<Button>(R.id.rescanButton).setOnClickListener { startScan(force = true) }
        findViewById<Button>(R.id.deleteAllButton).setOnClickListener { deleteAll() }
        permissionButton.setOnClickListener { requestStoragePermission() }
        catchButton.setOnClickListener { catchPokemon() }
        updateCheckButton.setOnClickListener { checkForUpdate() }
        updateInstallButton.setOnClickListener { downloadAndInstall() }
        updateStatusText.text = "現在のバージョン: ${BuildConfig.VERSION_NAME}"

        updateDeviceInfo()
        updatePermissionStatus()
        refreshDummyList()
        updateStats()
        if (hasStoragePermission()) startScan()
    }

    override fun onResume() {
        super.onResume()
        handler.post(uptimeTicker)
        updateDeviceInfo()
        updatePermissionStatus()
    }

    override fun onPause() {
        handler.removeCallbacks(uptimeTicker)
        super.onPause()
    }

    /** ビューツリーを辿って全テキストにドットフォントを当てる。太字指定は太字フォントに差し替える */
    private fun applyPixelFont(v: View) {
        when (v) {
            is ViewGroup -> for (i in 0 until v.childCount) applyPixelFont(v.getChildAt(i))
            is TextView -> v.typeface = if (v.typeface?.isBold == true) pixelFontBold else pixelFont
        }
    }

    // ---- カテゴリチップ ----

    private fun setupChips() {
        chips = listOf(
            Chip(findViewById(R.id.chipApps), "Apps", color(R.color.cat_app), true),
            Chip(findViewById(R.id.chipDownload), "Download", color(R.color.cat_dl), true),
            Chip(findViewById(R.id.chipPictures), "Pictures", color(R.color.cat_photo), true),
            Chip(findViewById(R.id.chipMovies), "Movies", color(R.color.cat_video), true),
            Chip(findViewById(R.id.chipMusic), "Music", color(R.color.cat_music), true)
        )
        for (chip in chips) {
            chip.view.setOnClickListener {
                chip.selected = !chip.selected
                renderChip(chip)
            }
            renderChip(chip)
        }
    }

    private fun renderChip(chip: Chip) {
        // ドットUI: 角丸なし・黒枠。選択中はカテゴリ色で塗り、未選択は白地
        val density = resources.displayMetrics.density
        chip.view.background = GradientDrawable().apply {
            setStroke((2f * density).toInt(), color(R.color.pixel_ink))
            setColor(if (chip.selected) chip.color else color(R.color.pixel_bg))
        }
        chip.view.setTextColor(
            if (chip.selected) Color.WHITE else color(R.color.pixel_shadow)
        )
        chip.view.typeface = pixelFontBold
    }

    private fun selectedCategories(): List<String> =
        chips.filter { it.selected }.map { it.dir }

    // ---- プリセットボタン ----

    private fun setupPresetButtons() {
        findViewById<Button>(R.id.pct30).setOnClickListener { startFill(ratioToBytes(0.30)) }
        findViewById<Button>(R.id.pct60).setOnClickListener { startFill(ratioToBytes(0.60)) }
        findViewById<Button>(R.id.pct70).setOnClickListener { startFill(ratioToBytes(0.70)) }
        findViewById<Button>(R.id.size100mb).setOnClickListener { startFill(100L shl 20) }
        findViewById<Button>(R.id.size1gb).setOnClickListener { startFill(1L shl 30) }
        findViewById<Button>(R.id.size5gb).setOnClickListener { startFill(5L shl 30) }
        findViewById<Button>(R.id.size10gb).setOnClickListener { startFill(10L shl 30) }
        findViewById<Button>(R.id.size30gb).setOnClickListener { startFill(30L shl 30) }
        findViewById<Button>(R.id.size50gb).setOnClickListener { startFill(50L shl 30) }
    }

    private fun ratioToBytes(ratio: Double): Long {
        val (free, _) = DeviceInfo.storage()
        return (free * ratio).toLong()
    }

    // ---- 埋め込み ----

    private fun startFill(requested: Long) {
        if (engine.isRunning) return
        if (!hasStoragePermission()) {
            appendLog("ストレージ権限がありません。先に許可してください")
            requestStoragePermission()
            return
        }
        val categories = selectedCategories()
        if (categories.isEmpty()) {
            appendLog("保存先フォルダを1つ以上選択してください")
            return
        }
        val (free, _) = DeviceInfo.storage()
        val target = requested.coerceAtMost(free)
        if (target <= 0) {
            appendLog("埋め対象容量が 0 です")
            return
        }

        appendLog(
            "埋め開始: 目標 ${DeviceInfo.formatBytes(target)} " +
                "(${categories.size}カテゴリ均等 / 1ファイル ${DeviceInfo.formatBytes(FillEngine.unitSize(target))})"
        )
        // 書込中は StatFs や FillLog を読まずに、この基準値＋書込済みバイト数で円グラフを動かす
        fillBaseFree = free
        fillBaseDummy = dummyBytes()
        liveWritten = 0L
        setWritingUi(true)
        engine.start(target, categories)
    }

    private fun setWritingUi(writing: Boolean) {
        if (writing) {
            statusText.text = "書き込み中: 0%"
            stopButton.isEnabled = true
            stopButton.setTextColor(Color.WHITE)
            stopButton.setBackgroundResource(R.drawable.btn_pixel_active)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            stopButton.isEnabled = false
            stopButton.setTextColor(color(R.color.pixel_shadow))
            stopButton.setBackgroundResource(R.drawable.btn_pixel)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---- ストレージ計測 ----

    private fun startScan(force: Boolean = false) {
        if (scanning || !hasStoragePermission()) return
        if (!force && lastScan != null) return
        scanning = true
        statusText.text = "計測中..."
        Thread {
            val result = StorageAnalyzer.scan(engine.baseDir()) { scanned ->
                runOnUiThread { statusText.text = "計測中... ${scanned}ファイル" }
            }
            scanning = false
            lastScan = result
            runOnUiThread {
                if (!engine.isRunning) statusText.text = "準備完了"
                updateStats()
            }
        }.start()
    }

    private fun dummyBytes(): Long {
        var sum = 0L
        for (p in FillLog(this).paths()) {
            val f = File(p)
            if (f.exists()) sum += f.length()
        }
        return sum
    }

    private fun updateStats() {
        val scan = lastScan
        val writing = liveWritten >= 0
        val (freeNow, totalNow) = DeviceInfo.storage()
        val total = scan?.totalBytes ?: totalNow
        // 書込中はディスクを読まず、埋め開始時点の値から書込済みバイト数を差し引いて推定する
        val free =
            if (writing) (fillBaseFree - liveWritten).coerceAtLeast(0)
            else scan?.freeBytes ?: freeNow
        val dummy = if (writing) fillBaseDummy + liveWritten else dummyBytes()
        val used = total - free
        val real = (used - dummy).coerceAtLeast(0)

        statsText.text = buildString {
            appendLine("全体: ${DeviceInfo.formatBytes(total)}")
            appendLine("実データ: ${DeviceInfo.formatBytes(real)}")
            appendLine("ダミー: ${DeviceInfo.formatBytes(dummy)}")
            append("空き: ${DeviceInfo.formatBytes(free)}")
        }

        // 描画順は「既存データ → ダミー → 空き」。
        // ダミーが使用済みと空きの境目に来るので、埋めるほど境界が動いて割合を読み取りやすい
        val segments = mutableListOf<DonutChartView.Segment>()
        if (scan != null) {
            var scanned = 0L
            for (cat in StorageAnalyzer.CATEGORIES) {
                val bytes = scan.categoryBytes[cat] ?: 0L
                scanned += bytes
                if (bytes > 0) {
                    segments.add(DonutChartView.Segment(cat, bytes, pieColors[cat] ?: Color.GRAY))
                }
            }
            // Android 11+ は Android/data・アプリ内部領域を走査できない。
            // 使用量との差をシステム領域として足し、リング合計を全体容量に揃える
            val system = (used - scanned - dummy).coerceAtLeast(0)
            if (system > 0) {
                segments.add(DonutChartView.Segment("システム", system, systemColor))
            }
        } else if (real > 0) {
            segments.add(DonutChartView.Segment("使用", real, systemColor))
        }
        if (dummy > 0) {
            segments.add(DonutChartView.Segment("ダミー", dummy, dummyColor))
        }
        segments.add(DonutChartView.Segment("空き", free, freeColor))
        val usedPct = if (total > 0) ((used * 100) / total).toInt() else 0
        donutChart.setData(segments, usedPct)
    }

    // ---- ダミーデータ一覧・削除 ----

    private fun categoryLabel(dirName: String): String = when (dirName) {
        "Apps" -> "App"
        "Download" -> "DL"
        "Pictures" -> "写真"
        "Movies" -> "動画"
        "Music" -> "音楽"
        else -> dirName
    }

    /** ヘッダに開閉マークと件数・合計を出す。一覧が縦に伸びすぎるのでデフォルトは閉じた状態 */
    private fun updateDummyHeader(count: Int, totalBytes: Long) {
        val mark = if (dummyListContainer.visibility == View.VISIBLE) "▼" else "▶"
        dummyHeader.text =
            if (count == 0) "$mark 作成済みのダミーデータ（削除用）"
            else "$mark 作成済みのダミーデータ  ${count}件 / ${DeviceInfo.formatBytes(totalBytes)}"
    }

    private fun refreshDummyList() {
        dummyListContainer.removeAllViews()
        val paths = FillLog(this).paths()
        updateDummyHeader(paths.size, dummyBytes())
        if (paths.isEmpty()) {
            val empty = TextView(this).apply {
                text = "作成済みデータはありません"
                setTextColor(color(R.color.pixel_shadow))
                textSize = 12f
                typeface = pixelFont
                setPadding(8, 8, 8, 8)
            }
            dummyListContainer.addView(empty)
            return
        }
        for (path in paths) {
            val f = File(path)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 4, 8, 4)
            }
            val label = TextView(this).apply {
                text = "[${categoryLabel(f.parentFile?.name ?: "")}] ${f.name}\n" +
                    DeviceInfo.formatBytes(if (f.exists()) f.length() else 0)
                setTextColor(color(R.color.pixel_ink))
                textSize = 12f
                typeface = pixelFont
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val delete = TextView(this).apply {
                text = "削除"
                setTextColor(color(R.color.pixel_accent))
                textSize = 14f
                typeface = pixelFontBold
                setPadding(24, 8, 24, 8)
                setOnClickListener { deleteOne(path) }
            }
            row.addView(label)
            row.addView(delete)
            dummyListContainer.addView(row)
        }
    }

    private fun deleteOne(path: String) {
        if (engine.isRunning) return
        Thread {
            val f = File(path)
            val size = if (f.exists()) f.length() else 0L
            val deleted = f.delete()
            FillLog(this).remove(path)
            runOnUiThread {
                appendLog(
                    if (deleted) "削除: ${f.name} (${DeviceInfo.formatBytes(size)})"
                    else "削除対象なし: ${f.name}"
                )
                refreshDummyList()
                updateStats()
            }
        }.start()
    }

    private fun deleteAll() {
        if (engine.isRunning) {
            appendLog("埋め込み実行中は削除できません")
            return
        }
        Thread {
            val log = FillLog(this)
            val paths = log.paths()
            var freed = 0L
            var deleted = 0
            for (path in paths) {
                val f = File(path)
                if (f.exists()) {
                    freed += f.length()
                    if (f.delete()) deleted++
                }
            }
            val base = engine.baseDir()
            for (category in FillEngine.CATEGORIES) File(base, category).delete()
            base.delete()
            log.clear()
            runOnUiThread {
                appendLog("全削除完了: ${deleted}件 / 解放 ${DeviceInfo.formatBytes(freed)}")
                refreshDummyList()
                updateStats()
                startScan(force = true)
            }
        }.start()
    }

    // ---- 端末情報・権限 ----

    private fun updateDeviceInfo() {
        deviceInfoText.text = buildString {
            appendLine("モデル: ${DeviceInfo.model()}")
            appendLine("OS: ${DeviceInfo.androidVersion()}")
            appendLine("CPU: ${DeviceInfo.cpu()}")
            append("RAM: ${DeviceInfo.formatBytes(DeviceInfo.ramTotal(this@MainActivity))}")
        }
    }

    private fun hasStoragePermission(): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT == 29 -> true // アプリ専用外部領域を使うため不要
        else -> checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun updatePermissionStatus() {
        val granted = hasStoragePermission()
        permissionStatusText.text = if (granted) {
            "ストレージ権限: 許可済み"
        } else {
            "ストレージ権限: 未許可（許可ボタンを押してください）"
        }
        permissionButton.isEnabled = !granted
        permissionButton.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun requestStoragePermission() {
        when {
            Build.VERSION.SDK_INT >= 30 -> {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
            Build.VERSION.SDK_INT == 29 -> updatePermissionStatus()
            else -> requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionStatus()
        if (hasStoragePermission()) startScan()
    }

    // ---- 捕まえる（アイコン画像のランダム保存） ----

    private fun catchPokemon() {
        if (catching) return
        catching = true
        catchButton.isEnabled = false
        catchStatusText.text = "捕まえています..."
        Thread {
            val caught = PokemonCatcher.catchOne(this)
            runOnUiThread {
                catching = false
                catchButton.isEnabled = true
                if (caught == null) {
                    catchStatusText.text = "逃げられました（通信か保存に失敗）"
                    appendLog("捕まえる: 失敗")
                } else {
                    catchImage.setImageBitmap(caught.image)
                    catchImage.visibility = View.VISIBLE
                    catchStatusText.text =
                        "No.${caught.number} を捕まえた！" +
                            "\n保存先: ${caught.savedTo}"
                    appendLog("捕まえる: No.${caught.number} -> ${caught.savedTo}")
                }
            }
        }.start()
    }

    // ---- セットアップ（Playへの導線） ----

    private fun buildSetupList() {
        setupContainer.removeAllViews()
        val gap = (4f * resources.displayMetrics.density).toInt()
        for ((index, app) in SetupApps.LIST.withIndex()) {
            val button = Button(this).apply {
                text = app.label
                textSize = 14f
                // Button は既定で英字を大文字化するので切る（「Google レンズ」が GOOGLE レンズ になる）
                isAllCaps = false
                setTextColor(color(R.color.pixel_ink))
                setBackgroundResource(R.drawable.btn_pixel)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (index > 0) topMargin = gap }
                setOnClickListener { openPlayPage(app) }
            }
            setupContainer.addView(button)
        }
    }

    /** Play アプリでページを開く。Play が無い端末ではブラウザにフォールバックする */
    private fun openPlayPage(app: SetupApps.Entry) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${app.pkg}"))
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=${app.pkg}")
        )
        try {
            startActivity(market)
        } catch (_: Exception) {
            try {
                startActivity(web)
            } catch (e: Exception) {
                appendLog("Playを開けませんでした: ${app.label} (${e.message})")
            }
        }
    }

    // ---- アップデート ----

    private fun checkForUpdate() {
        if (updateBusy) return
        updateBusy = true
        updateCheckButton.isEnabled = false
        updateInstallButton.visibility = View.GONE
        pendingUpdate = null
        updateStatusText.text = "確認中..."
        Thread {
            val latest = UpdateChecker.fetchLatest(BuildConfig.UPDATE_REPO)
            runOnUiThread {
                updateBusy = false
                updateCheckButton.isEnabled = true
                when {
                    latest == null ->
                        updateStatusText.text =
                            "確認できませんでした（通信に失敗したか、リリースがありません）"

                    UpdateChecker.isNewer(latest.version, BuildConfig.VERSION_NAME) -> {
                        pendingUpdate = latest
                        updateStatusText.text =
                            "新しいバージョン v${latest.version} があります\n" +
                                "現在: v${BuildConfig.VERSION_NAME} / " +
                                DeviceInfo.formatBytes(latest.sizeBytes)
                        updateInstallButton.visibility = View.VISIBLE
                    }

                    else ->
                        updateStatusText.text =
                            "最新です（v${BuildConfig.VERSION_NAME}）"
                }
            }
        }.start()
    }

    private fun downloadAndInstall() {
        val release = pendingUpdate ?: return
        if (updateBusy) return

        // API 26+ は「不明なアプリのインストール」をこのアプリに許可してもらう必要がある
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            updateStatusText.text = "設定で「不明なアプリのインストール」を許可してください"
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:$packageName"))
            )
            return
        }

        updateBusy = true
        updateInstallButton.isEnabled = false
        updateCheckButton.isEnabled = false
        Thread {
            var lastShown = 0L
            val file = UpdateChecker.download(this, release) { done, total ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastShown >= 100L || done >= total) {
                    lastShown = now
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    runOnUiThread {
                        updateStatusText.text = "ダウンロード中: $pct%  " +
                            "${DeviceInfo.formatBytes(done)} / ${DeviceInfo.formatBytes(total)}"
                    }
                }
            }
            runOnUiThread {
                updateBusy = false
                updateInstallButton.isEnabled = true
                updateCheckButton.isEnabled = true
                if (file == null) {
                    updateStatusText.text = "ダウンロードに失敗しました"
                    appendLog("アップデート: ダウンロード失敗")
                } else {
                    updateStatusText.text = "インストーラーを起動しました"
                    appendLog("アップデート: v${release.version} をダウンロード完了")
                    launchInstaller(file)
                }
            }
        }.start()
    }

    private fun launchInstaller(file: File) {
        val uri = ApkProvider.uriFor(this, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            updateStatusText.text = "インストーラーを起動できませんでした"
            appendLog("アップデート: インストーラー起動失敗 (${e.message})")
        }
    }

    // ---- FillEngine.Listener（別スレッドから呼ばれる） ----

    override fun onProgress(writtenBytes: Long, targetBytes: Long, speedMBps: Double, fileCount: Int) {
        runOnUiThread {
            val pct = if (targetBytes > 0) (writtenBytes * 100 / targetBytes).toInt() else 0
            statusText.text = "書き込み中: $pct%"
            progressText.text = "%s / %s  %.1f MB/s  %dファイル".format(
                DeviceInfo.formatBytes(writtenBytes),
                DeviceInfo.formatBytes(targetBytes),
                speedMBps, fileCount
            )
            // 円グラフも書込に連動させる。FillEngine 側で 100ms に間引かれている
            liveWritten = writtenBytes
            updateStats()
        }
    }

    override fun onLog(message: String) = appendLog(message)

    override fun onFinished(writtenBytes: Long, fileCount: Int, cancelled: Boolean) {
        runOnUiThread {
            liveWritten = -1L
            val state = if (cancelled) "停止" else "完了"
            statusText.text = "準備完了"
            progressText.text = ""
            appendLog("埋め$state: ${DeviceInfo.formatBytes(writtenBytes)} / $fileCount ファイル")
            setWritingUi(false)
            updateDeviceInfo()
            refreshDummyList()
            updateStats()
            startScan(force = true)
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            liveWritten = -1L
            statusText.text = "準備完了"
            progressText.text = ""
            appendLog("エラー: $message")
            setWritingUi(false)
            refreshDummyList()
            updateStats()
        }
    }

    private fun appendLog(message: String) {
        runOnUiThread { logText.append("$message\n") }
    }
}
