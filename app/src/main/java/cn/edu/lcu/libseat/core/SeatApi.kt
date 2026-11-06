package cn.edu.lcu.libseat.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 可直接展示给用户的错误。 */
open class SeatException(message: String, val code: String = "") : Exception(message)

/** 登录会话失效，可重新登录后重试。 */
class AuthExpiredException(message: String = "登录已失效，请重新登录") : SeatException(message, "12")

/** 预约提交结果不确定：必须查询预约记录，不能重复提交。 */
class BookingUncertainException(message: String) : SeatException(message)

/** 当前没有可预约的座位或时段。 */
class SeatUnavailableException(message: String) : SeatException(message)

/**
 * 与 lib-seat.lcu.edu.cn 交互。
 *
 * 与 Python 版 SeatClient 对应：同一个签名方案、同一批接口、同一套结果判定。
 * 唯一区别是网络层用 OkHttp，且全部方法都是挂起函数，方便放进前台服务。
 */
class SeatApi {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var hmacKey: ByteArray? = null
    private var bootstrapped = false

    /** 当前使用的 UA；bootstrap 时会挑一个站点放行的值。 */
    private var userAgent: String = DEFAULT_USER_AGENT

    var token: String = ""
        private set
    var userId: String = ""
        private set
    var username: String = ""
        private set

    fun setSession(token: String, userId: String = "", username: String = "") {
        this.token = token
        this.userId = userId
        this.username = username
    }

    fun clearSession() {
        token = ""
        userId = ""
        username = ""
    }

    /** 读取站点公开配置：签名开关、加密学校代码、统一认证开关。不执行下载的 JavaScript。 */
    suspend fun bootstrap(force: Boolean = false) {
        if (bootstrapped && !force) return
        val source = fetchSiteConfig()
        val signing = Regex("""\bCODE\s*:\s*(true|false)""").find(source)
            ?: throw SeatException("站点配置格式已变化，缺少 CODE 字段")
        val numcode = Regex("""\bNUMCODE\s*:\s*["']([^"']+)["']""").find(source)
        if (Regex("""\bCASLOGIN\s*:\s*true""").containsMatchIn(source)) {
            throw SeatException("站点已开启统一认证，本程序无法直接登录，请在网页登录后导出会话")
        }
        hmacKey = if (signing.groupValues[1] == "true") {
            val value = numcode?.groupValues?.get(1)
                ?: throw SeatException("站点配置缺少 NUMCODE 字段")
            try {
                SeatProtocol.decryptSchoolCode(value)
            } catch (e: Exception) {
                throw SeatException("站点请求签名配置已变化")
            }
        } else {
            null
        }
        bootstrapped = true
    }

    /**
     * 站点会对静态资源按 User-Agent 分流：UA 里带 Android / iPhone 的请求直接返回 404
     * （实测 baseUrl.js 对 `LCUSeat/1.0 (Android)` 与移动版 Chrome UA 均为 404，
     * 桌面 UA 与空 UA 正常）。这里按候选顺序逐个尝试，成功即固定使用该 UA。
     */
    private suspend fun fetchSiteConfig(): String {
        var lastError: SeatException? = null
        for (candidate in USER_AGENT_CANDIDATES) {
            userAgent = candidate
            try {
                Log.i(TAG, "读取站点配置，UA=$candidate")
                return getText(CONFIG_PATH)
            } catch (e: SeatException) {
                lastError = e
            }
        }
        throw SeatException(
            "读取站点配置失败：${lastError?.message ?: "网络不可用"}（已尝试多个 User-Agent）"
        )
    }

    private suspend fun getText(path: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url((BASE_URL + path).toHttpUrl())
            .header("User-Agent", userAgent)
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw SeatException("HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }
        } catch (e: SeatException) {
            throw e
        } catch (e: IOException) {
            throw SeatException("${e.message ?: "网络不可用"}")
        }
    }

    // ------------------------------------------------------------ 底层请求

