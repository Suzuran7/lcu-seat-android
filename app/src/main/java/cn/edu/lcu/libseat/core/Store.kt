package cn.edu.lcu.libseat.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 本地存储：账号（含登录会话）、定时任务、执行记录。
 *
 * 账号含密码，优先写入 EncryptedSharedPreferences；设备密钥不可用时退化为普通
 * SharedPreferences，保证功能可用，同时在界面上提示风险。
 */
class SeatStore(context: Context) {

    private val appContext = context.applicationContext
    private val encrypted: Boolean
    private val prefs: SharedPreferences

    init {
        var ok = true
        val created = try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "seat_store_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            ok = false
            appContext.getSharedPreferences("seat_store_fallback", Context.MODE_PRIVATE)
        }
        encrypted = ok
        prefs = created
    }

    val isEncrypted: Boolean get() = encrypted

    // ------------------------------------------------------------------ 账号

    fun accounts(): List<Account> = synchronized(this) {
        parseAccounts(prefs.getString(KEY_ACCOUNTS, null))
    }

    fun saveAccounts(list: List<Account>) = synchronized(this) {
        prefs.edit().putString(KEY_ACCOUNTS, encodeAccounts(list).toString()).apply()
    }

    fun upsertAccount(account: Account): Account {
        val list = accounts().toMutableList()
        val id = account.id.ifBlank { UUID.randomUUID().toString() }
        val saved = account.copy(id = id)
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) list[index] = saved else list.add(saved)
        saveAccounts(list)
        return saved
    }

    fun deleteAccount(id: String) {
        saveAccounts(accounts().filterNot { it.id == id })
        // 任务需一并清理，避免指向已删除账号。
        saveRules(rules().filterNot { it.accountId == id })
    }

    fun updateSession(accountId: String, token: String, userId: String) {
        val list = accounts().toMutableList()
        val index = list.indexOfFirst { it.id == accountId }
        if (index < 0) return
        list[index] = list[index].copy(
            token = token,
            userId = userId,
            lastLoginAt = System.currentTimeMillis(),
        )
        saveAccounts(list)
    }

    fun clearSession(accountId: String) {
        val list = accounts().toMutableList()
        val index = list.indexOfFirst { it.id == accountId }
        if (index < 0) return
        list[index] = list[index].copy(token = "", userId = "")
        saveAccounts(list)
    }

    fun account(id: String): Account? = accounts().firstOrNull { it.id == id }

    // ------------------------------------------------------------------ 任务

    fun rules(): List<Rule> = synchronized(this) {
        parseRules(prefs.getString(KEY_RULES, null))
    }

    fun saveRules(list: List<Rule>) = synchronized(this) {
        prefs.edit().putString(KEY_RULES, encodeRules(list).toString()).apply()
    }

    fun upsertRule(rule: Rule): Rule {
        val list = rules().toMutableList()
        val id = rule.id.ifBlank { UUID.randomUUID().toString() }
        val saved = rule.copy(id = id)
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) list[index] = saved else list.add(saved)
        saveRules(list)
        return saved
    }

    fun deleteRule(id: String) {
        saveRules(rules().filterNot { it.id == id })
    }

    fun rule(id: String): Rule? = rules().firstOrNull { it.id == id }

    // ------------------------------------------------------------------ 记录

    fun logs(): List<LogEntry> = synchronized(this) {
        val raw = prefs.getString(KEY_LOGS, null) ?: return emptyList()
        runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                runCatching {
                    val item = array.getJSONObject(index)
                    LogEntry(
                        time = item.optLong("time"),
                        ruleName = item.optString("rule"),
                        account = item.optString("account"),
                        ok = item.optBoolean("ok"),
                        message = item.optString("message"),
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun addLog(entry: LogEntry) = synchronized(this) {
        val list = logs().toMutableList()
        list.add(0, entry)
        while (list.size > MAX_LOGS) list.removeAt(list.size - 1)
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("time", it.time)
                    .put("rule", it.ruleName)
                    .put("account", it.account)
                    .put("ok", it.ok)
                    .put("message", it.message)
            )
        }
        prefs.edit().putString(KEY_LOGS, array.toString()).apply()
    }

    fun clearLogs() = synchronized(this) {
        prefs.edit().remove(KEY_LOGS).apply()
    }

    // ------------------------------------------------------------------ 开关

    var guardEnabled: Boolean
        get() = prefs.getBoolean(KEY_GUARD, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GUARD, value).apply()
        }

    var lastActiveAccountId: String
        get() = prefs.getString(KEY_ACTIVE_ACCOUNT, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_ACTIVE_ACCOUNT, value).apply()
        }

    // ------------------------------------------------------- JSON 编解码

    private fun encodeAccounts(list: List<Account>): JSONArray {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("label", it.label)
                    .put("username", it.username)
                    .put("password", it.password)
                    .put("token", it.token)
                    .put("userId", it.userId)
                    .put("lastLoginAt", it.lastLoginAt)
            )
        }
        return array
    }

    private fun parseAccounts(raw: String?): List<Account> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                Account(
                    id = item.optString("id"),
                    label = item.optString("label"),
                    username = item.optString("username"),
                    password = item.optString("password"),
                    token = item.optString("token"),
                    userId = item.optString("userId"),
                    lastLoginAt = item.optLong("lastLoginAt"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeRules(list: List<Rule>): JSONArray {
        val array = JSONArray()
        list.forEach { rule ->
            array.put(
                JSONObject()
                    .put("id", rule.id)
                    .put("name", rule.name)
                    .put("accountId", rule.accountId)
                    .put("action", rule.action.name)
                    .put("enabled", rule.enabled)
                    .put("triggerTime", rule.triggerTime)
                    .put("weekdays", JSONArray(rule.weekdays))
                    .put("buildingId", rule.buildingId)
                    .put("buildingName", rule.buildingName)
                    .put("floor", rule.floor)
                    .put("roomId", rule.roomId)
                    .put("roomName", rule.roomName)
                    .put("seatIds", JSONArray(rule.seatIds))
                    .put("seatNames", JSONArray(rule.seatNames))
                    .put("autoFallback", rule.autoFallback)
                    .put("start", rule.start)
                    .put("end", rule.end)
                    .put("intervalSec", rule.intervalSec)
                    .put("maxMinutes", rule.maxMinutes)
                    .put("keepAwake", rule.keepAwake)
            )
        }
        return array
    }

    private fun parseRules(raw: String?): List<Rule> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                Rule(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    accountId = item.optString("accountId"),
                    action = runCatching {
                        TaskAction.valueOf(item.optString("action", "BOOK"))
                    }.getOrDefault(TaskAction.BOOK),
                    enabled = item.optBoolean("enabled", true),
                    triggerTime = item.optString("triggerTime", "07:00"),
                    weekdays = item.optJSONArray("weekdays").toIntList().ifEmpty {
                        listOf(1, 2, 3, 4, 5, 6, 7)
                    },
                    buildingId = item.optInt("buildingId"),
                    buildingName = item.optString("buildingName"),
                    floor = item.optString("floor"),
                    roomId = item.optInt("roomId"),
                    roomName = item.optString("roomName"),
                    seatIds = item.optJSONArray("seatIds").toIntList(),
                    seatNames = item.optJSONArray("seatNames").toStringList(),
                    autoFallback = item.optBoolean("autoFallback", true),
                    start = item.optString("start", "09:00"),
                    end = item.optString("end", "22:00"),
                    intervalSec = item.optInt("intervalSec", 5).coerceAtLeast(1),
                    maxMinutes = item.optInt("maxMinutes", 10).coerceAtLeast(0),
                    keepAwake = item.optBoolean("keepAwake", true),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun JSONArray?.toIntList(): List<Int> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optInt(index, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optString(index).takeIf { it.isNotBlank() }
        }
    }

    companion object {
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_RULES = "rules"
        private const val KEY_LOGS = "logs"
        private const val KEY_GUARD = "guard_enabled"
        private const val KEY_ACTIVE_ACCOUNT = "active_account"
        private const val MAX_LOGS = 200
    }
}
