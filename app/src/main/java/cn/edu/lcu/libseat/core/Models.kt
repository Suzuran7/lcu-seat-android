package cn.edu.lcu.libseat.core

/** 账号：一个账号一份登录会话，互不干扰。 */
data class Account(
    val id: String = "",
    val label: String = "",
    val username: String = "",
    val password: String = "",
    val token: String = "",
    val userId: String = "",
    val lastLoginAt: Long = 0L,
) {
    val display: String get() = label.ifBlank { username }
    val hasSession: Boolean get() = token.isNotBlank()
}

enum class TaskAction { BOOK, CANCEL }

/** 一个定时任务：何时、用哪个账号、对哪个房间的哪些座位做什么。 */
data class Rule(
    val id: String = "",
    val name: String = "",
    val accountId: String = "",
    val action: TaskAction = TaskAction.BOOK,
    val enabled: Boolean = true,
    /** 每日触发时间 HH:mm，按北京时间。 */
    val triggerTime: String = "07:00",
    /** ISO 星期：1=周一 … 7=周日。 */
    val weekdays: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7),
    val buildingId: Int = 0,
    val buildingName: String = "",
    val floor: String = "",
    val roomId: Int = 0,
    val roomName: String = "",
    /** 优先座位，按顺序尝试；为空时按座位名顺序遍历全房间。 */
    val seatIds: List<Int> = emptyList(),
    val seatNames: List<String> = emptyList(),
    /** 指定的座位都不可用时，是否在房间内自动找第一个满足时段的座位。 */
    val autoFallback: Boolean = true,
    /** 开始时间，HH:mm 或 now（仅今天）。 */
    val start: String = "09:00",
    val end: String = "22:00",
    val intervalSec: Int = 5,
    val maxMinutes: Int = 10,
    val keepAwake: Boolean = true,
) {
    val display: String get() = name.ifBlank { "未命名任务" }
}

data class RoomInfo(
    val id: Int,
    val name: String,
    val buildingId: Int,
    val buildingName: String,
    val floor: String,
)

data class FilterInfo(
    val dates: List<String> = emptyList(),
    val buildings: List<Pair<Int, String>> = emptyList(),
    val rooms: List<RoomInfo> = emptyList(),
)

/** 布局中的一格：empty 空白、desk 桌子、seat 座位。 */
data class LayoutCell(val kind: String, val seat: SeatItem? = null)

data class SeatItem(
    val index: Int,
    val id: Int,
    val name: String,
    val status: String,
    val enabled: Boolean,
    val row: Int,
    val col: Int,
) {
    val statusText: String
        get() = when {
            !enabled -> "停用"
            else -> when (status) {
                "FREE" -> "空闲"
                "IN_USE" -> "使用中"
                "AWAY" -> "暂离"
                "FULL" -> "已约满"
                "BOOKED" -> "已预约"
                "STOP", "DISABLED" -> "停用"
                else -> status.ifBlank { "未知" }
            }
        }

    val usable: Boolean get() = enabled && status != "STOP" && status != "DISABLED"
}

data class RoomLayout(
    val roomId: Int,
    val roomName: String,
    val rows: Int,
    val cols: Int,
    val cells: List<LayoutCell>,
) {
    val seats: List<SeatItem> get() = cells.mapNotNull { it.seat }

    fun seat(id: Int): SeatItem? = cells.firstNotNullOfOrNull { cell ->
        cell.seat?.takeIf { it.id == id }
    }
}

data class Reservation(
    val id: Long,
    val seatId: Int,
    val date: String,
    val begin: String,
    val end: String,
    val status: String,
    val location: String,
) {
    companion object {
        val ACTIVE_STATUSES = setOf("RESERVE", "CHECK_IN", "IN_USE", "AWAY")
        val TERMINAL_STATUSES =
            setOf("CANCEL", "CANCELLED", "CANCELED", "END", "COMPLETE", "STOP", "MISS")

        fun isActive(status: String) = status !in TERMINAL_STATUSES
    }
}

data class SeatChoice(
    val seatId: Int,
    val seatName: String,
    val roomId: Int,
    val roomName: String,
    val date: String,
    val start: String,
    val end: String,
) {
    val describe: String get() = "$roomName $seatName（$seatId）"
}

data class TapPoint(val x: Int, val y: Int)

data class CaptchaChallenge(
    val token: String,
    val image: String,
    val wordImage: String,
    val count: Int,
)

data class LogEntry(
    val time: Long,
    val ruleName: String,
    val account: String,
    val ok: Boolean,
    val message: String,
)