    private suspend fun call(
        method: String,
        path: String,
        params: Map<String, String> = emptyMap(),
        form: Map<String, String>? = null,
        authenticated: Boolean = true,
        booking: Boolean = false,
    ): JSONObject = withContext(Dispatchers.IO) {
        val urlBuilder = (BASE_URL + path).toHttpUrl().newBuilder()
        params.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        if (authenticated) {
            if (token.isBlank()) throw SeatException("登录会话不存在，请先登录")
            // 与 Python 的 params.setdefault("token", ...) 等价：调用方已给出 token 时不覆盖。
            // 点选验证码校验里的 token 是验证码凭据，若再拼上会话 token 会出现两个同名参数。
            if (!params.containsKey("token")) urlBuilder.addQueryParameter("token", token)
        }

        val requestBody = form?.let { fields ->
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .apply { fields.forEach { (key, value) -> addFormDataPart(key, value) } }
                .build()
        }

        val builder = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "$BASE_URL/libseat/")
            .header("Origin", BASE_URL)
            .header("User-Agent", userAgent)
            .header("loginType", "PC")
        SeatProtocol.signedHeaders(method, hmacKey).forEach { (key, value) -> builder.header(key, value) }
        if (authenticated) builder.header("Authorization", token)

        when (method.uppercase()) {
            "POST" -> builder.post(requestBody ?: ByteArray(0).toRequestBody(null))
            else -> builder.get()
        }

        val response = try {
            Log.d(TAG, "→ ${method.uppercase()} ${urlBuilder.build().encodedPath}")
            http.newCall(builder.build()).execute()
        } catch (e: IOException) {
            if (booking) throw BookingUncertainException("提交预约时连接中断，需要查询预约记录确认")
            throw SeatException("网络请求失败：${e.message ?: e.javaClass.simpleName}")
        }

