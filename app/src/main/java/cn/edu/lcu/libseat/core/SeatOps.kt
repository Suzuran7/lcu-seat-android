package cn.edu.lcu.libseat.core

import kotlinx.coroutines.delay
import android.util.Log
import org.json.JSONObject

/** 需要用户介入（例如验证码只能人工完成）。 */
class ManualActionRequired(message: String) : SeatException(message)

sealed interface ReserveOutcome {
    data class Reserved(val choice: SeatChoice, val receipt: String) : ReserveOutcome
    data class AlreadyReserved(val reservation: Reservation) : ReserveOutcome
}

class CancelOutcome(
    val cancelled: List<Reservation> = emptyList(),
    val failed: List<Pair<Reservation, String>> = emptyList(),
    val skipped: List<Reservation> = emptyList(),
)

/**
 * 预约业务：登录、选座、验证码、提交、取消。
 *
 * 逻辑与 Python 版 booking.py 一致，包括提交前复查和“结果不确定时不重复提交”。
 */
class SeatOps(val api: SeatApi, val solver: CaptchaSolver) {

    var onLog: ((String) -> Unit)? = null

    init {
        // 识别细节写进同一路日志，真机上能看到「读到了什么、卡在哪一步」。
        solver.onDiagnostic = { message -> log(message) }
    }

    private fun log(message: String) {
        // 同时打到 logcat，真机调试时可直接 adb logcat -s LCUSeat 观察。
        Log.i(TAG, message)
        onLog?.invoke(message)
    }

    // ------------------------------------------------------------ 登录

    suspend fun login(account: Account, force: Boolean = false): Account {
        if (!force && account.token.isNotBlank()) {
            api.setSession(account.token, account.userId, account.username)
            val profile = runCatching { api.profile() }.getOrNull()
            if (profile != null && profile.optString("username") == account.username) {
                log("复用已保存的登录会话")
                return account.copy(userId = api.userId)
            }
            api.clearSession()
            log("已保存的会话不可用，重新登录")
        }

        var lastError: SeatException? = null
        var guessSubmitted = false

        for (attempt in 0 until MAX_LOGIN_ATTEMPTS) {
            val challenge = api.createLoginCaptcha()
            val data = challenge.optJSONObject("data")
            val captchaId = challenge.optString("captchaId")
                .ifBlank { data?.optString("captchaId").orEmpty() }
            if (captchaId.isBlank()) throw SeatException("登录验证码响应缺少 captchaId")
            val image = challenge.optString("captchaImage")
                .ifBlank { data?.optString("captchaImage").orEmpty() }

            val bitmap = CaptchaSolver.decodeDataUrl(image)
            val recognized = bitmap?.let { solver.solveLogin(it) }
            if (bitmap != null && recognized == null) {
                // 识别不出来就换一张再试，站点允许 3 次登录机会，这里用满但不会盲猜。
                if (attempt < MAX_LOGIN_ATTEMPTS - 1) {
                    log("登录验证码识别失败，重新获取验证码后重试（${attempt + 1}/$MAX_LOGIN_ATTEMPTS）")
                    continue
                }
                log("登录验证码连续 $MAX_LOGIN_ATTEMPTS 次识别失败，改为手动输入")
                throw ManualActionRequired(
                    "登录验证码连续 $MAX_LOGIN_ATTEMPTS 次自动识别失败，请手动输入图片中的 4 位字符"
                )
            }
            if (recognized != null && !recognized.confident) {
                if (guessSubmitted) {
                    if (attempt < MAX_LOGIN_ATTEMPTS - 1) {
                        log("本次只有低置信度结果，换一张验证码重试（${attempt + 1}/$MAX_LOGIN_ATTEMPTS）")
                        continue
                    }
                    log("验证码始终没有多轮一致结果，停止自动尝试以免消耗登录次数")
                    throw ManualActionRequired("验证码自动识别不可靠，请手动输入图片中的 4 位字符")
                }
                guessSubmitted = true
                log("识别结果 ${recognized.text} 置信度低，只提交这一次")
            } else if (recognized != null) {
                log("登录验证码识别为 ${recognized.text}（多轮一致），正在登录")
            }
            val answer = recognized?.text.orEmpty()

            try {
                api.login(account.username, account.password, captchaId, answer)
                val profile = api.profile()
                if (profile.optString("username") != account.username) {
                    throw SeatException("登录返回的账号与配置不一致")
                }
                log("登录成功")
                return account.copy(
                    token = api.token,
                    userId = api.userId,
                    lastLoginAt = System.currentTimeMillis(),
                )
            } catch (e: AuthExpiredException) {
                lastError = e
            } catch (e: SeatException) {
                lastError = e
                val reason = e.message ?: ""
                // code=13 实测是「验证码已通过、账号或密码不通过」，重试验证码没有意义，
                // 而且每次都会消耗一次登录机会，所以立即停止并指向真正的问题。
                if (e.code == "13") {
                    throw SeatException(
                        "账号或密码不正确（服务器：$reason），请检查账号页里的密码是否填对。",
                        "13",
                    )
                }
                // 服务端会给出剩余次数（如「还有98次机会」），继续猜可能把额度耗光。
                if (reason.contains("机会") || reason.contains("频繁") || reason.contains("锁定")) {
                    throw ManualActionRequired("登录受限：$reason，请手动输入验证码")
                }
                if (answer.isEmpty()) {
                    throw ManualActionRequired("登录验证码自动识别失败，请手动输入图片中的 4 位字符")
                }
                if (attempt < 2) log("登录失败：$reason，将换一张验证码重试")
            }
        }
        val last = lastError ?: SeatException("登录失败")
        // 连续三次都卡在验证码上，说明是识别问题而不是账号问题，直接引导人工登录。
        if (last.message?.contains("验证码") == true) {
            throw ManualActionRequired(
                "登录验证码连续 3 次未通过（${last.message}），请打开 App 点一次「登录测试」重试"
            )
        }
        throw last
    }

