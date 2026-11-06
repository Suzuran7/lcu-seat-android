package cn.edu.lcu.libseat.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.lcu.libseat.SeatApp
import cn.edu.lcu.libseat.core.Account
import cn.edu.lcu.libseat.core.CaptchaSolver
import cn.edu.lcu.libseat.core.FilterInfo
import cn.edu.lcu.libseat.core.LayoutCell
import cn.edu.lcu.libseat.core.LogEntry
import cn.edu.lcu.libseat.core.Reservation
import cn.edu.lcu.libseat.core.RoomInfo
import cn.edu.lcu.libseat.core.RoomLayout
import cn.edu.lcu.libseat.core.Rule
import cn.edu.lcu.libseat.core.SeatApi
import cn.edu.lcu.libseat.core.SeatException
import cn.edu.lcu.libseat.core.SeatItem
import cn.edu.lcu.libseat.core.SeatOps
import cn.edu.lcu.libseat.core.SeatStore
import cn.edu.lcu.libseat.core.TapPoint
import cn.edu.lcu.libseat.core.TimeKit
import cn.edu.lcu.libseat.schedule.AlarmScheduler
import cn.edu.lcu.libseat.service.TaskService
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class CaptchaUi(
    val token: String,
    val background: Bitmap,
    val word: Bitmap,
    val count: Int,
    val seat: SeatItem,
    val date: String,
    val start: String,
    val end: String,
    val points: List<TapPoint> = emptyList(),
)