        response.use { resp ->
            if (resp.code == 401 || resp.code == 403) throw AuthExpiredException()
            if (!resp.isSuccessful) {
                if (booking) throw BookingUncertainException("预约接口返回 HTTP ${resp.code}，需要查询记录")
                throw SeatException("请求返回 HTTP ${resp.code}")
            }
            val text = resp.body?.string().orEmpty()
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                if (booking) throw BookingUncertainException("预约接口返回非 JSON 内容，需要查询记录")
                throw SeatException("服务器返回非 JSON 内容")
            }
            // 仅在已登录请求上把 code=12 当作会话失效；登录接口的 12 是凭据错误，
            // 保留服务器原文（例如“用户名或密码不正确”）更利于排查。
            if (authenticated && json.optString("code") == "12") throw AuthExpiredException()
            json
        }
    }

    private fun JSONObject.requireSuccess(allowBoolean: Boolean = false): JSONObject {
        val status = opt("status")
        val ok = status == "success" || status == "OK" || (allowBoolean && status == true)
        if (!ok) {
            val message = optString("message")
            val code = optString("code")
            val detail = buildString {
                append(message.ifBlank { "服务器拒绝了请求" })
                if (code.isNotBlank()) append("（code=").append(code).append("）")
            }
            throw SeatException(detail, code)
        }
        return this
    }

    // ------------------------------------------------------------ 登录相关

    /**
     * 登录验证码：与 Python 版一样按「原始响应」处理——该接口只返回
     * `captchaId` / `captchaImage`，**没有 status 字段**，不能做统一成功校验。
     */
    suspend fun createLoginCaptcha(): JSONObject =
        call("GET", "/auth/createCaptcha", authenticated = false)

    suspend fun login(
        username: String,
        password: String,
        captchaId: String,
        answer: String,
    ): JSONObject {
        val json = call(
            "GET",
            "/rest/auth",
            authenticated = false,
            params = mapOf(
                "username" to SeatProtocol.encryptCredential(username),
                "password" to SeatProtocol.encryptCredential(password),
                "captchaId" to captchaId,
                "answer" to answer,
            ),
        )
        val data = json.requireSuccess().optJSONObject("data")
            ?: throw SeatException("登录响应缺少数据")
        val newToken = data.optString("token")
        if (newToken.isBlank()) throw SeatException("登录响应缺少 token")
        token = newToken
        return data
    }

    suspend fun profile(): JSONObject {
        val data = call("GET", "/rest/v2/user").requireSuccess().optJSONObject("data")
            ?: throw SeatException("用户资料为空")
        val name = data.optString("username")
        if (name.isBlank() || data.opt("id") == null) {
            throw SeatException("用户资料缺少预约所需字段")
        }
        username = name
        userId = data.optString("id")
        return data
    }

    suspend fun settings(): JSONObject =
        call("GET", "/rest/v2/settings").requireSuccess(allowBoolean = true).optJSONObject("data")
            ?: JSONObject()

    // ------------------------------------------------------------ 目录查询

    suspend fun filters(): JSONObject =
        call("GET", "/rest/v2/free/filters").requireSuccess().optJSONObject("data")
            ?: throw SeatException("开放信息为空")

    suspend fun rooms(buildingId: Int, date: String): JSONArray =
        call(
            "GET",
            "/rest/v2/room/stats2/$buildingId/$date",
            params = mapOf("buildingId" to buildingId.toString(), "date" to date),
        ).requireSuccess().optJSONArray("data") ?: JSONArray()

    suspend fun layout(roomId: Int, date: String): JSONObject =
        call(
            "GET",
            "/rest/v2/room/layoutByDate/$roomId/$date",
            params = mapOf("id" to roomId.toString(), "date" to date),
        ).requireSuccess().optJSONObject("data") ?: throw SeatException("房间布局为空")

    suspend fun startTimes(seatId: Int, date: String): JSONArray {
        val data = call(
            "GET",
            "/rest/v2/startTimesForSeat/$seatId/$date",
            params = mapOf("seatId" to seatId.toString(), "date" to date),
        ).requireSuccess().optJSONObject("data")
        return data?.optJSONArray("startTimes") ?: JSONArray()
    }

    suspend fun endTimes(seatId: Int, date: String, start: String): JSONArray {
        val data = call(
            "GET",
            "/rest/v2/endTimesForSeat/$seatId/$date/$start",
            params = mapOf(
                "id" to seatId.toString(),
                "date" to date,
                "start" to start,
            ),
        ).requireSuccess().optJSONObject("data")
        return data?.optJSONArray("endTimes") ?: JSONArray()
    }

    suspend fun reservations(): JSONArray =
        call("GET", "/rest/v2/user/reservations").requireSuccess().optJSONArray("data") ?: JSONArray()

    suspend fun cancel(reservationId: Long): JSONObject =
        call(
            "GET",
            "/rest/v2/cancel/$reservationId",
            params = mapOf("id" to reservationId.toString()),
        ).requireSuccess()

    // ------------------------------------------------------------ 预约相关

    suspend fun createBookingCaptcha(): JSONObject =
        call(
            "POST",
            "/cap/captcha/" + token,
            params = mapOf("username" to username),
        )

    suspend fun checkCaptcha(captchaToken: String, points: List<TapPoint>): Boolean {
        val json = call(
            "GET",
            "/cap/checkCaptcha",
            params = mapOf(
                "a" to SeatProtocol.encodePoints(points),
                "token" to captchaToken,
                "userId" to userId,
                "username" to username,
            ),
        )
        return json.optString("status") == "OK"
    }

    suspend fun book(
        seatId: Int,
        date: String,
        start: String,
        end: String,
        authId: String,
    ): JSONObject {
        val json = call(
            "POST",
            "/rest/v2/freeBook",
            booking = true,
            form = mapOf(
                "startTime" to if (start == "now") "-1" else start,
                "endTime" to end,
                "seat" to seatId.toString(),
                "date" to date,
                "userId" to userId,
                "username" to username,
                "authid" to authId,
            ),
        )
        return json.requireSuccess()
    }

    companion object {
        private const val TAG = "LCUSeat"
        const val BASE_URL = "https://lib-seat.lcu.edu.cn:8443"
        private const val CONFIG_PATH = "/libseat/static/baseUrl.js"

        /**
         * 站点对静态资源会按 UA 分流：含 Android / iPhone 的 UA 一律 404。
         * 因此默认沿用 Python 版的自定义 UA（实测对 baseUrl.js 与各接口均正常），
         * 失败时再退回桌面浏览器 UA / Python 版原始 UA。
         */
        private const val DEFAULT_USER_AGENT = "LCUSeat/1.0 (Kotlin)"

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val USER_AGENT_CANDIDATES = listOf(
            DEFAULT_USER_AGENT,
            DESKTOP_USER_AGENT,
            "LCUSeat/0.1 (Python)",
        )

        // -------------------------------------------------------- 响应解析

        fun parseFilters(json: JSONObject): FilterInfo {
            val dates = json.optJSONArray("dates")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf { value -> value.isNotBlank() } }
            } ?: emptyList()

            val buildings = json.optJSONArray("buildings")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val row = array.optJSONArray(index) ?: return@mapNotNull null
                    val id = row.optInt(0)
                    val name = row.optString(1)
                    if (id <= 0) null else id to name
                }
            } ?: emptyList()

            val buildingNames = buildings.toMap()
            val rooms = json.optJSONArray("rooms")?.let { parseRooms(it, buildingNames) } ?: emptyList()
            return FilterInfo(dates = dates, buildings = buildings, rooms = rooms)
        }

        fun parseRooms(array: JSONArray, buildingNames: Map<Int, String> = emptyMap()): List<RoomInfo> =
            (0 until array.length()).mapNotNull { index ->
                val row = array.optJSONArray(index) ?: return@mapNotNull null
                val id = row.optInt(0)
                if (id <= 0) return@mapNotNull null
                val buildingId = row.optInt(2)
                RoomInfo(
                    id = id,
                    name = row.optString(1),
                    buildingId = buildingId,
                    buildingName = buildingNames[buildingId] ?: "",
                    floor = row.optString(3),
                )
            }

        /**
         * 解析房间布局。
         *
         * 实测服务器给 `layout` 的键是 `行号 * 1000 + 列号`（第 0 行 0..cols-1，第 1 行 1000..；
         * 早期版本曾用 0..rows*cols-1 的连续下标，HAR 里那间房就是连续的）。因此按优先级尝试三种
         * 取键方式，哪种能把格子填满就用哪种，避免又因为键规则变化导致「0 个座位」。
         */
        fun parseLayout(json: JSONObject): RoomLayout {
            val roomId = json.optInt("id")
            val roomName = json.optString("name")
            val rows = json.optInt("rows").coerceAtLeast(0)
            val cols = json.optInt("cols").coerceAtLeast(0)
            val layout = json.optJSONObject("layout") ?: JSONObject()
            val total = rows * cols

            val cells = buildCells(layout, rows, cols) { row, col -> (row * 1000 + col).toString() }
                ?: buildCells(layout, rows, cols) { row, col -> (row * cols + col).toString() }
                ?: buildCellsByInsertionOrder(layout, total, cols)

            val seatCount = cells.count { it.seat != null }
            Log.d(
                TAG,
                "解析布局 room=$roomId $roomName rows=$rows cols=$cols " +
                    "layoutKeys=${layout.length()} cells=${cells.size} seats=$seatCount",
            )
            if (seatCount == 0) {
                val iterator = layout.keys()
                val sample = buildList {
                    while (iterator.hasNext() && size < 5) add(iterator.next())
                }
                Log.w(TAG, "布局没有座位，layout 键示例=$sample keys=${layout.length()}")
            }
            return RoomLayout(
                roomId = roomId,
                roomName = roomName,
                rows = rows,
                cols = cols,
                cells = cells,
            )
        }

        /** 按给定键规则铺满网格；命中率不足 90% 视为规则不匹配。 */
        private fun buildCells(
            layout: JSONObject,
            rows: Int,
            cols: Int,
            keyOf: (Int, Int) -> String,
        ): List<LayoutCell>? {
            if (rows <= 0 || cols <= 0) return null
            val cells = ArrayList<LayoutCell>(rows * cols)
            var hits = 0
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    val item = layout.optJSONObject(keyOf(row, col))
                    if (item != null) hits++
                    cells.add(toCell(row * cols + col, item, cols))
                }
            }
            return if (hits >= (rows * cols) * 0.9) cells else null
        }

        /** 兜底：按 JSON 插入顺序逐格填充（服务器按行生成，顺序即行优先）。 */
        private fun buildCellsByInsertionOrder(
            layout: JSONObject,
            total: Int,
            cols: Int,
        ): List<LayoutCell> {
            val cells = ArrayList<LayoutCell>(maxOf(total, layout.length()))
            val keys = layout.keys()
            while (keys.hasNext()) {
                cells.add(toCell(cells.size, layout.optJSONObject(keys.next()), cols))
            }
            while (cells.size < total) cells.add(LayoutCell("empty"))
            return cells
        }

        private fun toCell(index: Int, item: JSONObject?, cols: Int): LayoutCell {
            if (item == null) return LayoutCell("empty")
            val kind = item.optString("type", "empty")
            if (kind != "seat") return LayoutCell(kind)
            return LayoutCell(
                kind = kind,
                seat = SeatItem(
                    index = index,
                    id = item.optInt("id"),
                    name = item.optString("name"),
                    status = item.optString("status"),
                    enabled = item.optBoolean("enabled", true),
                    row = if (cols > 0) index / cols else 0,
                    col = if (cols > 0) index % cols else 0,
                ),
            )
        }

        fun parseReservations(array: JSONArray): List<Reservation> =
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optLong("id", 0L)
                if (id == 0L) return@mapNotNull null
                Reservation(
                    id = id,
                    seatId = item.optInt("seatId"),
                    date = item.optString("onDate"),
                    begin = item.optString("begin"),
                    end = item.optString("end"),
                    status = item.optString("status"),
                    location = item.optString("location"),
                )
            }

        fun parseTimes(array: JSONArray): List<Pair<String, String>> =
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                if (id.isBlank()) null else id to item.optString("value")
            }
    }
}
