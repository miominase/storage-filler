package jp.own.storagefiller

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 送信中フラグ（ChallengePanel.inFlight）を使った「他の処理と同時に走らせない」小さな手助け。
 *
 * 破棄・解除の確認ダイアログは、開いた時点だけでなく「確定を押した時点」でも送信中でないことを
 * 確かめる必要がある。開いてから押すまでの間に自動再送が始まると、破棄した記録が届いてしまうため。
 * フラグを取れたときだけ [block] を実行し、終わったら（例外でも）必ず戻す。
 */
object Exclusive {

    /** @return 実行したら true。送信中で実行しなかったら false。 */
    fun tryRun(flag: AtomicBoolean, block: () -> Unit): Boolean {
        if (!flag.compareAndSet(false, true)) return false
        try {
            block()
        } finally {
            flag.set(false)
        }
        return true
    }
}
