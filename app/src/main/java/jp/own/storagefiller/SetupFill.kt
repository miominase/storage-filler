package jp.own.storagefiller

import com.google.mlkit.common.MlKitException

/**
 * 紐づけ画面の初回設定（URL・共通パスワード）を欄へ入れるときの判断。
 * 画面に触れない純ロジックなので JVM 単体テストで確かめる。
 *
 * 値そのもの（パスワード入り）は返り値に載せて欄へ入れるだけで、ログやステータス表示には出さない。
 */
object SetupFill {

    /** 取り込んだ (url, password) をどう欄へ反映するか。 */
    sealed class Plan {
        /** URLもパスワードも見つからなかった。欄は触らない。 */
        data object Empty : Plan()

        /** 記録APIとして正しくないURLだった。欄は触らない（パスワードも入れない）。 */
        data object InvalidUrl : Plan()

        /**
         * 欄に入れる。null の欄はそのまま残す。
         * password が "" のときはパスワード欄を空にする（URLだけのリンクで宛先だけ差し替え、
         * 入力途中の本物のパスワードを他所へ送らせないため）。
         */
        data class Fill(val url: String?, val password: String?) : Plan() {
            // data class の既定の toString はパスワードをそのまま出すので伏せる
            override fun toString(): String =
                "Fill(url=${if (url == null) "null" else "set"}, password=${if (password == null) "null" else "***"})"
        }
    }

    fun plan(url: String?, password: String?): Plan {
        val hasUrl = !url.isNullOrEmpty()
        val hasPassword = !password.isNullOrEmpty()
        if (hasUrl && !ChallengeInput.isValidApiUrl(url!!)) return Plan.InvalidUrl
        if (!hasUrl && !hasPassword) return Plan.Empty
        return Plan.Fill(
            url = if (hasUrl) url else null,
            password = when {
                hasPassword -> password
                hasUrl -> ""
                else -> null
            }
        )
    }

    /**
     * クリップボードから取り込んだあと、クリップボードを空にするか。
     * 設定用の内容（URLかパスワード）が入っていたら、欄に入れられたかどうかに関係なく消す
     * （不正なURLで弾いたときもパスワードがキーボードの履歴に残らないように）。
     * 設定用の内容が無かったときは、関係ないクリップボードなので触らない。
     */
    fun shouldClearClipboard(plan: Plan): Boolean = plan != Plan.Empty

    /** QR読み取り（Google Code Scanner）が失敗したときの扱い。 */
    enum class ScanFailure {
        /** ユーザーが閉じた。何もしない。 */
        CANCELLED,

        /** すでに読み取り画面を開いている。何もしない。 */
        IN_PROGRESS,

        /** スキャン画面のモジュールが未取得（または理由不明）。取得を依頼する。 */
        MODULE_MISSING,

        /** Google Play 開発者サービスが古い。更新するかクリップボードを使ってもらう。 */
        PLAY_SERVICES_OLD,

        /** Google Play 開発者サービスにカメラが許可されていない。 */
        CAMERA_DENIED,

        /** それ以外の読み取り失敗。もう一度押すかクリップボードを使ってもらう。 */
        OTHER
    }

    /**
     * 失敗の例外を分類する。[errorCode] は `MlKitException.errorCode`、MlKitException 以外
     * （Play 開発者サービスの ApiException など）のときは null。
     * 理由が分からない失敗はモジュール未取得として扱い、取得を依頼してみる
     * （取得も失敗すればクリップボードへ案内するので、行き止まりにはならない）。
     */
    fun classifyScanError(errorCode: Int?): ScanFailure = when (errorCode) {
        // 13（INTERNAL）は、読み取り画面が理由を付けずに閉じたとき（端末の「戻る」など）にライブラリが
        // 既定値として入れる番号。× の 201 と同じく「閉じた」とみなして何も出さない（KNOWLEDGE.md）
        MlKitException.INTERNAL,
        MlKitException.CODE_SCANNER_CANCELLED -> ScanFailure.CANCELLED
        MlKitException.CODE_SCANNER_TASK_IN_PROGRESS -> ScanFailure.IN_PROGRESS
        MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD -> ScanFailure.PLAY_SERVICES_OLD
        MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED -> ScanFailure.CAMERA_DENIED
        MlKitException.CODE_SCANNER_APP_NAME_UNAVAILABLE,
        MlKitException.CODE_SCANNER_PIPELINE_INITIALIZATION_ERROR,
        MlKitException.CODE_SCANNER_PIPELINE_INFERENCE_ERROR -> ScanFailure.OTHER
        else -> ScanFailure.MODULE_MISSING  // UNAVAILABLE / CODE_SCANNER_UNAVAILABLE / 不明
    }

    /** 読み取りモジュールの取得を待つ時間。過ぎても終わらなければ案内を差し替える。 */
    const val MODULE_WAIT_TIMEOUT_MS = 60_000L

    /** 読み取りモジュールの取得中に、案内欄にいま何が出ているか。 */
    enum class ModuleWaitShown {
        /** 「準備しています」 */
        PREPARING,

        /** 「時間がかかっています」（タイムアウト後） */
        SLOW,

        /** それ以外（別の操作の案内など）。取得の結果で上書きしない。 */
        OTHER
    }

    /** 読み取りモジュールの取得で起きたこと。 */
    enum class ModuleWaitEvent { TIMED_OUT, COMPLETED, FAILED }

    /** 案内欄に出し直す内容。 */
    enum class ModuleWaitNotice { SLOW, READY, UNAVAILABLE }

    /**
     * 取得待ちの案内をどう差し替えるか。null は「変えない」。
     * 取得待ちの案内（準備中・時間がかかっています）が出ているときだけ差し替え、
     * その間に出た別の案内は消さない。タイムアウト後に取得が終われば「準備ができました」に変える。
     */
    fun moduleWaitNext(shown: ModuleWaitShown, event: ModuleWaitEvent): ModuleWaitNotice? {
        if (shown == ModuleWaitShown.OTHER) return null
        return when (event) {
            ModuleWaitEvent.TIMED_OUT -> if (shown == ModuleWaitShown.PREPARING) ModuleWaitNotice.SLOW else null
            ModuleWaitEvent.COMPLETED -> ModuleWaitNotice.READY
            ModuleWaitEvent.FAILED -> ModuleWaitNotice.UNAVAILABLE
        }
    }
}
