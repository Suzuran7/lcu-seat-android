package cn.edu.lcu.libseat.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import cn.edu.lcu.libseat.SeatApp
import cn.edu.lcu.libseat.core.SeatStore
import cn.edu.lcu.libseat.core.TimeKit
import cn.edu.lcu.libseat.schedule.AlarmScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务，承担两件事：
 *  1. 定点执行任务（由精确闹钟拉起），执行期间持有 CPU 唤醒锁；
 *  2. 守护模式：用户开启后长期持有唤醒锁，避免手机深度休眠时错过凌晨放座。
 */
class TaskService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: SeatStore
    private var guardRunning = false
    private var inForeground = false
    private var guardJob: Job? = null
    private var taskJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        store = (application as SeatApp).store
        guardRunning = store.guardEnabled
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_GUARD -> startGuard()
            ACTION_STOP_GUARD -> stopGuard()
            null -> if (guardRunning) {
                startGuard()
            } else {
                // 系统重启服务：先满足前台服务要求，再退出。
                enterForeground(Notifier.task(this, "已恢复定时调度"))
                leaveForeground()
                stopSelf(startId)
            }

            else -> runRule(intent?.getStringExtra(EXTRA_RULE_ID), startId)
        }
        return START_STICKY
    }

    // ---------------------------------------------------------- 守护模式

    private fun startGuard() {
        guardRunning = true
        store.guardEnabled = true
        WakeLocks.acquireGuard(this)
        enterForeground(Notifier.guard(this, guardText()), Notifier.NOTIF_GUARD)
        guardJob?.cancel()
        guardJob = scope.launch {
            while (isActive) {
                delay(60_000)
                Notifier.update(
                    this@TaskService,
                    Notifier.NOTIF_GUARD,
                    Notifier.guard(this@TaskService, guardText()),
                )
            }
        }
    }

    private fun stopGuard() {
        guardRunning = false
        store.guardEnabled = false
        guardJob?.cancel()
        guardJob = null
        WakeLocks.releaseGuard()
        Notifier.cancel(this, Notifier.NOTIF_GUARD)
        if (taskJob?.isActive == true) return
        leaveForeground()
        stopSelf()
    }

    private fun guardText(): String {
        val next = store.rules()
            .filter { it.enabled }
            .mapNotNull { rule -> TimeKit.nextTrigger(rule)?.let { it to rule } }
            .minByOrNull { it.first }
            ?: return "守护中 · 暂无启用中的任务"
        return "下次 ${TimeKit.nextTriggerText(next.second)} · ${next.second.display}"
    }

    // ---------------------------------------------------------- 任务执行

    private fun runRule(ruleId: String?, startId: Int) {
        // startForegroundService 要求 5 秒内进入前台，先无条件满足。
        if (!inForeground) enterForeground(Notifier.task(this, "准备执行任务"))

        val rule = ruleId?.let { store.rule(it) }
        if (rule == null) {
            if (!guardRunning) {
                leaveForeground()
                stopSelf(startId)
            }
            return
        }

        if (rule.keepAwake) {
            WakeLocks.acquireTask(this, rule.maxMinutes.coerceAtLeast(5).toLong() + 5)
        }

        taskJob?.cancel()
        taskJob = scope.launch {
            try {
                val runner = TaskRunner(this@TaskService, store)
                val outcome = runner.run(rule) { line ->
                    Notifier.update(
                        this@TaskService,
                        Notifier.NOTIF_TASK,
                        Notifier.task(this@TaskService, "${rule.display}：$line"),
                    )
                }
                Notifier.postResult(this@TaskService, outcome.title, outcome.detail, outcome.ok)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Notifier.postResult(
                    this@TaskService,
                    "${rule.display} · 执行异常",
                    e.message ?: "未知错误",
                    false,
                )
            } finally {
                WakeLocks.releaseTask()
                // 每天重复的任务在这里排下一次。
                runCatching { AlarmScheduler(this@TaskService).schedule(rule) }
                if (guardRunning) {
                    if (guardJob?.isActive != true) {
                        startGuard()
                    } else {
                        enterForeground(
                            Notifier.guard(this@TaskService, guardText()),
                            Notifier.NOTIF_GUARD,
                        )
                    }
                } else {
                    leaveForeground()
                    stopSelf(startId)
                }
            }
        }
    }

    // ---------------------------------------------------------- 前后台切换

    private fun enterForeground(notification: Notification, id: Int = Notifier.NOTIF_TASK) {
        startForeground(id, notification)
        inForeground = true
    }

    private fun leaveForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        inForeground = false
    }

    override fun onDestroy() {
        scope.cancel()
        WakeLocks.releaseTask()
        WakeLocks.releaseGuard()
        super.onDestroy()
    }

    companion object {
        const val ACTION_RUN_RULE = "cn.edu.lcu.libseat.RUN_RULE"
        const val ACTION_START_GUARD = "cn.edu.lcu.libseat.START_GUARD"
        const val ACTION_STOP_GUARD = "cn.edu.lcu.libseat.STOP_GUARD"
        const val EXTRA_RULE_ID = "rule_id"
    }
}
