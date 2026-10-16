package jp.own.storagefiller

/**
 * アカウントの使用回数（F16）の表示文言と判断。画面に触れない純ロジックなので JVM 単体テストで確かめる。
 */
object AccountUsage {

    /** チャレンジ記録パネルの1行。残り回数が取れなければ「確認できません」。 */
    fun leftLabel(accountId: String, lineLeft: Int?, googleLeft: Int?): String = when {
        accountId.isEmpty() -> "アカウント: なし（記録の前に紐づけし直してください）"
        lineLeft == null || googleLeft == null -> "アカウント: $accountId　残り: 確認できません"
        else -> "アカウント: $accountId　LINE 残り $lineLeft・Google 残り $googleLeft"
    }

    // 未送信一覧の要確認の行はこの文言そのもので見分ける。変えると端末にたまった行のボタンが出なくなる
    fun usedUpMessage(accountId: String, service: String): String = "$accountId は $service を使い切っています"

    /** 「切り替えて紐づけ直す」で送り直す記録。使い切り後の印は新しいアカウントへ持ち越さない。 */
    fun switchedTo(record: ChallengeRecord, newAccountId: String): ChallengeRecord =
        record.copy(accountId = newAccountId, overUse = false)

    /** 未送信一覧で「切り替えて紐づけ直す」「このまま送る」を出す行か。要確認の理由は lastError の文言で見分ける。 */
    fun isUsedUp(record: ChallengeRecord): Boolean =
        record.status == ChallengeRecord.STATUS_ATTENTION && record.accountId.isNotEmpty() &&
            record.lastError == usedUpMessage(record.accountId, record.service)
}
