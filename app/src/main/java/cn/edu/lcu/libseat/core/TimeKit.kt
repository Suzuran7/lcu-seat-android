package cn.edu.lcu.libseat.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 时间处理：统一按北京时间，与学校服务器一致。 */
object TimeKit {

    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val hhmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun now(): ZonedDateTime = ZonedDateTime.now(zone)

    fun today(): LocalDate = LocalDate.now(zone)

    /** 预约只支持当天，日期一律用这一个值。 */
    fun todayIso(): String = today().toString()

    fun isValidHhmm(value: String): Boolean =
        runCatching { LocalTime.parse(value, hhmm) }.isSuccess

    /** "09:00" → "540"：接口用当日分钟编号表示时间。 */
    fun toMinuteId(value: String): String {
        val time = LocalTime.parse(value, hhmm)
        return (time.hour * 60 + time.minute).toString()
    }

    fun fromMinuteId(id: String): String = runCatching {
        val minutes = id.toInt()
        LocalTime.of(minutes / 60, minutes % 60).format(hhmm)
    }.getOrDefault(id)

    fun dayName(iso: Int): String =
        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            .getOrElse(iso - 1) { "周?" }

    /** 计算任务下一次真正触发的时间；无可用星期时返回 null。 */
    fun nextTrigger(rule: Rule, from: ZonedDateTime = now()): ZonedDateTime? {
        if (!isValidHhmm(rule.triggerTime)) return null
        val time = LocalTime.parse(rule.triggerTime, hhmm)
        for (offset in 0..14) {
            val date = from.toLocalDate().plusDays(offset.toLong())
            if (rule.weekdays.isNotEmpty() && date.dayOfWeek.value !in rule.weekdays) continue
            val candidate = date.atTime(time).atZone(zone)
            if (candidate.isAfter(from)) return candidate
        }
        return null
    }

    fun nextTriggerText(rule: Rule): String {
        val next = nextTrigger(rule) ?: return "未设置有效时间"
        return "%02d-%02d %s %s".format(
            next.monthValue,
            next.dayOfMonth,
            next.format(hhmm),
            dayName(next.dayOfWeek.value),
        )
    }

    fun prettyTime(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(zone)
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
}