    // ------------------------------------------------------------ 查询

    suspend fun activeReservations(): List<Reservation> =
        SeatApi.parseReservations(api.reservations()).filter {
            Reservation.isActive(it.status)
        }

    suspend fun layout(roomId: Int, date: String): RoomLayout =
        SeatApi.parseLayout(api.layout(roomId, date))

    /** 该座位在指定时间窗内是否可预约。 */
    suspend fun checkWindow(seatId: Int, date: String, start: String, end: String): Boolean {
        val starts = SeatApi.parseTimes(api.startTimes(seatId, date)).map { it.first }
        if (start !in starts) return false
        val ends = SeatApi.parseTimes(api.endTimes(seatId, date, start)).map { it.first }
        return end in ends
    }

    // ------------------------------------------------------------ 取消

    suspend fun cancelAll(): CancelOutcome {
        val rows = activeReservations()
        if (rows.isEmpty()) {
            log("当前没有进行中的预约")
            return CancelOutcome()
        }
        val cancelled = mutableListOf<Reservation>()
        val failed = mutableListOf<Pair<Reservation, String>>()
        val skipped = mutableListOf<Reservation>()
        for (row in rows) {
            if (row.status !in Reservation.ACTIVE_STATUSES) {
                skipped.add(row)
                continue
            }
            try {
                api.cancel(row.id)
                cancelled.add(row)
                log("已取消 ${row.date} 座位 ${row.seatId} 的预约（${row.id}）")
            } catch (e: AuthExpiredException) {
                throw e
            } catch (e: SeatException) {
                failed.add(row to (e.message ?: "取消请求失败"))
                log("预约 ${row.id} 取消失败：${e.message}")
            }
        }
        return CancelOutcome(cancelled = cancelled, failed = failed, skipped = skipped)
    }

    // ------------------------------------------------------------ 预约

    /** 提交一次预约；失败抛出异常，由上层决定是否重试。 */
    suspend fun reserveOnce(
        rule: Rule,
        date: String,
        start: String,
        end: String,
    ): ReserveOutcome {
        val existing = activeReservations()
        matchingReservation(existing, rule, date, start, end)?.let {
            return ReserveOutcome.AlreadyReserved(it)
        }
        if (existing.isNotEmpty()) {
            throw SeatException("账号已有进行中的预约，已停止本次提交")
        }

        val choice = chooseSeat(rule, date, start, end)
        log("已选择 ${choice.describe}")

        var authId = ""
        if (api.settings().optBoolean("isCaptchaOpen", true)) {
            authId = solveBookingCaptcha()
        }

        // 验证码可能耗时，提交前复查账号状态与座位时段。
        val again = activeReservations()
        if (again.isNotEmpty()) {
            matchingReservation(again, rule, date, start, end)?.let {
                return ReserveOutcome.AlreadyReserved(it)
            }
            throw SeatException("验证码处理期间账号出现了新的预约，已停止提交")
        }
        val stillFree = chooseSeat(rule, date, start, end, onlySeatId = choice.seatId)
        require(stillFree.seatId == choice.seatId)

        return try {
            val json = api.book(choice.seatId, date, start, end, authId)
            val data = json.optJSONObject("data")
            if (data == null || data.optLong("id", 0L) == 0L) {
                throw BookingUncertainException("预约响应缺少预约编号，需要查询记录确认")
            }
            log("预约成功：${data.optString("onDate")} ${data.optString("begin")}-${data.optString("end")}")
            ReserveOutcome.Reserved(choice, data.optString("receipt"))
        } catch (e: BookingUncertainException) {
            // 结果不确定时绝不重复提交，只查询记录。
            repeat(3) {
                delay(2000)
                val found = runCatching { activeReservations() }.getOrNull()
                    ?.firstOrNull { it.seatId == choice.seatId && it.date == date }
                if (found != null) return ReserveOutcome.AlreadyReserved(found)
            }
            throw e
        }
    }