/** 手动输入登录验证码的界面状态。 */
data class LoginCaptchaUi(
    val accountId: String,
    val accountLabel: String,
    val captchaId: String,
    val image: Bitmap,
    val error: String = "",
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store: SeatStore = (app as SeatApp).store
    private val scheduler = AlarmScheduler(app)

    var accounts by mutableStateOf<List<Account>>(store.accounts())
        private set
    var rules by mutableStateOf<List<Rule>>(store.rules())
        private set
    var logs by mutableStateOf<List<LogEntry>>(store.logs())
        private set
    var guardEnabled by mutableStateOf(store.guardEnabled)
        private set

    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    // 座位图
    var filters by mutableStateOf<FilterInfo?>(null)
        private set
    var rooms by mutableStateOf<List<RoomInfo>>(emptyList())
        private set
    var layout by mutableStateOf<RoomLayout?>(null)
        private set
    var buildingId by mutableStateOf<Int?>(null)
        private set
    var floor by mutableStateOf("")
        private set
    var roomId by mutableStateOf<Int?>(null)
        private set
    var roomName by mutableStateOf("")
        private set
    var windowStart by mutableStateOf("09:00")
    var windowEnd by mutableStateOf("22:00")
    var seatLoading by mutableStateOf(false)
        private set
    var selectedSeat by mutableStateOf<SeatItem?>(null)
        private set
    var selectedSeatNote by mutableStateOf("")
        private set
    var accountForSeatMap by mutableStateOf<String?>(null)
        private set

    /** 从座位图为某个任务挑选座位时使用。 */
    var pendingRuleId by mutableStateOf<String?>(null)
        private set
    var pickedSeats by mutableStateOf<List<Pair<Int, String>>>(emptyList())
        private set

    var captcha by mutableStateOf<CaptchaUi?>(null)
        private set

    /** 手动输入登录验证码：自动识别失败时的兜底，没有它登录会直接卡死。 */
    var loginCaptcha by mutableStateOf<LoginCaptchaUi?>(null)
        private set
    var loginCaptchaAnswer by mutableStateOf("")

    private var loginApi: SeatApi? = null

    private var cachedApi: SeatApi? = null
    private var cachedAccountId: String? = null

    init {
        accountForSeatMap = store.lastActiveAccountId
            .ifBlank { accounts.firstOrNull()?.id.orEmpty() }
            .takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------ 基础设施

    private fun fail(e: Throwable) {
        message = e.message ?: e.javaClass.simpleName
    }

    fun clearMessage() {
        message = null
    }

    private fun refreshAccounts() {
        accounts = store.accounts()
    }

    private fun refreshRules() {
        rules = store.rules()
    }

    fun refreshLogs() {
        logs = store.logs()
    }

    private suspend fun ensureApi(accountId: String): SeatApi {
        cachedApi?.let { api ->
            if (cachedAccountId == accountId && api.token.isNotBlank()) return api
        }
        val api = SeatApi()
        api.bootstrap()
        val account = store.account(accountId) ?: throw SeatException("账号不存在")
        val ops = SeatOps(api, CaptchaSolver())
        val updated = ops.login(account)
        store.updateSession(updated.id, updated.token, updated.userId)
        cachedApi = api
        cachedAccountId = accountId
        refreshAccounts()
        return api
    }

    // ------------------------------------------------------------ 账号操作

    fun saveAccount(account: Account) {
        viewModelScope.launch {
            try {
                val saved = store.upsertAccount(account)
                refreshAccounts()
                if (accountForSeatMap == null) setSeatMapAccount(saved.id)
                message = "已保存账号 ${saved.display}"
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    fun removeAccount(id: String) {
        viewModelScope.launch {
            store.deleteAccount(id)
            refreshAccounts()
            refreshRules()
            if (cachedAccountId == id) {
                cachedApi = null
                cachedAccountId = null
            }
            if (accountForSeatMap == id) {
                accountForSeatMap = accounts.firstOrNull()?.id
            }
            message = "已删除账号"
        }
    }

    fun setSeatMapAccount(id: String) {
        accountForSeatMap = id
        store.lastActiveAccountId = id
        cachedApi = null
        cachedAccountId = null
        filters = null
        layout = null
    }

    fun loginAccount(accountId: String) {
        viewModelScope.launch {
            busy = "正在登录…"
            val notes = mutableListOf<String>()
            var label = accountId
            try {
                val account = store.account(accountId) ?: throw SeatException("账号不存在")
                label = account.display
                val api = SeatApi()
                api.bootstrap()
                val ops = SeatOps(api, CaptchaSolver())
                // 把验证码识别的中间结果收起来，失败时写进执行记录，方便排查。
                ops.onLog = { line -> notes += line }
                val updated = ops.login(account, force = true)
                store.updateSession(updated.id, updated.token, updated.userId)
                cachedApi = api
                cachedAccountId = accountId
                refreshAccounts()
                message = "登录成功：${updated.username}"
            } catch (e: Throwable) {
                store.addLog(
                    LogEntry(
                        time = System.currentTimeMillis(),
                        ruleName = "登录测试",
                        account = label,
                        ok = false,
                        message = (e.message ?: "登录失败") +
                            if (notes.isEmpty()) "" else "\n" + notes.takeLast(20).joinToString("\n"),
                    )
                )
                refreshLogs()
                fail(e)
                // 验证码相关问题顺手把手动输入框打开，用户填 4 位就能登录。
                openLoginCaptcha(accountId)
            } finally {
                busy = null
            }
        }
    }

    // ------------------------------------------------------ 手动登录验证码

    fun openLoginCaptcha(accountId: String) {
        viewModelScope.launch {
            try {
                // 换账号就换一个新客户端，避免沿用上一个账号的会话。
                loginApi = null
                loginCaptchaAnswer = ""
                loginCaptcha = buildLoginCaptcha(accountId, "")
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    fun refreshLoginCaptcha() {
        val current = loginCaptcha ?: return
        viewModelScope.launch {
            try {
                loginCaptcha = buildLoginCaptcha(current.accountId, "")
                loginCaptchaAnswer = ""
            } catch (e: Throwable) {
                loginCaptcha = current.copy(error = e.message ?: "换图失败")
            }
        }
    }

    fun submitLoginCaptcha() {
        val current = loginCaptcha ?: return
        val answer = loginCaptchaAnswer.trim().lowercase()
        if (!CaptchaSolver.isValidLoginCode(answer)) {
            loginCaptcha = current.copy(error = "验证码必须是 4 位小写字母或数字")
            return
        }
        viewModelScope.launch {
            busy = "正在登录…"
            try {
                val account = store.account(current.accountId) ?: throw SeatException("账号不存在")
                val api = loginApi ?: SeatApi().also { it.bootstrap(); loginApi = it }
                Log.i("LCUSeat", "手动提交验证码 ${current.captchaId} answer=$answer")
                api.login(account.username, account.password, current.captchaId, answer)
                val profile = api.profile()
                store.updateSession(account.id, api.token, profile.optString("id"))
                cachedApi = api
                cachedAccountId = account.id
                refreshAccounts()
                loginCaptcha = null
                loginCaptchaAnswer = ""
                message = "登录成功：${profile.optString("username")}"
            } catch (e: Throwable) {
                val reason = e.message ?: "登录失败"
                val code = (e as? SeatException)?.code.orEmpty()
                Log.i("LCUSeat", "手动登录失败 code=$code reason=$reason")
                if (code == "13") {
                    // 验证码这一关已经过了，问题在账号密码：不再换图，让用户去改密码。
                    loginCaptcha = current.copy(error = reason)
                } else {
                    // 验证码只对当前这张图有效，失败立刻换新图。
                    loginCaptchaAnswer = ""
                    loginCaptcha = runCatching { buildLoginCaptcha(current.accountId, reason) }
                        .getOrElse { current.copy(error = reason) }
                }
            } finally {
                busy = null
            }
        }
    }

    fun dismissLoginCaptcha() {
        loginCaptcha = null
        loginCaptchaAnswer = ""
    }

    private suspend fun buildLoginCaptcha(accountId: String, error: String): LoginCaptchaUi {
        val account = store.account(accountId) ?: throw SeatException("账号不存在")
        val api = loginApi ?: SeatApi().also {
            it.bootstrap()
            loginApi = it
        }
        val challenge = api.createLoginCaptcha()
        val data = challenge.optJSONObject("data")
        val captchaId = challenge.optString("captchaId")
            .ifBlank { data?.optString("captchaId").orEmpty() }
        val image = challenge.optString("captchaImage")
            .ifBlank { data?.optString("captchaImage").orEmpty() }
        val bitmap = CaptchaSolver.decodeDataUrl(image)
            ?: throw SeatException("验证码图片无效")
        if (captchaId.isBlank()) throw SeatException("验证码数据不完整")
        return LoginCaptchaUi(
            accountId = accountId,
            accountLabel = account.display,
            captchaId = captchaId,
            image = bitmap,
            error = error,
        )
    }

    fun queryReservations(accountId: String) {
        viewModelScope.launch {
            busy = "正在查询预约…"
            try {
                val api = ensureApi(accountId)
                val rows = SeatApi.parseReservations(api.reservations())
                    .filter { Reservation.isActive(it.status) }
                message = if (rows.isEmpty()) {
                    "该账号当前没有进行中的预约"
                } else {
                    rows.joinToString("\n") {
                        "${it.date} ${it.begin}-${it.end} ${it.location}（${it.status}）"
                    }
                }
                refreshLogs()
            } catch (e: Throwable) {
                fail(e)
            } finally {
                busy = null
            }
        }
    }

    fun cancelReservations(accountId: String) {
        viewModelScope.launch {
            busy = "正在取消预约…"
            try {
                val api = ensureApi(accountId)
                val result = SeatOps(api, CaptchaSolver()).cancelAll()
                message = buildString {
                    append("已取消 ${result.cancelled.size} 条预约")
                    if (result.failed.isNotEmpty()) append("，失败 ${result.failed.size} 条")
                    if (result.skipped.isNotEmpty()) append("，跳过 ${result.skipped.size} 条")
                }
                refreshLogs()
            } catch (e: Throwable) {
                fail(e)
            } finally {
                busy = null
            }
        }
    }

    // ------------------------------------------------------------ 任务操作

    fun saveRule(rule: Rule) {
        viewModelScope.launch {
            try {
                val saved = store.upsertRule(rule)
                refreshRules()
                if (saved.enabled) scheduler.schedule(saved) else scheduler.cancel(saved)
                message = "已保存任务 ${saved.display}，下次 ${TimeKit.nextTriggerText(saved)}"
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    fun removeRule(id: String) {
        viewModelScope.launch {
            store.rule(id)?.let { scheduler.cancel(it) }
            store.deleteRule(id)
            refreshRules()
            message = "已删除任务"
        }
    }

    fun toggleRule(rule: Rule, enabled: Boolean) {
        viewModelScope.launch {
            val saved = store.upsertRule(rule.copy(enabled = enabled))
            refreshRules()
            if (enabled) scheduler.schedule(saved) else scheduler.cancel(saved)
            message = if (enabled) "已启用 ${saved.display}" else "已停用 ${saved.display}"
        }
    }

    fun runRuleNow(id: String) {
        val intent = Intent(getApplication(), TaskService::class.java).apply {
            action = TaskService.ACTION_RUN_RULE
            putExtra(TaskService.EXTRA_RULE_ID, id)
        }
        runCatching { ContextCompat.startForegroundService(getApplication(), intent) }
            .onSuccess { message = "任务已提交，进度见通知栏" }
            .onFailure { message = "启动失败：${it.message}" }
    }

    fun setGuard(enabled: Boolean) {
        val intent = Intent(getApplication(), TaskService::class.java).apply {
            action = if (enabled) TaskService.ACTION_START_GUARD else TaskService.ACTION_STOP_GUARD
        }
        runCatching {
            if (enabled) ContextCompat.startForegroundService(getApplication(), intent)
            else getApplication<Application>().startService(intent)
        }
        store.guardEnabled = enabled
        guardEnabled = enabled
        message = if (enabled) "守护模式已开启，CPU 保持唤醒" else "守护模式已关闭"
    }

    fun clearLogs() {
        store.clearLogs()
        refreshLogs()
        message = "已清空执行记录"
    }

    fun canScheduleExactAlarms(): Boolean = scheduler.canScheduleExact()

    // ------------------------------------------------------------ 座位图

    fun setWindow(start: String, end: String) {
        windowStart = start
        windowEnd = end
    }

    fun selectRoom(room: RoomInfo) {
        roomId = room.id
        roomName = room.name
        floor = room.floor
        buildingId = room.buildingId
        viewModelScope.launch { loadLayoutInternal() }
    }

    fun loadSeatMap(withFilters: Boolean = true) {
        viewModelScope.launch {
            seatLoading = true
            selectedSeat = null
            try {
                val accountId = accountForSeatMap ?: accounts.firstOrNull()?.id
                    ?: throw SeatException("请先到「账号」页添加账号")
                accountForSeatMap = accountId
                val api = ensureApi(accountId)
                if (withFilters || filters == null) {
                    val info = SeatApi.parseFilters(api.filters())
                    filters = info
                    rooms = info.rooms
                    if (rooms.isEmpty()) throw SeatException("服务器没有返回房间")
                    val current = rooms.firstOrNull { it.id == roomId }
                    val target = current
                        ?: rooms.firstOrNull { it.buildingId == buildingId }
                        ?: rooms.first()
                    roomId = target.id
                    roomName = target.name
                    floor = target.floor
                    buildingId = target.buildingId
                }
                loadLayoutInternal()
            } catch (e: Throwable) {
                fail(e)
            } finally {
                seatLoading = false
            }
        }
    }

    private suspend fun loadLayoutInternal() {
        val accountId = accountForSeatMap ?: return
        val api = ensureApi(accountId)
        val id = roomId ?: return
        val date = TimeKit.todayIso()
        val result = SeatApi.parseLayout(api.layout(id, date))
        layout = result
        if (roomName.isBlank()) roomName = result.roomName
        seatLoading = false
    }

    fun selectSeat(seat: SeatItem) {
        selectedSeat = seat
        selectedSeatNote = "正在校验 ${TimeKit.todayIso()} $windowStart-$windowEnd …"
        viewModelScope.launch {
            try {
                if (!TimeKit.isValidHhmm(windowStart) && !windowStart.equals("now", true)) {
                    selectedSeatNote = "开始时间格式应为 HH:mm 或 now"
                    return@launch
                }
                if (!TimeKit.isValidHhmm(windowEnd)) {
                    selectedSeatNote = "结束时间格式应为 HH:mm"
                    return@launch
                }
                val accountId = accountForSeatMap ?: return@launch
                val api = ensureApi(accountId)
                val date = TimeKit.todayIso()
                val start = if (windowStart.equals("now", true)) "now" else TimeKit.toMinuteId(windowStart)
                val end = TimeKit.toMinuteId(windowEnd)
                val ok = SeatOps(api, CaptchaSolver()).checkWindow(seat.id, date, start, end)
                if (selectedSeat?.id == seat.id) {
                    selectedSeatNote = if (ok) {
                        "✅ 该时段可预约：$date $windowStart-$windowEnd"
                    } else {
                        "⚠️ 该座位在 $date $windowStart-$windowEnd 不可预约"
                    }
                }
            } catch (e: Throwable) {
                selectedSeatNote = e.message ?: "校验失败"
            }
        }
    }

    fun clearSelectedSeat() {
        selectedSeat = null
        selectedSeatNote = ""
    }

    // ------------------------------------------------------------ 任务挑座位

    fun startPickingForRule(ruleId: String, fallback: Rule? = null) {
        pendingRuleId = ruleId
        // 刚保存的任务可能还没写入存储，用编辑中的草稿兜底。
        val rule = store.rule(ruleId) ?: fallback
        pickedSeats = when {
            rule == null -> emptyList()
            rule.seatIds.isEmpty() -> emptyList()
            else -> rule.seatIds.zip(rule.seatNames)
        }
        if (rule != null && rule.roomId > 0) {
            roomId = rule.roomId
            roomName = rule.roomName
            buildingId = rule.buildingId.takeIf { id -> id > 0 }
            floor = rule.floor
        }
    }

    fun cancelPicking() {
        pendingRuleId = null
        pickedSeats = emptyList()
    }

    fun togglePickSeat(seat: SeatItem) {
        val existing = pickedSeats.firstOrNull { it.first == seat.id }
        pickedSeats = if (existing != null) {
            pickedSeats - existing
        } else {
            pickedSeats + (seat.id to seat.name)
        }
    }

    fun applyPickedToRule() {
        val ruleId = pendingRuleId ?: return
        val rule = store.rule(ruleId) ?: return
        viewModelScope.launch {
            val saved = store.upsertRule(
                rule.copy(
                    seatIds = pickedSeats.map { it.first },
                    seatNames = pickedSeats.map { it.second },
                    roomId = roomId ?: rule.roomId,
                    roomName = roomName.ifBlank { rule.roomName },
                    buildingId = buildingId ?: rule.buildingId,
                    floor = floor.ifBlank { rule.floor },
                )
            )
            refreshRules()
            if (saved.enabled) scheduler.schedule(saved)
            pendingRuleId = null
            message = "已更新任务座位：${pickedSeats.joinToString("、") { it.second }}"
        }
    }

    // ------------------------------------------------------------ 手动预约

    /** 打开手动点选验证码，用于自动识别失败或临时补约。 */
    fun startInteractiveBooking(seat: SeatItem) {
        val accountId = accountForSeatMap ?: accounts.firstOrNull()?.id
        if (accountId == null) {
            message = "请先添加账号"
            return
        }
        viewModelScope.launch {
            busy = "正在获取验证码…"
            try {
                if (!TimeKit.isValidHhmm(windowEnd)) throw SeatException("结束时间格式应为 HH:mm")
                if (!windowStart.equals("now", true) && !TimeKit.isValidHhmm(windowStart)) {
                    throw SeatException("开始时间格式应为 HH:mm 或 now")
                }
                val api = ensureApi(accountId)
                val date = TimeKit.todayIso()
                val start = if (windowStart.equals("now", true)) "now" else TimeKit.toMinuteId(windowStart)
                val end = TimeKit.toMinuteId(windowEnd)

                val active = SeatApi.parseReservations(api.reservations())
                    .filter { Reservation.isActive(it.status) }
                if (active.isNotEmpty()) {
                    throw SeatException("该账号已有进行中的预约：${active.first().location}")
                }

                val settings = api.settings()
                if (!settings.optBoolean("isCaptchaOpen", true)) {
                    submitBooking(api, seat, date, start, end, "")
                    return@launch
                }

                val challenge = api.createBookingCaptcha()
                val background = CaptchaSolver.decodeDataUrl(challenge.optString("image"))
                    ?: throw SeatException("验证码图片无效")
                val word = CaptchaSolver.decodeDataUrl(challenge.optString("wordImage"))
                    ?: throw SeatException("验证码图片无效")
                val count = challenge.optInt("wordCheckCount", 0)
                val token = challenge.optString("token")
                if (count <= 0 || token.isBlank()) throw SeatException("验证码数据不完整")
                captcha = CaptchaUi(
                    token = token,
                    background = background,
                    word = word,
                    count = count,
                    seat = seat,
                    date = date,
                    start = start,
                    end = end,
                )
            } catch (e: Throwable) {
                fail(e)
            } finally {
                busy = null
            }
        }
    }

    fun addTap(x: Int, y: Int) {
        val current = captcha ?: return
        if (current.points.size >= current.count) return
        captcha = current.copy(points = current.points + TapPoint(x, y))
    }

    fun undoLastTap() {
        val current = captcha ?: return
        if (current.points.isEmpty()) return
        captcha = current.copy(points = current.points.dropLast(1))
    }

    fun dismissCaptcha() {
        captcha = null
    }

    fun submitCaptcha() {
        val current = captcha ?: return
        if (current.points.size != current.count) {
            message = "提示词共 ${current.count} 个，请在图片上依次点击"
            return
        }
        viewModelScope.launch {
            busy = "正在提交预约…"
            try {
                val accountId = accountForSeatMap ?: throw SeatException("请先选择账号")
                val api = ensureApi(accountId)
                if (!api.checkCaptcha(current.token, current.points)) {
                    message = "验证码未通过，请重新获取图片后再试"
                    captcha = null
                    return@launch
                }
                submitBooking(api, current.seat, current.date, current.start, current.end, current.token)
                captcha = null
            } catch (e: Throwable) {
                fail(e)
            } finally {
                busy = null
            }
        }
    }

    private suspend fun submitBooking(
        api: SeatApi,
        seat: SeatItem,
        date: String,
        start: String,
        end: String,
        authId: String,
    ) {
        val json = api.book(seat.id, date, start, end, authId)
        val data = json.optJSONObject("data")
        message = "预约成功：${data?.optString("location") ?: seat.name}"
        selectedSeat = null
        loadLayoutInternal()
        refreshLogs()
    }

    /** 座位图上的点击坐标换算为 330 像素宽基准。 */
    fun tapToServerPoint(x: Float, y: Float, displayWidth: Int): Pair<Int, Int> {
        val scale = 330f / displayWidth.coerceAtLeast(1)
        return (x * scale).roundToInt() to (y * scale).roundToInt()
    }

    fun cellOf(index: Int): LayoutCell? = layout?.cells?.getOrNull(index)
}
