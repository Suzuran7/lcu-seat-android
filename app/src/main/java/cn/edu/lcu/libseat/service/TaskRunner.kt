package cn.edu.lcu.libseat.service

import android.content.Context
import cn.edu.lcu.libseat.core.AuthExpiredException
import cn.edu.lcu.libseat.core.BookingUncertainException
import cn.edu.lcu.libseat.core.CaptchaSolver
import cn.edu.lcu.libseat.core.LogEntry
import cn.edu.lcu.libseat.core.ManualActionRequired
import cn.edu.lcu.libseat.core.ReserveOutcome
import cn.edu.lcu.libseat.core.Rule
import cn.edu.lcu.libseat.core.SeatApi
import cn.edu.lcu.libseat.core.SeatException
import cn.edu.lcu.libseat.core.SeatOps
import cn.edu.lcu.libseat.core.SeatStore
import cn.edu.lcu.libseat.core.SeatUnavailableException
import cn.edu.lcu.libseat.core.TaskAction
import cn.edu.lcu.libseat.core.TimeKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 一个任务的完整执行流程：登录 → 选座 → 验证码 → 提交 / 取消，失败按间隔重试到时间窗口结束。
 *
 * 与 Python 版 multi-book / reserve_loop 的行为对齐：
 *  - 会话失效自动重登一次并继续
 *  - 结果不确定时先查预约记录，绝不重复提交
 *  - 单个任务失败只影响自己，结果写进执行记录
 */
class TaskRunner(private val context: Context, private val store: SeatStore) {

    data class Outcome(val ok: Boolean, val title: String, val detail: String)

    private var progress: (String) -> Unit = {}

    suspend fun run(rule: Rule, onProgress: (String) -> Unit = {}): Outcome {
        progress = onProgress
        val account = store.account(rule.accountId)
            ?: return finish(rule, "-", Outcome(false, rule.display, "任务绑定的账号已被删除"))

        val api = SeatApi()
        val ops = SeatOps(api, CaptchaSolver())
        // 识别与选座的中间过程既推给通知，也留一份给失败时写进记录。
        val notes = mutableListOf<String>()
        ops.onLog = { line ->
            notes += line
            if (notes.size > MAX_NOTES) notes.removeAt(0)
            onProgress(line)
        }

        val outcome = try {
            api.bootstrap()
            var current = ops.login(account)
            store.updateSession(current.id, current.token, current.userId)
            when (rule.action) {
                TaskAction.BOOK -> doBook(rule, ops)
                TaskAction.CANCEL -> doCancel(rule, ops)
            }
        } catch (e: ManualActionRequired) {
            Outcome(false, "${rule.display} · 需要人工处理", e.message ?: "需要人工完成验证码")
        } catch (e: AuthExpiredException) {
            Outcome(false, "${rule.display} · 登录失效", e.message ?: "请打开 App 重新登录")
        } catch (e: SeatException) {
            Outcome(false, "${rule.display} · 未成功", e.message ?: "执行失败")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome(false, "${rule.display} · 执行异常", e.message ?: e.javaClass.simpleName)
        }

        val withNotes = if (!outcome.ok && notes.isNotEmpty()) {
            outcome.copy(detail = outcome.detail + "\n— 最近过程 —\n" + notes.takeLast(3).joinToString("\n"))
        } else {
            outcome
        }
        return finish(rule, account.display, withNotes)
    }

    private fun finish(rule: Rule, accountName: String, outcome: Outcome): Outcome {
        store.addLog(
            LogEntry(
                time = System.currentTimeMillis(),
                ruleName = rule.display,
                account = accountName,
                ok = outcome.ok,
                message = "${outcome.title}：${outcome.detail}",
            )
        )
        return outcome
    }

    private suspend fun doBook(rule: Rule, ops: SeatOps): Outcome {
        val today = TimeKit.todayIso()
        val date = today
        val start = if (rule.start.equals("now", true)) {
            if (date != today) throw SeatException("开始时间 now 只能用于今天")
            "now"
        } else {
            TimeKit.toMinuteId(rule.start)
        }
        val end = TimeKit.toMinuteId(rule.end)
        if (start != "now" && start.toInt() >= end.toInt()) {
            throw SeatException("结束时间必须晚于开始时间")
        }

        val deadline = System.currentTimeMillis() + rule.maxMinutes.coerceAtLeast(0) * 60_000L
        var attempt = 0
        var lastError = ""

        while (true) {
            attempt++
            progress("第 $attempt 次尝试：$date $start-$end")
            try {
                val result = ops.reserveOnce(rule, date, start, end)
                return when (result) {
                    is ReserveOutcome.Reserved -> Outcome(
                        true,
                        "${rule.display} · 预约成功",
                        "${result.choice.describe}\n$date ${rule.start}-${rule.end}" +
                            if (result.receipt.isBlank()) "" else "\n凭证 ${result.receipt}",
                    )

                    is ReserveOutcome.AlreadyReserved -> Outcome(
                        true,
                        "${rule.display} · 已有预约",
                        "${result.reservation.location}\n${result.reservation.date} " +
                            "${result.reservation.begin}-${result.reservation.end}",
                    )
                }
            } catch (e: AuthExpiredException) {
                progress("登录会话失效，重新登录后继续")
                val refreshed = ops.login(store.account(rule.accountId) ?: throw e, force = true)
                store.updateSession(refreshed.id, refreshed.token, refreshed.userId)
            } catch (e: ManualActionRequired) {
                throw e
            } catch (e: SeatUnavailableException) {
                lastError = e.message ?: "暂无满足条件的座位"
            } catch (e: BookingUncertainException) {
                lastError = e.message ?: "提交结果不确定"
            } catch (e: SeatException) {
                lastError = e.message ?: "请求失败"
            }

            if (System.currentTimeMillis() >= deadline) {
                return Outcome(
                    false,
                    "${rule.display} · 未成功",
                    "${lastError.ifBlank { "超过重试时间窗口" }}（共 $attempt 次尝试）",
                )
            }
            delay(rule.intervalSec.coerceAtLeast(1) * 1000L)
        }
    }

    private suspend fun doCancel(rule: Rule, ops: SeatOps): Outcome {
        val result = ops.cancelAll()
        val detail = buildString {
            if (result.cancelled.isNotEmpty()) {
                append("已取消 ${result.cancelled.size} 条：")
                append(result.cancelled.joinToString("；") { "${it.date} 座位${it.seatId}" })
            }
            if (result.failed.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("失败 ${result.failed.size} 条：")
                append(result.failed.joinToString("；") { "${it.first.date} ${it.second}" })
            }
            if (result.skipped.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("跳过 ${result.skipped.size} 条（状态未知，未自动取消）")
            }
            if (isEmpty()) append("当前没有进行中的预约")
        }
        return Outcome(result.failed.isEmpty(), "${rule.display} · 取消完成", detail)
    }

    private companion object {
        /** 只保留最近若干条过程信息，避免一次任务把日志撑爆。 */
        const val MAX_NOTES = 40
    }
}
