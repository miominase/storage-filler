package jp.own.storagefiller

/**
 * 共有メニュー（ACTION_SEND, text/plain）で受け取った文字列から、URLと共有元テキストを取り出す。
 * Android に依存しない純ロジック。URL は文字列として扱うだけで、開く・取得することはしない。
 */
object SharedLink {

    data class Parsed(val url: String, val text: String)

    const val MAX_URL = 2000
    const val MAX_TEXT = 1000

    // URL に使える ASCII の記号・英数字だけを拾う（直後に空白なしで続く日本語や全角の括弧を取り込まない）
    private val URL_PATTERN = Regex("https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+", RegexOption.IGNORE_CASE)

    // 文中のURLの直後に付きがちな句読点・閉じ括弧（URL の一部ではないことが多い）
    private const val TRAILING = ".,;:!?)]}。、）」』】！？"

    /**
     * [text]（EXTRA_TEXT）から最初の http/https URL を取り出す。無ければ null。
     * [subject]（EXTRA_SUBJECT）は共有元テキストの先頭に足す（URL と重複しない範囲で）。
     * 共有元テキストは URL を含む全文を前後の空白だけ除いて [MAX_TEXT] 文字までにする。
     */
    fun parse(text: String?, subject: String? = null): Parsed? {
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return null
        val match = URL_PATTERN.find(body) ?: return null
        val url = match.value.trimEnd { it in TRAILING }
        if (url.length <= "https://".length || url.length > MAX_URL) return null
        val subj = subject?.trim().orEmpty()
        val full = if (subj.isNotEmpty() && !body.contains(subj)) "$subj\n$body" else body
        return Parsed(url, full.take(MAX_TEXT))
    }
}
