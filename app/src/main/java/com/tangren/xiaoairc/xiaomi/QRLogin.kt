package com.tangren.xiaoairc.xiaomi

import com.tangren.xiaoairc.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 小米扫码登录（手机端）。
 *
 * 相比「手填账号密码」：不触发验证码 / 风控，且不用去浏览器里翻 Cookie。
 *
 * 流程（对齐 miair-next 的 qr_login.py）：
 *  1. GET  /pass/serviceLogin?sid=mijia        → _sign / qs / callback
 *  2. GET  /longPolling/loginUrl               → qr(二维码图 URL) / loginUrl / lp(长轮询地址)
 *  3. GET  lp（长轮询，服务端挂起约 30s）        → 米家 App 扫码确认后返回 passToken + userId
 *  4. 上层拿到 userId + passToken 后，切到 passToken 方式调用 MiAccount.login()
 *
 * 注意：本文件是依据公开 HTTP 流程的独立实现，不包含任何 GPL 源码。
 */
class QRLogin {

    enum class State { IDLE, WAITING, CONFIRMED, EXPIRED, FAILED }

    data class QrInfo(val qrImageUrl: String, val loginUrl: String)

    data class PollResult(
        val state: State,
        val message: String,
        val userId: String = "",
        val passToken: String = "",
        val cUserId: String = ""
    )

    companion object {
        private const val ACCOUNT_BASE = "https://account.xiaomi.com"
        private const val LONG_POLLING_URL = "https://account.xiaomi.com/longPolling/loginUrl"
        private const val SID = "mijia" // 用 mijia（比 micoapi 稳定）
        private const val MAX_POLL_COUNT = 20
        private const val UA_TEMPLATE =
            "Android-7.1.1-1.0.0-ONEPLUS A3010-136-%s APP/xiaomi.smarthome APPV/62830"
    }