    private fun matchingReservation(
        rows: List<Reservation>,
        rule: Rule,
        date: String,
        start: String,
        end: String,
    ): Reservation? {
        val endId = TimeKit.toMinuteId(end)
        for (row in rows) {
            if (rule.seatIds.isNotEmpty() && row.seatId !in rule.seatIds) continue
            if (row.date != date) continue
            val rowEnd = runCatching { TimeKit.toMinuteId(row.end) }.getOrDefault("")
            if (rowEnd != endId) continue
            if (start != "now") {
                val rowBegin = runCatching { TimeKit.toMinuteId(row.begin) }.getOrDefault("")
                if (rowBegin != start) continue
            }
            return row
        }
        return null
    }

    private suspend fun chooseSeat(
        rule: Rule,
        date: String,
        start: String,
        end: String,
        onlySeatId: Int? = null,
    ): SeatChoice {
        val filters = api.filters()
        val dates = filters.optJSONArray("dates")?.let { array ->
            (0 until array.length()).map { array.optString(it) }
        } ?: emptyList()
        if (date !in dates) {
            throw SeatUnavailableException("服务器尚未开放 $date 的预约")
        }

        val roomLayout = layout(rule.roomId, date)
        val seats = roomLayout.seats.associateBy { it.id }
        if (onlySeatId != null) {
            val seat = seats[onlySeatId]
                ?: throw SeatUnavailableException("座位 $onlySeatId 已不在房间布局中")
            if (!checkWindow(seat.id, date, start, end)) {
                throw SeatUnavailableException("提交前复查发现座位时段已变化，本轮重新开始")
            }
            return buildChoice(rule, roomLayout, seat, date, start, end)
        }

        rule.seatIds.forEach {
            if (it !in seats) throw SeatException("座位 $it 不属于房间 ${rule.roomId}")
        }

        // 与网页一致：先给出房间整体可预约情况，便于事后核对当时的座位状态。
        val freeCount = roomLayout.seats.count { it.usable && it.status == "FREE" }
        log("房间「${roomLayout.roomName}」共 ${roomLayout.seats.size} 个座位，$date 空闲 $freeCount 个")

        val ordered = ArrayList<SeatItem>(seats.size)
        rule.seatIds.mapNotNullTo(ordered) { seats[it] }
        if (rule.autoFallback) {
            seats.values
                .filter { it.id !in rule.seatIds }
                .sortedWith(compareBy({ it.name }, { it.id }))
                .forEach { ordered.add(it) }
        }
        if (ordered.isEmpty()) throw SeatUnavailableException("没有可尝试的座位")

        var checked = 0
        for (seat in ordered) {
            if (!seat.usable) continue
            if (++checked > MAX_SEAT_CHECKS) break
            if (checkWindow(seat.id, date, start, end)) {
                return buildChoice(rule, roomLayout, seat, date, start, end)
            }
            delay(120)
        }
        throw SeatUnavailableException(
            "没有满足 ${TimeKit.fromMinuteId(start)}-${TimeKit.fromMinuteId(end)} 时段的座位"
        )
    }

    private fun buildChoice(
        rule: Rule,
        layout: RoomLayout,
        seat: SeatItem,
        date: String,
        start: String,
        end: String,
    ) = SeatChoice(
        seatId = seat.id,
        seatName = seat.name,
        roomId = rule.roomId,
        roomName = rule.roomName.ifBlank { layout.roomName },
        date = date,
        start = start,
        end = end,
    )

    /** 点选验证码：自动识别 5 次，失败则要求人工在 App 内完成。 */
    private suspend fun solveBookingCaptcha(): String {
        repeat(5) { attempt ->
            val challenge = api.createBookingCaptcha()
            val token = challenge.optString("token")
            val count = challenge.optInt("wordCheckCount", 0)
            val background = CaptchaSolver.decodeDataUrl(challenge.optString("image"))
            val word = CaptchaSolver.decodeDataUrl(challenge.optString("wordImage"))
            if (challenge.optString("status") != "OK" || token.isBlank() ||
                background == null || word == null || count <= 0
            ) {
                throw SeatException("服务器未返回完整的预约验证码")
            }
            log("预约验证码第 ${attempt + 1} 次识别（需点 $count 个字）")
            val points = solver.solveClick(background, word, count)
            if (points != null && api.checkCaptcha(token, points)) {
                log("预约验证码已通过自动识别（${points.joinToString(" ") { "${it.x},${it.y}" }}）")
                return token
            }
            if (points != null) log("预约验证码未被服务器接受，换一张重试")
            delay(800)
        }
        throw ManualActionRequired("预约验证码自动识别未通过，请在座位图中手动点选完成预约")
    }

    companion object {
        private const val TAG = "LCUSeat"
        private const val MAX_SEAT_CHECKS = 80

        /** 登录验证码最多自动尝试 3 次（每次都会重新取一张图）。 */
        private const val MAX_LOGIN_ATTEMPTS = 3
    }
}
