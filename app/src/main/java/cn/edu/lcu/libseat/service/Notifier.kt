package cn.edu.lcu.libseat.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.edu.lcu.libseat.R
import cn.edu.lcu.libseat.ui.MainActivity

/** 通知：前台服务、任务进度、执行结果各用一个渠道，方便用户单独静音。 */
object Notifier {

    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_TASK = "task"
    const val CHANNEL_RESULT = "result"

    const val NOTIF_GUARD = 1001
    const val NOTIF_TASK = 1002
    const val NOTIF_RESULT = 1003

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "守护服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持 CPU 唤醒和执行定时任务时显示"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TASK, "任务进度", NotificationManager.IMPORTANCE_LOW).apply {
                description = "选座、取消等任务的实时进度"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "执行结果", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "任务成功、失败与需要人工处理的提醒"
            }
        )
    }

    fun guard(context: Context, text: String): Notification =
        build(context, CHANNEL_SERVICE, NOTIF_GUARD, "守护中 · CPU 保持唤醒", text, ongoing = true)

    fun task(context: Context, text: String): Notification =
        build(context, CHANNEL_TASK, NOTIF_TASK, "任务执行中", text, ongoing = true)

    fun result(context: Context, title: String, text: String, ok: Boolean): Notification =
        build(
            context,
            CHANNEL_RESULT,
            NOTIF_RESULT,
            (if (ok) "✅ " else "⚠️ ") + title,
            text,
            ongoing = false,
        )

    fun update(context: Context, id: Int, notification: Notification) {
        notifyIfAllowed(context, id, notification)
    }

    fun postResult(context: Context, title: String, text: String, ok: Boolean) {
        notifyIfAllowed(context, NOTIF_RESULT, result(context, title, text, ok))
    }

    fun cancel(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    /**
     * Android 13+ 需要运行时通知权限，权限被拒绝时静默跳过，任务本身照常执行。
     * 权限检查必须写在本函数内，静态检查工具才能确认调用是安全的。
     */
    private fun notifyIfAllowed(context: Context, id: Int, notification: Notification) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun build(
        context: Context,
        channel: String,
        id: Int,
        title: String,
        text: String,
        ongoing: Boolean,
    ): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("notification_id", id)
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
            .setAutoCancel(!ongoing)
            .build()
    }
}
