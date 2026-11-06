package cn.edu.lcu.libseat.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import cn.edu.lcu.libseat.core.Rule
import cn.edu.lcu.libseat.core.TimeKit
import cn.edu.lcu.libseat.service.TaskService

/**
 * 精确定时。
 *
 * 用 RTC_WAKEUP 闹钟在指定时刻把设备唤醒并直接拉起前台服务，
 * 同时在任务执行完后自动排下一次，因此每个任务只需要一条闹钟记录。
 */
class AlarmScheduler(private val context: Context) {

    private val manager: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    fun syncAll(rules: List<Rule>) {
        rules.forEach { if (it.enabled) schedule(it) else cancel(it) }
    }

    fun schedule(rule: Rule) {
        val alarm = manager ?: return
        val next = TimeKit.nextTrigger(rule)
        if (next == null) {
            cancel(rule)
            return
        }
        val pending = pendingIntent(rule)
        val triggerAt = next.toInstant().toEpochMilli()
        try {
            if (canScheduleExact()) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                // 未授予精确定时权限时退化为允许待机唤醒的非精确闹钟。
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        } catch (e: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun cancel(rule: Rule) {
        val alarm = manager ?: return
        alarm.cancel(pendingIntent(rule))
    }

    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager?.canScheduleExactAlarms() == true
        } else {
            true
        }

    private fun pendingIntent(rule: Rule): PendingIntent {
        val intent = Intent(context, TaskService::class.java).apply {
            action = TaskService.ACTION_RUN_RULE
            putExtra(TaskService.EXTRA_RULE_ID, rule.id)
        }
        return PendingIntent.getForegroundService(
            context,
            rule.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
