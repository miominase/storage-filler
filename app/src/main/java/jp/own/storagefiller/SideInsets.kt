package jp.own.storagefiller

/**
 * 横画面で左右に出るシステムバー（3ボタンのナビゲーションバー）やカメラの切り欠きを避けるための計算。
 * 画面に触れない純ロジックなので JVM 単体テストで確かめる。値はすべてピクセル。
 */
object SideInsets {

    /**
     * 右下（end 側）に置くメニューボタンの end マージン。XML のマージン [base] に、
     * ボタンを寄せている側のインセットを足す。左から右へ読む配置なら右、右から左なら左。
     */
    fun menuEndMargin(base: Int, left: Int, right: Int, rtl: Boolean): Int =
        base + if (rtl) left else right

    /** パネル（ScrollView）の左右パディング。元の値 [baseLeft] [baseRight] に左右のインセットを足す。 */
    fun panelPadding(baseLeft: Int, baseRight: Int, left: Int, right: Int): Pair<Int, Int> =
        Pair(baseLeft + left, baseRight + right)
}
