package cn.edu.lcu.libseat.core

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 站点公开前端使用的加密与签名规则。
 *
 * 与 Python 版 lcu_seat/protocol.py 完全等价：
 *  - 凭据：AES-128-CBC / PKCS7，密钥 "server_date_time"，向量 "client_date_time"，Base64 后加 "_encrypt"
 *  - 学校代码：同一组密钥解密 baseUrL.js 的 NUMCODE，得到 HMAC 密钥
 *  - 签名：HMAC-SHA256("seat::{request_id}::{timestamp}::{METHOD}")
 *  - 点击坐标：紧凑 JSON 再 Base64
 */
object SeatProtocol {

    private val AES_KEY = "server_date_time".toByteArray(Charsets.UTF_8)
    private val AES_IV = "client_date_time".toByteArray(Charsets.UTF_8)

    fun encryptCredential(value: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(AES_KEY, "AES"),
            IvParameterSpec(AES_IV)
        )
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted) + "_encrypt"
    }

    fun decryptSchoolCode(value: String): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(AES_KEY, "AES"),
            IvParameterSpec(AES_IV)
        )
        return cipher.doFinal(Base64.getDecoder().decode(value))
    }

    /** 每次请求生成新的请求编号与毫秒时间，签名只对当次请求有效。 */
    fun signedHeaders(method: String, key: ByteArray?): Map<String, String> {
        if (key == null) return emptyMap()
        val requestId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis().toString()
        val message = "seat::$requestId::$timestamp::${method.uppercase()}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val signature = mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
        return mapOf(
            "X-request-id" to requestId,
            "X-request-date" to timestamp,
            "X-hmac-request-key" to signature,
        )
    }

    /** 网页以 330 像素宽显示验证码背景图，坐标按该宽度换算。 */
    fun encodePoints(points: List<TapPoint>): String {
        val json = points.joinToString(prefix = "[", postfix = "]", separator = ",") {
            "{\"x\":${it.x},\"y\":${it.y}}"
        }
        return Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xff
            out.append("0123456789abcdef"[value ushr 4])
            out.append("0123456789abcdef"[value and 0x0f])
        }
        return out.toString()
    }
}
