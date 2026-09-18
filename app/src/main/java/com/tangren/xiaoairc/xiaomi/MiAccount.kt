package com.tangren.xiaoairc.xiaomi

import android.util.Base64
import com.tangren.xiaoairc.LogBus
import com.tangren.xiaoairc.NetUtil
import com.tangren.xiaoairc.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 小米账号认证 + micoapi 请求封装。
 *
 * 逻辑对齐现有开源实现（miservice / MiAir，均 MIT）：
 *  - 推荐路径：用浏览器里的 userId + passToken 换取 micoapi 的 serviceToken（不会触发验证码）
 *  - 备用路径：账号密码登录，服务端下发 _sign 后走 serviceLoginAuth2
 *  - 业务请求：https://api2.mina.mi.com，Cookie 带 userId + serviceToken
 */
class MiAccount(private val prefs: Prefs) {

    companion object {
        /** 米家 App 的官方 UA，走 passToken 换 token 时必须带上，否则会被风控拦 */
        const val UA_APP = "APP/com.xiaomi.mihome APPV/60209 iosPassportSDK/3.9.0 iOS/17.5.1"
        private const val MINA_BASE = "https://api2.mina.mi.com"
        private val FORM_CT = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    var userId: String = ""
        private set
    var serviceToken: String = ""
        private set
    var ssecurity: String = ""
        private set
    var deviceId: String = ""
        private set

    var loggedIn: Boolean = false
        private set

    /** 载入磁盘上的 serviceToken 缓存，避免每次启动都要换 token。 */
    fun loadCachedToken(): Boolean {
        val uid = prefs.tokenUserId
        val st = prefs.serviceToken
        if (uid.isNotBlank() && st.isNotBlank()) {
            userId = uid
            serviceToken = st
            ssecurity = prefs.ssecurity
            deviceId = deviceIdFor(uid)
            loggedIn = true
            LogBus.i("已载入缓存的 serviceToken (userId=$uid)")
            return true
        }
        return false
    }

    suspend fun login(): Boolean = withContext(Dispatchers.IO) {
        // 登录失败限速：窗口内失败过多时先拒绝，避免反复触发小米风控
        val lock = LoginGuard.remainingLockSeconds(prefs)
        if (lock > 0) {
            LogBus.w("登录已限速：请等待 ${lock}s 后再试（连续失败过多，避免触发风控）")
            return@withContext false
        }

        val ok = if (prefs.authMode == "password") {
            val acc = prefs.account.trim()
            val pwd = prefs.password
            if (acc.isEmpty() || pwd.isEmpty()) {
                LogBus.e("请先填写小米账号和密码")
                return@withContext false
            }
            loginWithPassword(acc, pwd)
        } else {
            val uid = prefs.userId.trim()
            val ptk = prefs.passToken.trim()
            if (uid.isEmpty() || ptk.isEmpty()) {
                LogBus.e("请先填写小米 ID 和 passToken")
                return@withContext false
            }
            loginWithPassToken(uid, ptk)
        }

        if (ok) {
            LoginGuard.recordSuccess(prefs)
        } else {
            val n = LoginGuard.recordFailure(prefs)
            LogBus.w("已是连续第 $n 次登录失败（成功一次即清零）")
        }
        ok
    }

    /** 用当前凭据重登一次（token 失效时调用）。 */
    suspend fun relogin(): Boolean {
        loggedIn = false
        prefs.clearToken()
        return login()
    }

    private fun deviceIdFor(uid: String): String =
        NetUtil.md5Hex("xiaoairc_$uid").take(16).uppercase()

    // ------------------------------------------------------------------
    // 路径一：passToken 换 serviceToken
    // ------------------------------------------------------------------

    private fun loginWithPassToken(uid: String, passToken: String): Boolean {
        deviceId = deviceIdFor(uid)
        val url = "https://account.xiaomi.com/pass/serviceLogin?sid=micoapi&_json=true"
        val cookie = "sdkVersion=3.9; deviceId=$deviceId; userId=$uid; passToken=$passToken"

        val body = httpGet(url, cookie) ?: return false
        val json = parseMiJson(body) ?: return false
        val code = json.optInt("code", -1)
        if (code != 0) {
            val desc = json.optString("description").ifEmpty { json.optString("desc") }
            LogBus.e("passToken 换取 serviceToken 失败 (code=$code): $desc")
            if (code == 70016) LogBus.e("提示：passToken 可能已过期，请重新登录 account.xiaomi.com 复制")
            return false
        }

        val token = securityTokenService(
            json.optString("location"),
            json.optString("nonce"),
            json.optString("ssecurity")
        ) ?: run {
            LogBus.e("未能从鉴权响应中提取 serviceToken")
            return false
        }

        userId = uid
        serviceToken = token
        ssecurity = json.optString("ssecurity")
        loggedIn = true

        prefs.tokenUserId = uid
        prefs.serviceToken = token
        prefs.ssecurity = ssecurity
        // 顺手把 passToken 记下来，下次直接复用
        prefs.userId = uid
        prefs.passToken = passToken
        LogBus.i("登录成功（passToken 路径），userId=$uid")
        return true
    }

    // ------------------------------------------------------------------
    // 路径二：账号密码
    // ------------------------------------------------------------------

    private fun loginWithPassword(account: String, password: String): Boolean {
        deviceId = NetUtil.md5Hex("xiaoairc_acc_$account").take(16).uppercase()
        val cookie = "sdkVersion=3.9; deviceId=$deviceId; passToken="

        val first = parseMiJson(httpGet(
            "https://account.xiaomi.com/pass/serviceLogin?sid=micoapi&_json=true", cookie
        ) ?: return false) ?: return false

        // 已存在登录态时直接复用
        if (first.optInt("code", -1) == 0 && first.has("location")) {
            return finishPasswordLogin(first, account)
        }

        val form = linkedMapOf(
            "_json" to "true",
            "qs" to first.optString("qs"),
            "sid" to first.optString("sid", "micoapi"),
            "_sign" to first.optString("_sign"),
            "callback" to first.optString("callback"),
            "user" to account,
            "hash" to NetUtil.md5Hex(password).uppercase()
        )
        val second = parseMiJson(
            httpPost("https://account.xiaomi.com/pass/serviceLoginAuth2", form, cookie)
                ?: return false
        ) ?: return false

        val code = second.optInt("code", -1)
        if (code != 0) {
            when (code) {
                87001 -> LogBus.e("需要人机验证/captcha。建议改用 passToken 方式登录。")
                70016 -> LogBus.e("登录校验失败：账号密码错误，或账号开启了二次验证。建议改用 passToken 方式。")
                else -> {
                    val desc = second.optString("description")
                    LogBus.e("密码登录失败 (code=$code): $desc")
                }
            }
            return false
        }
        return finishPasswordLogin(second, account)
    }

    private fun finishPasswordLogin(json: JSONObject, account: String): Boolean {
        val uid = json.optString("userId")
        val passToken = json.optString("passToken")
        val token = securityTokenService(
            json.optString("location"),
            json.optString("nonce"),
            json.optString("ssecurity")
        ) ?: run {
            LogBus.e("未能提取 serviceToken")
            return false
        }
        userId = uid
        serviceToken = token
        ssecurity = json.optString("ssecurity")
        loggedIn = true

        prefs.tokenUserId = uid
        prefs.serviceToken = token
        prefs.ssecurity = ssecurity
        if (passToken.isNotEmpty()) {
            prefs.userId = uid
            prefs.passToken = passToken
        }
        prefs.account = account
        LogBus.i("登录成功（密码路径），userId=$uid")
        return true
    }

    /**
     * clientSign = base64(sha1("nonce=" + nonce + "&" + ssecurity))
     * 然后向 location 取 serviceToken（Set-Cookie）。中间可能有 302，需要手动跟。
     */
    private fun securityTokenService(location: String, nonce: String, ssecurity: String): String? {
        if (location.isEmpty() || nonce.isEmpty()) return null
        val nsec = "nonce=$nonce&$ssecurity"
        val sign = Base64.encodeToString(NetUtil.sha1(nsec.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        var url = location + "&clientSign=" + URLEncoder.encode(sign, "UTF-8")

        var hops = 0
        while (hops++ < 5) {
            var next: String? = null
            try {
                client.newCall(
                    Request.Builder().url(url).header("User-Agent", UA_APP).get().build()
                ).execute().use { resp ->
                    for (h in resp.headers("Set-Cookie")) {
                        val v = cookieValue(h, "serviceToken")
                        if (!v.isNullOrEmpty()) return v
                    }
                    if (resp.code in 300..399) next = resp.header("Location")
                }
            } catch (t: Throwable) {
                LogBus.e("取 serviceToken 异常: ${t.message}")
                return null
            }
            val n = next ?: return null
            url = if (n.startsWith("http")) n else java.net.URI(url).resolve(n).toString()
        }
        return null
    }

    private fun cookieValue(setCookie: String, name: String): String? {
        for (part in setCookie.split(";")) {
            val kv = part.trim()
            if (kv.startsWith("$name=")) return kv.substring(name.length + 1)
        }
        return null
    }

    // ------------------------------------------------------------------
    // 业务请求（micoapi）
    // ------------------------------------------------------------------

    /**
     * 与 miservice.mina_request 等价：
     *  - uri 里已经带 query 时，requestId 拼到 URL 上（GET，如 device_list）
     *  - 传了 form 时，requestId 作为表单字段（POST，如 ubus）
     */
    suspend fun minaRequest(uri: String, form: Map<String, String>?): JSONObject? =
        withContext(Dispatchers.IO) {
            if (!loggedIn && !login()) return@withContext null

            var attempt = 0
            while (attempt < 2) {
                attempt++
                val requestId = "app_ios_" + NetUtil.randomId(30)
                val url: String
                val builder: Request.Builder

                if (form == null) {
                    url = MINA_BASE + uri + (if (uri.contains("?")) "&" else "?") + "requestId=$requestId"
                    builder = Request.Builder().url(url).get()
                } else {
                    url = "$MINA_BASE$uri?requestId=$requestId"
                    val bodyMap = LinkedHashMap(form).apply { put("requestId", requestId) }
                    builder = Request.Builder().url(url).post(toFormBody(bodyMap))
                }

                builder.header("User-Agent", UA_APP)
                builder.header("Cookie", "userId=$userId; serviceToken=$serviceToken")

                val text = try {
                    client.newCall(builder.build()).execute().use { it.body?.string() ?: "" }
                } catch (t: Throwable) {
                    LogBus.e("micoapi 请求异常 $uri: ${t.message}")
                    return@withContext null
                }

                if (text.isBlank()) {
                    LogBus.e("micoapi 空响应 $uri")
                    return@withContext null
                }

                val json = try {
                    JSONObject(text)
                } catch (t: Throwable) {
                    LogBus.e("micoapi 响应非 JSON $uri: ${text.take(160)}")
                    return@withContext null
                }

                if (json.optInt("code", -1) == 0) return@withContext json

                val message = json.optString("message")
                if (attempt == 1 && message.lowercase().contains("auth")) {
                    LogBus.w("token 失效，正在重新登录…")
                    loggedIn = false
                    if (!login()) return@withContext null
                    continue
                }
                val codeText = json.optString("code")
                val detail = message.ifEmpty { text.take(160) }
                LogBus.e("micoapi 返回错误 $uri: $codeText $detail")
                return@withContext json
            }
            null
        }

    // ------------------------------------------------------------------
    // 基础 HTTP
    // ------------------------------------------------------------------

    private fun httpGet(url: String, cookie: String): String? = try {
        client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", UA_APP)
                .header("Cookie", cookie)
                .get().build()
        ).execute().use { it.body?.string() ?: "" }
    } catch (t: Throwable) {
        LogBus.e("HTTP GET 失败: ${t.message}")
        null
    }

    private fun httpPost(url: String, form: Map<String, String>, cookie: String): String? = try {
        client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", UA_APP)
                .header("Cookie", cookie)
                .post(toFormBody(form)).build()
        ).execute().use { it.body?.string() ?: "" }
    } catch (t: Throwable) {
        LogBus.e("HTTP POST 失败: ${t.message}")
        null
    }

    private fun toFormBody(map: Map<String, String>) =
        map.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
            .toRequestBody(FORM_CT)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * 小米接口会返回 "&&&START&&&" 前缀 + JSON，统一从第一个左花括号开始截。
     *
     * 特别注意：**账号密码校验不通过时，serviceLoginAuth2 会返回一整个登录页 HTML**
     * （Content-Type: text/html，body 是一堆空行 + <!doctype html>），
     * 不是 JSON。这种失败方式非常隐蔽，所以这里单独识别并给出可执行的建议。
     */
    private fun parseMiJson(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("<!doctype", true) ||
            trimmed.startsWith("<html", true) ||
            trimmed.contains("<title>小米账号")
        ) {
            LogBus.e(
                "小米返回的是登录页 HTML 而不是 JSON —— 账号密码没通过校验，或被风控拦了。" +
                    "建议改用 passToken 方式登录（点「打开小米登录页」+「粘贴并解析 Cookie」）"
            )
            return null
        }
        val idx = raw.indexOf('{')
        if (idx < 0) {
            LogBus.e("小米接口返回格式异常: ${trimmed.take(160)}")
            return null
        }
        return try {
            JSONObject(raw.substring(idx))
        } catch (t: Throwable) {
            LogBus.e("小米接口 JSON 解析失败: ${t.message}")
            null
        }
    }
}
