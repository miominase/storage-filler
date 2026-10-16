package jp.own.storagefiller

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * チャレンジ記録パネルの制御。MainActivity を太らせないため、この画面の状態はここに閉じる。
 *
 * 表示するのは紐づけ端末・寝かせ時間・使用サービス・メモ・記録ボタンだけ。
 * 履歴や集計は既存の管理Webアプリに残す。
 */
class ChallengePanel(
    private val activity: Activity,
    root: View,
    private val pixelFont: Typeface,
    private val pixelFontBold: Typeface,
    /** F16: Google アカウントの選択画面を開き、選ばれたメールアドレスを渡してもらう（キャンセル時は呼ばれない）。 */
    private val chooseAccount: (onChosen: (String) -> Unit) -> Unit
) {
    private val config = ChallengeConfig(activity)
    private val pending = PendingRecords(activity)

    private val boundDeviceText: TextView = root.findViewById(R.id.boundDeviceText)
    private val accountLeftText: TextView = root.findViewById(R.id.accountLeftText)
    private val setupBox: View = root.findViewById(R.id.setupBox)
    private val urlInput: EditText = root.findViewById(R.id.urlInput)
    private val passwordInput: EditText = root.findViewById(R.id.passwordInput)
    private val deviceIdInput: EditText = root.findViewById(R.id.deviceIdInput)
    private val scanQrButton: Button = root.findViewById(R.id.scanQrButton)
    private val clipboardImportButton: Button = root.findViewById(R.id.clipboardImportButton)
    private val bindButton: Button = root.findViewById(R.id.bindButton)
    private val setupStatusText: TextView = root.findViewById(R.id.setupStatusText)

    private val recordBox: View = root.findViewById(R.id.recordBox)
    private val hoursInput: EditText = root.findViewById(R.id.hoursInput)
    private val serviceLine: TextView = root.findViewById(R.id.serviceLine)
    private val serviceGoogle: TextView = root.findViewById(R.id.serviceGoogle)
    private val memoInput: EditText = root.findViewById(R.id.memoInput)
    private val recordSuccessButton: Button = root.findViewById(R.id.recordSuccessButton)
    private val recordFailureButton: Button = root.findViewById(R.id.recordFailureButton)
    private val recordStatusText: TextView = root.findViewById(R.id.recordStatusText)
    private val unbindButton: Button = root.findViewById(R.id.unbindButton)

    private val pendingHeader: TextView = root.findViewById(R.id.pendingHeader)
    private val pendingContainer: LinearLayout = root.findViewById(R.id.pendingContainer)

    private var service: String = ""

    /** QR読み取り画面を開いている間 true。二度押しで読み取り画面を重ねないため。 */
    private var scanning = false

    init {
        listOf(scanQrButton, clipboardImportButton, bindButton, recordSuccessButton, recordFailureButton, unbindButton)
            .forEach { it.isAllCaps = false }
        serviceLine.setOnClickListener { selectService("LINE") }
        serviceGoogle.setOnClickListener { selectService("Google") }
        scanQrButton.setOnClickListener { scanQr() }
        clipboardImportButton.setOnClickListener { importFromClipboard() }
        bindButton.setOnClickListener { bind() }
        recordSuccessButton.setOnClickListener { record(ChallengeRecord.RESULT_SUCCESS) }
        recordFailureButton.setOnClickListener { record(ChallengeRecord.RESULT_FAILURE) }
        unbindButton.setOnClickListener { confirmUnbind() }
        selectService("")
    }

    /** パネルを表示したときに呼ぶ。寝かせ時間を入れ直し、未送信があれば再送する。 */
    fun onShown() {
        render()
        if (config.isBound()) {
            fillHoursFromUptime()
            refreshAccountLeft()
            resendPending()
        }
    }

    private fun render() {
        val bound = config.isBound()
        setupBox.visibility = if (bound) View.GONE else View.VISIBLE
        recordBox.visibility = if (bound) View.VISIBLE else View.GONE
        boundDeviceText.text =
            if (bound) "${config.deviceName}（ID: ${config.deviceId}）" else "まだ紐づけされていません"
        accountLeftText.visibility = if (bound) View.VISIBLE else View.GONE
        if (bound && config.accountId.isEmpty()) showAccountLeft(null, null)
        renderPending()
    }

    // ---- アカウントの残り回数（F16） ----

    private fun showAccountLeft(lineLeft: Int?, googleLeft: Int?) {
        accountLeftText.text = AccountUsage.leftLabel(config.accountId, lineLeft, googleLeft)
    }

    /** 応答が今の結び付けアカウントのものなら残りを更新する。他アカウントの記録の再送では触らない。 */
    private fun showAccountLeftFrom(response: ChallengeApi.Result) {
        if (response.accountId.isNotEmpty() && response.accountId == config.accountId) {
            showAccountLeft(response.lineLeft, response.googleLeft)
        }
    }

    /**
     * 画面を開いたときに残りを問い合わせる。読むだけなので送信中フラグは取らない。
     * 通信の失敗は ChallengeApi が Result にして返す（例外は出ない）。
     */
    private fun refreshAccountLeft() {
        val accountId = config.accountId
        if (accountId.isEmpty()) return
        val url = config.apiUrl
        val password = config.password
        showAccountLeft(null, null)
        Thread {
            val r = ChallengeApi.account(url, password, accountId)
            activity.runOnUiThread {
                // 問い合わせ中に紐づけし直していたら古い結果で上書きしない
                if (config.accountId == accountId) showAccountLeft(r.lineLeft, r.googleLeft)
            }
        }.start()
    }

    /** [email] のアカウントID。未選択・IDにできない名前なら [status] に案内を出して null。 */
    private fun accountIdOrWarn(email: String?, status: TextView): String? {
        if (email == null) {
            status.text = "先に Google アカウントを選んでください"
            return null
        }
        return GoogleAccounts.accountIdOf(email).also {
            if (it == null) status.text = "このGoogleアカウントはアカウントIDにできません。別のアカウントを選んでください"
        }
    }

    /**
     * アカウント選択画面を開き、選ばれたアカウントで保存済みの URL・パスワード・端末IDのまま紐づけし直す。
     * 成功したら新しいアカウントIDで [then] を呼ぶ（送信中フラグは戻してあるので、そこから送信を始めてよい）。
     */
    private fun rebindWithChosenAccount(then: (String) -> Unit) {
        chooseAccount { email ->
            if (!config.isBound()) {
                recordStatusText.text = "紐づけが解除されたため中止しました"
                return@chooseAccount
            }
            val accountId = accountIdOrWarn(email, recordStatusText) ?: return@chooseAccount
            if (!inFlight.compareAndSet(false, true)) {
                recordStatusText.text = BUSY_MESSAGE
                return@chooseAccount
            }
            val url = config.apiUrl
            val password = config.password
            val deviceId = config.deviceId
            recordStatusText.text = "紐づけし直しています..."
            runInBackground(onError = {
                recordStatusText.text = "紐づけし直す途中でエラーが起きました。もう一度お試しください"
            }) {
                val result = ChallengeApi.verify(url, password, deviceId, accountId)
                val ui: () -> Unit = {
                    if (result.ok) {
                        config.save(url, password, deviceId, result.deviceName, accountId)
                        render()
                        // 応答に account が無ければ「確認できません」を出す（古い表示を残さない）
                        showAccountLeft(result.lineLeft, result.googleLeft)
                        then(accountId)
                    } else {
                        recordStatusText.text = describe(result)
                    }
                }
                ui
            }
        }
    }

    /** 使い切りの応答が来た記録の扱いを選んでもらう（F16）。記録は要確認として未送信に残っている。 */
    private fun showUsedUpDialog(record: ChallengeRecord) {
        AlertDialog.Builder(activity)
            .setMessage(AccountUsage.usedUpMessage(record.accountId, record.service))
            .setPositiveButton("切り替えて紐づけ直す") { _, _ -> switchAccountAndResend(record) }
            .setNeutralButton("このまま送る") { _, _ -> confirmOverUse(record) }
            .setNegativeButton("あとで", null)
            .show()
    }

    private fun switchAccountAndResend(record: ChallengeRecord) {
        rebindWithChosenAccount { newId -> replaceAndResend(AccountUsage.switchedTo(record, newId)) }
    }

    private fun confirmOverUse(record: ChallengeRecord) {
        AlertDialog.Builder(activity)
            .setMessage("使い切ったアカウントのまま送りますか？\n記録は残り、チェックは増えません（「使い切り後に使用」として記録されます）。")
            .setPositiveButton("このまま送る") { _, _ -> replaceAndResend(record.copy(overUse = true)) }
            .setNegativeButton("やめる", null)
            .show()
    }

    /** 未送信の記録を同じIDのまま差し替えて送り直す。差し替えは送信中フラグを取った中で行う。 */
    private fun replaceAndResend(record: ChallengeRecord) {
        var message: String? = BUSY_MESSAGE
        Exclusive.tryRun(inFlight) {
            message = try {
                pending.replace(record)
                null
            } catch (_: IOException) {
                QUEUE_UPDATE_FAILED
            }
        }
        if (message != null) {
            recordStatusText.text = message
            return
        }
        renderPending()
        resendOne(record)
    }

    /**
     * 連続稼働時間をそのまま寝かせ時間の初期値にする。手で直せる。
     * 小数点は端末の言語設定に関係なく '.'（ChallengeInput.formatHours）。
     */
    private fun fillHoursFromUptime() {
        if (hoursInput.text.isNotEmpty()) return
        val hours = SystemClock.elapsedRealtime() / 3600000.0
        hoursInput.setText(ChallengeInput.formatHours(hours))
    }

    /** 記録を未送信に入れた後の入力欄の初期化。二度押しで別IDの重複記録を作らせないため。 */
    private fun resetForm() {
        memoInput.setText("")
        hoursInput.setText("")
        selectService("")
        fillHoursFromUptime()
    }

    private fun selectService(value: String) {
        service = value
        renderServiceChip(serviceLine, value == "LINE")
        renderServiceChip(serviceGoogle, value == "Google")
    }

    private fun renderServiceChip(view: TextView, selected: Boolean) {
        val density = activity.resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            setStroke((2f * density).toInt(), activity.resources.getColor(R.color.pixel_ink, null))
            setColor(
                if (selected) activity.resources.getColor(R.color.pixel_accent, null)
                else activity.resources.getColor(R.color.pixel_bg, null)
            )
        }
        view.setTextColor(
            if (selected) Color.WHITE else activity.resources.getColor(R.color.pixel_shadow, null)
        )
        view.typeface = pixelFontBold
    }

    // ---- 初回設定 ----

    /**
     * QRコード経由（storagefiller://setup?url=...&password=...）で読み取った値を
     * URL・パスワード欄に入れる。呼び出し側（MainActivity）でインテントは読み取り後すぐに
     * 消費済みにしているので、ここでは受け取った値をそのまま欄に反映するだけでよい。
     *
     * 送信はしない。端末IDは端末ごとに違うためここでは埋めず、ユーザーに入力してもらう。
     *
     * 記録APIとして正しくないURL（https://script.google.com/macros/s/... 以外）が来たら何も入れない。
     * URLだけのリンクではパスワード欄を空にする。入力途中の本物のパスワードを残したまま
     * 細工したリンクでURLだけ差し替えられ、紐づけを押すとパスワードが他所へ送られるのを防ぐ。
     *
     * @return 欄に何か入れたら true
     */
    fun prefillFromUri(url: String?, password: String?): Boolean {
        // 紐づけ済みならこの欄自体が非表示。紐づけを変えたい場合は先に解除してもらう。
        if (config.isBound()) return false
        return applyPlan(SetupFill.plan(url, password))
    }

    /** [prefillFromUri] の本体。判断済みの [plan] を欄へ反映し、何か入れたら true。 */
    private fun applyPlan(plan: SetupFill.Plan): Boolean {
        return when (plan) {
            SetupFill.Plan.Empty -> false
            SetupFill.Plan.InvalidUrl -> {
                setupStatusText.text = INVALID_URL_MESSAGE
                false
            }
            is SetupFill.Plan.Fill -> {
                plan.url?.let { urlInput.setText(it) }
                plan.password?.let { passwordInput.setText(it) }
                setupStatusText.text = "QRから読み込みました。端末IDを入れてください"
                true
            }
        }
    }

    /** 設定用テキストの入手経路。見つからなかったときの案内だけが違う。 */
    private enum class SetupSource(val emptyMessage: String) {
        CLIPBOARD("クリップボードに読み込める内容がありませんでした"),
        SCAN("設定用のQRではありませんでした")
    }

    /**
     * QRの中身（クリップボードの文字列、またはアプリ内で読み取った文字列）を解析して欄に入れる。
     * 判定・抽出は `handleIncomingIntent` と共通の `SetupLink.parse()` を使い、欄へ入れる処理は
     * `prefillFromUri` をそのまま再利用する（紐づけ済みなら何もしない・端末IDは触らない・
     * 自動送信しない・不正なURLは入れない、という規則もそちらに揃う）。
     *
     * [text] はパスワードそのものを含むので、ステータス表示にもログにも一切出さない。
     *
     * @return 取り込みの判断。紐づけ済み・設定用の内容が無いときは [SetupFill.Plan.Empty]
     */
    private fun applySetupText(text: String?, source: SetupSource): SetupFill.Plan {
        if (config.isBound()) return SetupFill.Plan.Empty
        val parsed = text?.let { SetupLink.parse(it) }
        val plan = SetupFill.plan(parsed?.first, parsed?.second)
        if (plan == SetupFill.Plan.Empty) {
            setupStatusText.text = source.emptyMessage
        } else {
            applyPlan(plan)
        }
        return plan
    }

    /**
     * クリップボードのテキストをQRの内容として読み込む。Google Play 開発者サービスがなく
     * 「QRを読み取る」が使えない端末向けの予備の入口（標準カメラで読んで「コピー」してもらう）。
     * 設定用の内容だったらクリップボードを空にする（キーボードのクリップボード履歴に残さないため）。
     * 不正なURLで欄に入れなかったときも、パスワードが含まれうるので消す。
     */
    private fun importFromClipboard() {
        if (config.isBound()) return
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(activity)?.toString()
        } else {
            null
        }
        // パスワード入りの内容は、不正なURLで欄に入れなかったときも消す
        if (SetupFill.shouldClearClipboard(applySetupText(text, SetupSource.CLIPBOARD))) clearClipboard(clipboard)
    }

    /**
     * アプリ内でQRを読み取る（Google Code Scanner）。読み取り画面は Google Play 開発者サービス側が
     * 出すので、このアプリにカメラ権限は要らない。読めた文字列はクリップボードを通さず
     * そのまま `applySetupText` へ渡す（コピーしないので消す必要もない）。
     *
     * 紐づけ済み・送信中は何もしない。キャンセルされたら何も変えない。
     * スキャン画面のモジュールが未取得なら取得を依頼し、もう一度押してもらう。
     */
    private fun scanQr() {
        if (config.isBound()) return
        if (inFlight.get()) {
            setupStatusText.text = BUSY_MESSAGE
            return
        }
        if (scanning) return
        val scanner = try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            GmsBarcodeScanning.getClient(activity, options)
        } catch (_: Exception) {
            setupStatusText.text = SCAN_UNAVAILABLE_MESSAGE
            return
        }
        val task = try {
            scanner.startScan()
        } catch (_: Exception) {
            requestScannerModule(scanner)
            return
        }
        scanning = true
        task.addOnSuccessListener { barcode -> applySetupText(barcode.rawValue, SetupSource.SCAN) }
            .addOnFailureListener { e -> handleScanFailure(scanner, e) }
            .addOnCompleteListener { scanning = false }  // キャンセル時もここに来る
    }

    /** 読み取り失敗の扱い。例外のメッセージは表示しない（決まった案内文だけを出す）。 */
    private fun handleScanFailure(scanner: GmsBarcodeScanner, e: Exception) {
        when (SetupFill.classifyScanError((e as? MlKitException)?.errorCode)) {
            SetupFill.ScanFailure.CANCELLED, SetupFill.ScanFailure.IN_PROGRESS -> Unit
            SetupFill.ScanFailure.PLAY_SERVICES_OLD -> setupStatusText.text =
                "Google Play開発者サービスが古いため読み取れません。更新するか、「クリップボードから読み込む」を使ってください"
            SetupFill.ScanFailure.CAMERA_DENIED -> setupStatusText.text =
                "カメラを使えないため読み取れません。Google Play開発者サービスにカメラを許可するか、「クリップボードから読み込む」を使ってください"
            SetupFill.ScanFailure.OTHER -> setupStatusText.text = SCAN_RETRY_MESSAGE
            SetupFill.ScanFailure.MODULE_MISSING -> requestScannerModule(scanner)
        }
    }

    /**
     * スキャン画面のモジュールを Google Play 開発者サービスに取得してもらう。
     * マニフェストの `com.google.mlkit.vision.DEPENDENCIES` は Play ストア経由のインストールでしか
     * 効かないため、APKを手動で入れた端末では初回にここを通る。取得が終わったら案内を出し直す。
     * Play 開発者サービスがない端末では取得自体が失敗するので、クリップボードへ案内する。
     */
    private fun requestScannerModule(scanner: GmsBarcodeScanner) {
        val client = try {
            ModuleInstall.getClient(activity)
        } catch (_: Exception) {
            setupStatusText.text = SCAN_UNAVAILABLE_MESSAGE
            return
        }
        val listener = object : InstallStatusListener {
            override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                val state = update.installState
                val completed = state == ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED
                val ended = completed ||
                    state == ModuleInstallStatusUpdate.InstallState.STATE_FAILED ||
                    state == ModuleInstallStatusUpdate.InstallState.STATE_CANCELED
                if (!ended) return
                client.unregisterListener(this)
                activity.runOnUiThread {
                    // 準備中の案内が出たままのときだけ差し替える（その間の別の表示は消さない）
                    if (setupStatusText.text.toString() == SCAN_PREPARING_MESSAGE) {
                        setupStatusText.text = if (completed) SCAN_READY_MESSAGE else SCAN_UNAVAILABLE_MESSAGE
                    }
                }
            }
        }
        val request = try {
            ModuleInstallRequest.newBuilder().addApi(scanner).setListener(listener).build()
        } catch (_: Exception) {
            setupStatusText.text = SCAN_UNAVAILABLE_MESSAGE
            return
        }
        setupStatusText.text = SCAN_PREPARING_MESSAGE
        client.installModules(request)
            .addOnSuccessListener { response ->
                if (response.areModulesAlreadyInstalled()) {
                    // モジュールはあるのに開けなかった。取得待ちではないので案内を変える
                    client.unregisterListener(listener)
                    setupStatusText.text = SCAN_RETRY_MESSAGE
                }
            }
            .addOnFailureListener {
                client.unregisterListener(listener)
                setupStatusText.text = SCAN_UNAVAILABLE_MESSAGE
            }
    }

    private fun clearClipboard(clipboard: ClipboardManager?) {
        if (clipboard == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (_: Exception) {
            // 消せなくても読み込み自体は済んでいる
        }
    }

    private fun bind() {
        if (inFlight.get()) {
            setupStatusText.text = BUSY_MESSAGE
            return
        }
        val url = urlInput.text.toString().trim()
        val password = passwordInput.text.toString()
        val deviceId = deviceIdInput.text.toString().trim()
        if (url.isEmpty() || password.isEmpty() || deviceId.isEmpty()) {
            setupStatusText.text = "URL・パスワード・端末IDをすべて入力してください"
            return
        }
        // パスワードを送る前に宛先を確かめる。Apps Script のウェブアプリ以外には送らない。
        if (!ChallengeInput.isValidApiUrl(url)) {
            setupStatusText.text = INVALID_URL_MESSAGE
            return
        }
        // F16: 紐づけには F15 で選んだアカウントが要る（端末とアカウントを結び付けるため）
        val email = GoogleAccounts(activity).saved().firstOrNull()?.name
        val accountId = accountIdOrWarn(email, setupStatusText) ?: return
        if (!inFlight.compareAndSet(false, true)) {
            setupStatusText.text = BUSY_MESSAGE
            return
        }
        bindButton.isEnabled = false
        setupStatusText.text = "確認中..."
        runInBackground(onError = {
            bindButton.isEnabled = true
            setupStatusText.text = "確認中にエラーが起きました。もう一度お試しください"
        }) {
            val result = ChallengeApi.verify(url, password, deviceId, accountId)
            val ui: () -> Unit = {
                bindButton.isEnabled = true
                if (result.ok) {
                    config.save(url, password, deviceId, result.deviceName, accountId)
                    passwordInput.setText("")
                    setupStatusText.text = ""
                    render()
                    showAccountLeft(result.lineLeft, result.googleLeft)
                    fillHoursFromUptime()
                    resendPending()
                } else {
                    setupStatusText.text = describe(result)
                }
            }
            ui
        }
    }

    private fun confirmUnbind() {
        if (inFlight.get()) {
            recordStatusText.text = BUSY_MESSAGE
            return
        }
        AlertDialog.Builder(activity)
            .setMessage("この端末の紐づけを解除しますか？\n未送信の記録は残ります。")
            .setPositiveButton("解除") { _, _ ->
                // ダイアログを開いてから押すまでに送信が始まっていたら解除しない
                val done = Exclusive.tryRun(inFlight) {
                    config.unbind()
                    urlInput.setText("")
                    passwordInput.setText("")
                    deviceIdInput.setText("")
                }
                if (done) render() else recordStatusText.text = BUSY_MESSAGE
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    // ---- 記録 ----

    private fun record(result: String) {
        if (inFlight.get()) {
            recordStatusText.text = BUSY_MESSAGE
            return
        }
        if (service.isEmpty()) {
            recordStatusText.text = "使用サービスを選んでください"
            return
        }
        val hours = ChallengeInput.parseHours(hoursInput.text.toString())
        if (hours == null) {
            recordStatusText.text = "寝かせ時間を0以上の数値で入力してください"
            return
        }
        val memo = memoInput.text.toString().trim()
        if (memo.length > 1000) {
            recordStatusText.text = "メモは1000文字以内にしてください"
            return
        }
        // F16: この機能より前に紐づけた端末。記録は作らず、入力はそのまま残して紐づけし直してもらう
        if (config.accountId.isEmpty()) {
            recordStatusText.text = "アカウントを選んで紐づけし直してください"
            rebindWithChosenAccount {
                recordStatusText.text = "紐づけし直しました。もう一度記録ボタンを押してください"
            }
            return
        }
        // 別の ChallengePanel（回転前の画面など）が送信中なら重ねて送らない
        if (!inFlight.compareAndSet(false, true)) {
            recordStatusText.text = BUSY_MESSAGE
            return
        }
        // 実施日時はここで固定する。通信が遅れても動かさない。
        val record = ChallengeRecord.create(config.deviceId, config.accountId, hours, result, service, memo)
        // 送信前に必ず保存する。ここで落ちても記録は消えない。
        // 端末の空きがなくて保存できなくても送信は試みる（送れれば記録は残る）。
        val saved = try {
            pending.add(record)
            true
        } catch (_: IOException) {
            false
        }
        renderPending()
        // 未送信に入った時点で記録は失われない。送信結果を待たずに入力欄を空け、
        // 通信の遅れを見てもう一度押したときに別IDの重複記録ができないようにする。
        if (saved) resetForm()

        // 送信中に紐づけ解除されても宛先が消えないよう、スレッド開始前にスナップショットする
        val url = config.apiUrl
        val password = config.password

        setRecordButtonsEnabled(false)
        recordStatusText.text = if (saved) "送信中..." else "送信中...（$NOT_SAVED_MESSAGE）"
        runInBackground(onError = {
            setRecordButtonsEnabled(true)
            recordStatusText.text =
                if (saved) "送信中にエラーが起きました。未送信に保存済みで、自動で送り直します"
                else "送信中にエラーが起きました（$NOT_SAVED_MESSAGE）"
            renderPending()
        }) {
            val response = ChallengeApi.send(url, password, record)
            if (saved) {
                try {
                    if (response.ok) {
                        pending.remove(record.id)
                    } else if (PendingRecords.needsAttention(response.code)) {
                        pending.markAttention(record.id, describe(response, record))
                    }
                } catch (_: IOException) {
                    // 消せなければ次回の再送でサーバー側の重複判定に任せる。
                    // 要確認の印が付けられなければ通常の未送信として再送される。
                }
            }
            val ui: () -> Unit = {
                setRecordButtonsEnabled(true)
                showAccountLeftFrom(response)
                if (response.ok) {
                    if (!saved) resetForm()
                    recordStatusText.text =
                        if (response.duplicate) "記録済みでした（重複なし）" else "記録しました"
                } else if (!saved) {
                    // 控えが無いので入力欄は残す。もう一度押してもらう必要がある。
                    recordStatusText.text = "送信できませんでした（${describe(response, record)}）。$NOT_SAVED_MESSAGE"
                } else if (PendingRecords.needsAttention(response.code)) {
                    recordStatusText.text = "${describe(response, record)}（未送信に残しました）"
                    if (response.code == USED_UP) showUsedUpDialog(record)
                } else {
                    recordStatusText.text =
                        "未送信として保存しました（${describe(response)}）。自動で送り直すので、もう一度押す必要はありません"
                }
                renderPending()
            }
            ui
        }
    }

    /**
     * 通信を背景スレッドで行い、結果の反映をUIスレッドへ渡す。
     * work は背景スレッドで動き、UIスレッドで実行する処理を返す。
     * 例外はスレッドの外へ出さず（出るとプロセスごと落ちる）、代わりに onError をUIスレッドで実行する。
     * 進行中フラグはUIへ渡す前に必ず戻す（UI側の処理から再送を始められるように）。
     * 呼び出し側は inFlight を立ててから呼ぶこと。
     */
    private fun runInBackground(onError: () -> Unit, work: () -> (() -> Unit)) {
        Thread {
            val ui: () -> Unit = try {
                work()
            } catch (_: Exception) {
                onError
            }
            inFlight.set(false)
            activity.runOnUiThread { ui() }
        }.start()
    }

    private fun setRecordButtonsEnabled(enabled: Boolean) {
        recordSuccessButton.isEnabled = enabled
        recordFailureButton.isEnabled = enabled
    }

    /** [record] は使い切りの文言にアカウントIDとサービスを入れるため（未送信一覧の行の見分けにも使う）。 */
    private fun describe(r: ChallengeApi.Result, record: ChallengeRecord? = null): String = when (r.code) {
        "AUTH" -> "パスワードが違います。紐づけを設定し直してください"
        "DEVICE_NOT_ACTIVE" -> "使用中の端末ではありません（対象外・売却済み）"
        "VALIDATION" -> "入力内容を確認してください: ${r.message}"
        "CONFLICT" -> "同じ記録IDで別の内容が保存済みです"
        "NETWORK" -> "通信できませんでした"
        USED_UP -> record?.let { AccountUsage.usedUpMessage(it.accountId, it.service) } ?: "アカウントを使い切っています"
        else -> r.message.ifEmpty { "サーバーエラー" }
    }

    // ---- 未送信 ----

    private fun renderPending() {
        val list = pending.all()
        pendingHeader.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        pendingContainer.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        pendingHeader.text = "未送信 ${list.size}件"
        pendingContainer.removeAllViews()

        list.forEach { r ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 6, 0, 6)
            }
            row.addView(TextView(activity).apply {
                val mark = if (r.status == ChallengeRecord.STATUS_ATTENTION) "※要確認 " else ""
                text = "$mark${r.result} / ${r.service} / ${ChallengeInput.formatHours(r.hours)}h"
                setTextColor(
                    if (r.status == ChallengeRecord.STATUS_ATTENTION)
                        activity.resources.getColor(R.color.pixel_accent, null)
                    else activity.resources.getColor(R.color.pixel_ink, null)
                )
                textSize = 12f
                typeface = pixelFontBold
            })
            row.addView(TextView(activity).apply {
                text = r.at + if (r.lastError.isEmpty()) "" else "\n${r.lastError}"
                setTextColor(activity.resources.getColor(R.color.pixel_sub, null))
                textSize = 11f
                typeface = pixelFont
            })

            val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(Button(activity).apply {
                text = "再送"
                isAllCaps = false
                textSize = 12f
                setTextColor(activity.resources.getColor(R.color.pixel_ink, null))
                setBackgroundResource(R.drawable.btn_pixel)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { resendOne(r) }
            })
            buttons.addView(Button(activity).apply {
                text = "破棄"
                isAllCaps = false
                textSize = 12f
                setTextColor(activity.resources.getColor(R.color.pixel_accent, null))
                setBackgroundResource(R.drawable.btn_pixel_accent)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { confirmDiscard(r) }
            })
            row.addView(buttons)
            if (AccountUsage.isUsedUp(r)) {
                val usedUpButtons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                usedUpButtons.addView(pendingButton("切り替えて紐づけ直す") { switchAccountAndResend(r) })
                usedUpButtons.addView(pendingButton("このまま送る") { confirmOverUse(r) })
                row.addView(usedUpButtons)
            }
            pendingContainer.addView(row)
        }
    }

    /** 未送信一覧の行のボタン（「再送」と同じ見た目）。 */
    private fun pendingButton(label: String, onClick: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        typeface = pixelFont
        setTextColor(activity.resources.getColor(R.color.pixel_ink, null))
        setBackgroundResource(R.drawable.btn_pixel)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { onClick() }
    }

    /**
     * 記録画面を開いたときの自動再送。
     * 要確認の記録は送り直しても同じ結果になるため飛ばす。
     */
    private fun resendPending() {
        if (!config.isBound()) return
        // 別の ChallengePanel（回転前の画面など）が送信中なら、そちらに任せる
        if (!inFlight.compareAndSet(false, true)) return
        val targets = pending.all().filter { it.status != ChallengeRecord.STATUS_ATTENTION }
        if (targets.isEmpty()) {
            inFlight.set(false)
            return
        }

        // 送信中に紐づけ解除されても宛先が消えないよう、スレッド開始前にスナップショットする
        val url = config.apiUrl
        val password = config.password

        recordStatusText.text = "未送信を送信中..."
        runInBackground(onError = {
            recordStatusText.text = "未送信の再送中にエラーが起きました"
            renderPending()
        }) {
            var sent = 0
            var failed = 0
            var queueError = false
            var usedUp: ChallengeRecord? = null
            var lastResponse: ChallengeApi.Result? = null
            for (r in targets) {
                // 内容もIDも変えずに送り直す。サーバー側が重複を弾く。
                val response = ChallengeApi.send(url, password, r)
                lastResponse = response
                if (response.code == USED_UP && usedUp == null) usedUp = r
                try {
                    when {
                        response.ok -> {
                            sent++
                            pending.remove(r.id)
                        }
                        PendingRecords.needsAttention(response.code) -> {
                            failed++
                            pending.markAttention(r.id, describe(response, r))
                        }
                        else -> failed++  // 通信・サーバー一時エラーは次回も再送する
                    }
                } catch (_: IOException) {
                    queueError = true
                }
            }
            val ui: () -> Unit = {
                val summary = when {
                    failed == 0 -> "未送信を${sent}件送信しました"
                    sent == 0 -> "未送信を送信できませんでした（${failed}件）"
                    else -> "未送信を${sent}件送信、${failed}件残っています"
                }
                recordStatusText.text = if (queueError) "$summary（$QUEUE_UPDATE_FAILED）" else summary
                renderPending()
                lastResponse?.let { showAccountLeftFrom(it) }
                // 何件止まっても、ダイアログは最初の1件だけ。残りは未送信一覧の行から選べる
                usedUp?.let { showUsedUpDialog(it) }
            }
            ui
        }
    }

    private fun resendOne(record: ChallengeRecord) {
        if (!config.isBound()) {
            recordStatusText.text = "先に端末を紐づけてください"
            return
        }
        if (!inFlight.compareAndSet(false, true)) {
            recordStatusText.text = BUSY_MESSAGE
            return
        }

        // 送信中に紐づけ解除されても宛先が消えないよう、スレッド開始前にスナップショットする
        val url = config.apiUrl
        val password = config.password

        recordStatusText.text = "送信中..."
        runInBackground(onError = {
            recordStatusText.text = "送信中にエラーが起きました"
            renderPending()
        }) {
            val response = ChallengeApi.send(url, password, record)
            var queueError = false
            try {
                if (response.ok) {
                    pending.remove(record.id)
                } else if (PendingRecords.needsAttention(response.code)) {
                    pending.markAttention(record.id, describe(response, record))
                }
            } catch (_: IOException) {
                queueError = true
            }
            val ui: () -> Unit = {
                val text =
                    if (response.ok) (if (response.duplicate) "記録済みでした" else "送信しました")
                    else describe(response, record)
                recordStatusText.text = if (queueError) "$text（$QUEUE_UPDATE_FAILED）" else text
                renderPending()
                showAccountLeftFrom(response)
                if (response.code == USED_UP) showUsedUpDialog(record)
            }
            ui
        }
    }

    private fun confirmDiscard(record: ChallengeRecord) {
        if (inFlight.get()) {
            recordStatusText.text = BUSY_MESSAGE
            return
        }
        AlertDialog.Builder(activity)
            .setMessage("この未送信の記録を破棄しますか？\n${record.at}\n${record.result} / ${record.service}")
            .setPositiveButton("破棄") { _, _ ->
                // ダイアログを開いてから押すまでに自動再送が始まっていたら、破棄した記録が
                // 届いてしまう。確定の時点でも送信中フラグを取り、取れなければ破棄しない。
                var message = BUSY_MESSAGE
                Exclusive.tryRun(inFlight) {
                    message = try {
                        pending.remove(record.id)
                        "未送信の記録を破棄しました"
                    } catch (_: IOException) {
                        "破棄できませんでした（端末の空きが不足しています）"
                    }
                }
                recordStatusText.text = message
                renderPending()
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    companion object {
        /**
         * 送信・紐づけ確認が進行中か。画面の回転などで Activity が作り直されると ChallengePanel が
         * 複数できるため、インスタンスではなくプロセス全体で1つにして、送信を同時に1本に絞る。
         */
        private val inFlight = AtomicBoolean(false)

        private const val USED_UP = "ACCOUNT_USED_UP"
        private const val BUSY_MESSAGE = "送信中です。終わるまでお待ちください"
        private const val NOT_SAVED_MESSAGE = "端末の空きがなく未送信にも保存できていません"
        private const val QUEUE_UPDATE_FAILED = "端末の空きがなく未送信の一覧を更新できませんでした"
        private const val SCAN_PREPARING_MESSAGE =
            "読み取り機能を準備しています。少し待ってからもう一度押してください"
        private const val SCAN_READY_MESSAGE =
            "読み取りの準備ができました。もう一度「QRを読み取る」を押してください"
        private const val SCAN_RETRY_MESSAGE =
            "読み取りを開始できませんでした。もう一度押すか、「クリップボードから読み込む」を使ってください"
        private const val SCAN_UNAVAILABLE_MESSAGE =
            "この端末ではQRの読み取りを使えません。「クリップボードから読み込む」を使ってください"
        private const val INVALID_URL_MESSAGE =
            "URLは https://script.google.com/macros/s/ で始まる記録APIのURLを入力してください"
    }
}