    private val deviceId: String = randomHex(16) // 32 位十六进制
    private val userAgent: String = UA_TEMPLATE.format(deviceId)
    private val cookieJar = MemoryCookieJar()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS) // 长轮询服务端挂起约 30s，留余量
        .build()

    private var pollUrl: String = ""
    private var pollCount: Int = 0

    @Volatile
    var state: State = State.IDLE
        private set

    /** 取二维码。成功返回二维码图 URL 与网页登录 URL。 */
    suspend fun fetchQrCode(): QrInfo? = withContext(Dispatchers.IO) {
        try {
            // Step 1: serviceLogin 取签名参数
            val url1 = "$ACCOUNT_BASE/pass/serviceLogin?sid=$SID&_json=true"
            val req1 = Request.Builder()
                .url(url1)
                .header("User-Agent", userAgent)
                .header("Cookie", "sdkVersion=3.8.6; deviceId=$deviceId")
                .get()
                .build()
            val body1 = client.newCall(req1).execute().use { it.body?.string() ?: "" }
            val d1 = JSONObject(stripPrefix(body1))
            val sign = getStr(d1, "_sign")
            val qs = getStr(d1, "qs")
            val callback = getStr(d1, "callback")
            if (sign.isEmpty() || qs.isEmpty() || callback.isEmpty()) {
                LogBus.w("扫码登录：serviceLogin 缺少必要参数")
                state = State.FAILED
                return@withContext null
            }

            // Step 2: longPolling/loginUrl 取二维码与长轮询地址
            // 二维码尺寸取大一些：太小在手机上不好扫（客户端还会按密度再缩放一次）
            val params = "_qrsize=640" +
                "&qs=${enc(qs)}" +
                "&sid=$SID" +
                "&_sign=${enc(sign)}" +
                "&callback=${enc(callback)}" +
                "&_json=true" +
                "&_dc=${System.currentTimeMillis()}"
            val url2 = "$LONG_POLLING_URL?$params"
            val req2 = Request.Builder()
                .url(url2)
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .get()
                .build()
            val body2 = client.newCall(req2).execute().use { it.body?.string() ?: "" }
            val d2 = JSONObject(stripPrefix(body2))
            if (d2.optInt("code", 0) != 0) {
                LogBus.w("扫码登录：获取二维码失败 ${getStr(d2, "desc")}")
                state = State.FAILED
                return@withContext null
            }
            val qr = getStr(d2, "qr")
            val loginUrl = getStr(d2, "loginUrl")
            val lp = getStr(d2, "lp")
            if (lp.isEmpty()) {
                LogBus.w("扫码登录：响应缺少长轮询地址 lp")
                state = State.FAILED
                return@withContext null
            }
            pollUrl = lp
            pollCount = 0
            state = State.WAITING
            QrInfo(qr.ifEmpty { loginUrl }, loginUrl)
        } catch (t: Throwable) {
            LogBus.e("扫码登录：获取二维码异常 ${t.message}")
            state = State.FAILED
            null
        }
    }

    /** 单次轮询（长轮询，阻塞至服务端返回或超时）。 */
    suspend fun poll(): PollResult = withContext(Dispatchers.IO) {
        if (pollUrl.isEmpty()) {
            return@withContext PollResult(State.FAILED, "尚未获取二维码")
        }
        pollCount++
        if (pollCount > MAX_POLL_COUNT) {
            state = State.EXPIRED
            return@withContext PollResult(State.EXPIRED, "二维码已过期")
        }

        val status: Int
        val body: String
        try {
            val req = Request.Builder()
                .url(pollUrl)
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .get()
                .build()
            client.newCall(req).execute().use {
                status = it.code
                body = it.body?.string() ?: ""
            }
        } catch (_: SocketTimeoutException) {
            // 长轮询超时 = 仍在等待扫码
            return@withContext PollResult(State.WAITING, "等待扫码")
        } catch (t: Throwable) {
            state = State.FAILED
            return@withContext PollResult(State.FAILED, "轮询异常：${t.message}")
        }

        if (status == 403) {
            state = State.EXPIRED
            return@withContext PollResult(State.EXPIRED, "二维码已过期")
        }
        if (status >= 400) {
            state = State.FAILED
            return@withContext PollResult(State.FAILED, "轮询失败：HTTP $status")
        }

        val json = try {
            JSONObject(stripPrefix(body))
        } catch (_: Throwable) {
            return@withContext PollResult(State.WAITING, "等待扫码")
        }
        if (json.optInt("code", 0) != 0) {
            state = State.EXPIRED
            return@withContext PollResult(State.EXPIRED, "二维码失效：${getStr(json, "desc")}")
        }

        val passToken = getStr(json, "passToken")
        val userId = getStr(json, "userId")
        if (passToken.isEmpty() || userId.isEmpty()) {
            return@withContext PollResult(State.WAITING, "已扫码，等待确认")
        }
        state = State.CONFIRMED
        LogBus.i("扫码登录成功（userId 已隐藏）")
        PollResult(State.CONFIRMED, "登录成功", userId, passToken, getStr(json, "cUserId"))
    }

    fun close() {
        try {
            client.dispatcher.executorService.shutdown()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------

    private fun stripPrefix(body: String): String =
        body.replace("&&&START&&&", "").trim()

    /** 兼容 userId 为数字类型的取值 */
    private fun getStr(obj: JSONObject, key: String): String {
        val v = obj.opt(key) ?: return ""
        return when (v) {
            is String -> v
            is Number -> v.toLong().toString()
            else -> v.toString()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** 极简 Cookie 存储：登录流程需要跨请求保留 cookie（含非常规域），全接受。 */
    private class MemoryCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = store.getOrPut(url.host) { mutableListOf() }
            synchronized(list) {
                for (c in cookies) {
                    list.removeAll { it.name == c.name }
                    list.add(c)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val list = store[url.host] ?: return emptyList()
            return synchronized(list) { list.toList() }
        }
    }
}
