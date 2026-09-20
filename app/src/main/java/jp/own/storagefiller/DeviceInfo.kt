package jp.own.storagefiller

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.util.Locale

object DeviceInfo {

    fun model(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    fun androidVersion(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    fun cpu(): String {
        var hardware = ""
        try {
            File("/proc/cpuinfo").forEachLine { line ->
                if (hardware.isEmpty() &&
                    (line.startsWith("Hardware") || line.startsWith("model name") || line.startsWith("Processor"))
                ) {
                    hardware = line.substringAfter(":").trim()
                }
            }
        } catch (_: Exception) {
        }
        if (hardware.isEmpty()) hardware = Build.HARDWARE
        return "$hardware / ${Runtime.getRuntime().availableProcessors()}コア"
    }

    fun ramTotal(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }

    /** @return Pair(空きバイト, 総容量バイト) — 内蔵ストレージ（外部ストレージ領域） */
    fun storage(): Pair<Long, Long> {
        val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        return stat.availableBytes to stat.totalBytes
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        do {
            value /= 1024.0
            unit++
        } while (value >= 1024.0 && unit < units.size - 1)
        return String.format(Locale.US, "%.2f %s", value, units[unit])
    }

    /** 日換算なしの総時間表示。例: 371時間 20分 37秒 */
    fun formatDuration(millis: Long): String {
        val totalSec = millis / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return String.format(Locale.US, "%d時間 %02d分 %02d秒", hours, minutes, seconds)
    }
}
