package jp.own.storagefiller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * ストレージ種類比率を表示するドーナツ型円グラフ。
 * segments: (ラベル, バイト数, 色) のリスト。中央に使用率%を表示。
 *
 * 描画はドット絵UIに合わせて、正方形セルのグリッドを1マスずつ塗る方式。
 * アンチエイリアスは使わず、セル中心がリングの内外どちらにあるかだけで塗り分ける。
 */
class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Segment(val label: String, val bytes: Long, val color: Int)

    private var segments: List<Segment> = emptyList()
    private var centerText: String = "--"

    /** リング全体をおよそ何マスで表すか。大きいほど滑らかでドット感が薄れる */
    private val cellsAcross = 26

    private val cellPaint = Paint().apply { isAntiAlias = false }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
    }
    private val emptyColor = 0xFFE8E8E8.toInt()

    fun setTypeface(tf: Typeface?) {
        textPaint.typeface = tf
        invalidate()
    }

    fun setData(segments: List<Segment>, usedPercent: Int) {
        this.segments = segments
        this.centerText = "$usedPercent%"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        if (size <= 0) return

        val cell = size / cellsAcross
        val cx = width / 2f
        val cy = height / 2f
        val outer = size / 2f
        val inner = outer * 0.50f

        val total = segments.sumOf { it.bytes }
        // 各セグメントの終了角度（12時から時計回りの度数）をあらかじめ積み上げておく
        val bounds = ArrayList<Pair<Float, Int>>(segments.size)
        if (total > 0) {
            var acc = 0f
            for (seg in segments) {
                if (seg.bytes <= 0) continue
                acc += seg.bytes.toFloat() / total * 360f
                bounds.add(acc to seg.color)
            }
        }

        var y = cy - outer
        while (y < cy + outer) {
            var x = cx - outer
            while (x < cx + outer) {
                val mx = x + cell / 2f
                val my = y + cell / 2f
                val r = hypot(mx - cx, my - cy)
                if (r in inner..outer) {
                    cellPaint.color =
                        if (bounds.isEmpty()) emptyColor
                        else colorAt(degreesFromTop(mx - cx, my - cy), bounds)
                    // セル境界に隙間が出ないよう +1px だけ重ねる
                    canvas.drawRect(x, y, x + cell + 1f, y + cell + 1f, cellPaint)
                }
                x += cell
            }
            y += cell
        }

        textPaint.textSize = size * 0.19f
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(centerText, cx, textY, textPaint)
    }

    /** 12時を0度として時計回りの角度 */
    private fun degreesFromTop(dx: Float, dy: Float): Float {
        val deg = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
        return if (deg < 0) deg + 360f else deg
    }

    private fun colorAt(deg: Float, bounds: List<Pair<Float, Int>>): Int {
        for ((end, color) in bounds) {
            if (deg < end) return color
        }
        return bounds.last().second
    }
}
