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
    private val pixelFontBold: Typeface
) {
    private val config = ChallengeConfig(activity)
    private val pending = PendingRecords(activity)

    private val boundDeviceText: TextView = root.findViewById(R.id.boundDeviceText)
    private val setupBox: View = root.findViewById(R.id.setupBox)
    private val urlInput: EditText = root.findViewById(R.id.urlInput)
    private val passwordInput: EditText = root.findViewById(R.id.passwordInput)
    private val deviceIdInput: EditText = root.findViewById(R.id.deviceIdInput)
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

    init {
        listOf(clipboardImportButton, bindButton, recordSuccessButton, recordFailureButton, unbindButton)
            .forEach { it.isAllCaps = false }
        serviceLine.setOnClickListener { selectService("LINE") }
        serviceGoogle.setOnClickListener { selectService("Google") }
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
            resendPending()
        }
    }

    private fun render() {
        val bound = config.isBound()
        setupBox.visibility = if (bound) View.GONE else View.VISIBLE
        recordBox.visibility = if (bound) View.VISIBLE else View.GONE
        boundDeviceText.text =
            if (bound) "${config.deviceName}（ID: ${config.deviceId}）" else "まだ紐づけされていません"
        renderPending()
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
        if (!url.isNullOrEmpty() && !ChallengeInput.isValidApiUrl(url)) {
            setupStatusText.text = INVALID_URL_MESSAGE
            return false
        }
        var filled = false
        if (!url.isNullOrEmpty()) {
            urlInput.setText(url)
            if (password.isNullOrEmpty()) passwordInput.setText("")
            filled = true
        }
        if (!password.isNullOrEmpty()) {
            passwordInput.setText(password)
            filled = true
        }
        if (filled) {
            setupStatusText.text = "QRから読み込みました。端末IDを入れてください"
        }
        return filled
    }

    /**
     * クリップボードのテキストをQRの内容として読み込む。標準カメラの多くはQRを開く手段を
     * 提示せず「コピー」しか出さないため、この経路が主な入口になる。判定・抽出は
     * `handleIncomingIntent` と共通の `SetupLink.parse()` を使い、実際に欄へ入れる処理は
     * `prefillFromUri` をそのまま再利用する（紐づけ済みなら何もしない・端末IDは触らない・
     * 自動送信しない、という規則もそちらに揃う）。
     *
     * クリップボードの中身（＝パスワードそのもの）はステータス表示にもログにも一切出さない。
     * 読み込めたらクリップボードを空にする（キーボードのクリップボード履歴に残さないため）。
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
        val parsed = text?.let { SetupLink.parse(it) }
        val url = parsed?.first
        val password = parsed?.second
        if (url.isNullOrEmpty() && password.isNullOrEmpty()) {
            setupStatusText.text = "クリップボードに読み込める内容がありませんでした"
            return
        }
        if (prefillFromUri(url, password)) clearClipboard(clipboard)
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
            val result = ChallengeApi.verify(url, password, deviceId)
            val ui: () -> Unit = {
                bindButton.isEnabled = true
                if (result.ok) {
                    config.save(url, password, deviceId, result.deviceName)
                    passwordInput.setText("")
                    setupStatusText.text = ""
                    render()
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
                config.unbind()
                urlInput.setText("")
                passwordInput.setText("")
                deviceIdInput.setText("")
                render()
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
        // 別の ChallengePanel（回転前の画面など）が送信中なら重ねて送らない
        if (!inFlight.compareAndSet(false, true)) {
            recordStatusText.text = BUSY_MESSAGE
            return
        }
        // 実施日時はここで固定する。通信が遅れても動かさない。
        val record = ChallengeRecord.create(config.deviceId, hours, result, service, memo)
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
                        pending.markAttention(record.id, describe(response))
                    }
                } catch (_: IOException) {
                    // 消せなければ次回の再送でサーバー側の重複判定に任せる。
                    // 要確認の印が付けられなければ通常の未送信として再送される。
                }
            }
            val ui: () -> Unit = {
                setRecordButtonsEnabled(true)
                if (response.ok) {
                    if (!saved) resetForm()
                    recordStatusText.text =
                        if (response.duplicate) "記録済みでした（重複なし）" else "記録しました"
                } else if (!saved) {
                    // 控えが無いので入力欄は残す。もう一度押してもらう必要がある。
                    recordStatusText.text = "送信できませんでした（${describe(response)}）。$NOT_SAVED_MESSAGE"
                } else if (PendingRecords.needsAttention(response.code)) {
                    recordStatusText.text = "${describe(response)}（未送信に残しました）"
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

    private fun describe(r: ChallengeApi.Result): String = when (r.code) {
        "AUTH" -> "パスワードが違います。紐づけを設定し直してください"
        "DEVICE_NOT_ACTIVE" -> "使用中の端末ではありません（対象外・売却済み）"
        "VALIDATION" -> "入力内容を確認してください: ${r.message}"
        "CONFLICT" -> "同じ記録IDで別の内容が保存済みです"
        "NETWORK" -> "通信できませんでした"
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
            pendingContainer.addView(row)
        }
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
            for (r in targets) {
                // 内容もIDも変えずに送り直す。サーバー側が重複を弾く。
                val response = ChallengeApi.send(url, password, r)
                try {
                    when {
                        response.ok -> {
                            sent++
                            pending.remove(r.id)
                        }
                        PendingRecords.needsAttention(response.code) -> {
                            failed++
                            pending.markAttention(r.id, describe(response))
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
                    pending.markAttention(record.id, describe(response))
                }
            } catch (_: IOException) {
                queueError = true
            }
            val ui: () -> Unit = {
                val text =
                    if (response.ok) (if (response.duplicate) "記録済みでした" else "送信しました")
                    else describe(response)
                recordStatusText.text = if (queueError) "$text（$QUEUE_UPDATE_FAILED）" else text
                renderPending()
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
                recordStatusText.text = try {
                    pending.remove(record.id)
                    "未送信の記録を破棄しました"
                } catch (_: IOException) {
                    "破棄できませんでした（端末の空きが不足しています）"
                }
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

        private const val BUSY_MESSAGE = "送信中です。終わるまでお待ちください"
        private const val NOT_SAVED_MESSAGE = "端末の空きがなく未送信にも保存できていません"
        private const val QUEUE_UPDATE_FAILED = "端末の空きがなく未送信の一覧を更新できませんでした"
        private const val INVALID_URL_MESSAGE =
            "URLは https://script.google.com/macros/s/ で始まる記録APIのURLを入力してください"
    }
}
