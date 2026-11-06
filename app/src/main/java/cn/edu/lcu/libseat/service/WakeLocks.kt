package cn.edu.lcu.libseat.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * CPU 唤醒锁。
 *
 * 两把锁分开管理：
 *  - 任务锁：只在一次选座/取消期间持有，超时自动释放，避免忘记关掉耗电。
 *  - 守护锁：用户在“守护模式”打开时持有，直到主动关闭，保证凌晨定时任务被准时唤醒。
 * 另外加一把高性能 Wi-Fi 锁，避免休眠时无线网卡降频导致请求超时。
 */
object WakeLocks {

    private var taskLock: PowerManager.WakeLock? = null
    private var guardLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun acquireTask(context: Context, minutes: Long = 30) {
        val manager = context.getSystemService(PowerManager::class.java) ?: return
        if (taskLock?.isHeld == true) return
        taskLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LCUSeat:task").apply {
            setReferenceCounted(false)
            acquire(minutes.coerceAtLeast(1) * 60_000L)
        }
        acquireWifi(context)
    }

    fun releaseTask() {
        runCatching { if (taskLock?.isHeld == true) taskLock?.release() }
        taskLock = null
        releaseWifi()
    }

    fun acquireGuard(context: Context) {
        val manager = context.getSystemService(PowerManager::class.java) ?: return
        if (guardLock?.isHeld == true) return
        guardLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LCUSeat:guard").apply {
            setReferenceCounted(false)
            acquire()
        }
        acquireWifi(context)
    }

    fun releaseGuard() {
        runCatching { if (guardLock?.isHeld == true) guardLock?.release() }
        guardLock = null
        releaseWifi()
    }

    val guardHeld: Boolean get() = guardLock?.isHeld == true

    private fun acquireWifi(context: Context) {
        if (wifiLock?.isHeld == true) return
        runCatching {
            val manager = context.getSystemService(WifiManager::class.java) ?: return
            @Suppress("DEPRECATION")
            wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LCUSeat:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWifi() {
        if (guardLock?.isHeld == true || taskLock?.isHeld == true) return
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
    }
}
