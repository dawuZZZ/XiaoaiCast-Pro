package com.tangren.xiaoairc

import android.content.Context
import android.content.SharedPreferences

/** 配置与令牌持久化（含小米 serviceToken 缓存）。 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("xiaoairc", Context.MODE_PRIVATE)

    /** password（默认） / passToken —— 密码优先，失败时再切 passToken */
    var authMode: String
        get() = sp.getString(K_AUTH_MODE, "password") ?: "password"
        set(v) = sp.edit().putString(K_AUTH_MODE, v).apply()

    var userId: String
        get() = sp.getString(K_USER_ID, "") ?: ""
        set(v) = sp.edit().putString(K_USER_ID, v).apply()

    var passToken: String
        get() = sp.getString(K_PASS_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(K_PASS_TOKEN, v).apply()

    var account: String
        get() = sp.getString(K_ACCOUNT, "") ?: ""
        set(v) = sp.edit().putString(K_ACCOUNT, v).apply()

    var password: String
        get() = sp.getString(K_PASSWORD, "") ?: ""
        set(v) = sp.edit().putString(K_PASSWORD, v).apply()

    // ---- 令牌缓存 ----
    var serviceToken: String
        get() = sp.getString(K_SERVICE_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(K_SERVICE_TOKEN, v).apply()

    var ssecurity: String
        get() = sp.getString(K_SSECURITY, "") ?: ""
        set(v) = sp.edit().putString(K_SSECURITY, v).apply()

    var tokenUserId: String
        get() = sp.getString(K_TOKEN_UID, "") ?: ""
        set(v) = sp.edit().putString(K_TOKEN_UID, v).apply()

    // ---- 目标音箱 ----
    var deviceId: String
        get() = sp.getString(K_DEVICE_ID, "") ?: ""
        set(v) = sp.edit().putString(K_DEVICE_ID, v).apply()

    var deviceName: String
        get() = sp.getString(K_DEVICE_NAME, "") ?: ""
        set(v) = sp.edit().putString(K_DEVICE_NAME, v).apply()

    var hardware: String
        get() = sp.getString(K_HARDWARE, "") ?: ""
        set(v) = sp.edit().putString(K_HARDWARE, v).apply()

    var udn: String
        get() = sp.getString(K_UDN, "") ?: ""
        set(v) = sp.edit().putString(K_UDN, v).apply()

    /** 伪装成 DLNA 设备后在音乐 App 里显示的名字；留空则用音箱本名 */
    var dlnaName: String
        get() = sp.getString(K_DLNA_NAME, "") ?: ""
        set(v) = sp.edit().putString(K_DLNA_NAME, v).apply()

    // ---- 行为开关 ----
    /** 走 player_play_music（L05B / L05C / LX05 等机型必需） */
    var useMusicApi: Boolean
        get() = sp.getBoolean(K_MUSIC_API, true)
        set(v) = sp.edit().putBoolean(K_MUSIC_API, v).apply()

    /** 音频流经手机代理转发 */
    var proxyEnabled: Boolean
        get() = sp.getBoolean(K_PROXY, true)
        set(v) = sp.edit().putBoolean(K_PROXY, v).apply()

    /**
     * 先把音频缓冲到本地再供给音箱：CDN 不稳定时更抗造、seek 更准，
     * 代价是起播要等第一批数据落地。默认关——直连模式起播最快，缓冲作为兜底手段。
     */
    var bufferEnabled: Boolean
        get() = sp.getBoolean(K_BUFFER, false)
        set(v) = sp.edit().putBoolean(K_BUFFER, v).apply()

    /**
     * 用户是否显式改过「player_play_music」开关。
     * 改过之后型号白名单不再强行覆盖用户选择（见 DeviceCatalog.shouldUseMusicApi）。
     */
    var musicApiExplicit: Boolean
        get() = sp.getBoolean(K_MUSIC_API_EXPLICIT, false)
        set(v) = sp.edit().putBoolean(K_MUSIC_API_EXPLICIT, v).apply()

    /** 兼容模式：强制走 player_play_url（忽略型号白名单），用于排查机型问题 */
    var compatibilityMode: Boolean
        get() = sp.getBoolean(K_COMPAT_MODE, false)
        set(v) = sp.edit().putBoolean(K_COMPAT_MODE, v).apply()

    /** audio_type = MUSIC，让"正在播放"指示灯亮起 */
    var lightUp: Boolean
        get() = sp.getBoolean(K_LIGHT, false)
        set(v) = sp.edit().putBoolean(K_LIGHT, v).apply()

    /** player_play_url 的 type 参数（备用路径） */
    var playUrlType: Int
        get() = sp.getInt(K_PLAY_TYPE, 2)
        set(v) = sp.edit().putInt(K_PLAY_TYPE, v).apply()

    /** 默认音量（0 表示不改动音箱音量） */
    var defaultVolume: Int
        get() = sp.getInt(K_VOLUME, 0)
        set(v) = sp.edit().putInt(K_VOLUME, v).apply()

    var serverPort: Int
        get() = sp.getInt(K_PORT, 8300)
        set(v) = sp.edit().putInt(K_PORT, v).apply()

    /** 首屏「使用步骤」是否收起；前置步骤全部完成后会自动收起一次，之后由用户手动开关 */
    var guideCollapsed: Boolean
        get() = sp.getBoolean(K_GUIDE_COLLAPSED, false)
        set(v) = sp.edit().putBoolean(K_GUIDE_COLLAPSED, v).apply()

    // ---- 登录保护（LoginGuard 用）----
    /** 限速窗口内的登录失败时间戳（毫秒），逗号分隔 */
    var loginFailTimes: List<Long>
        get() = sp.getString(K_LOGIN_FAIL_TIMES, "")
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?: emptyList()
        set(v) = sp.edit().putString(K_LOGIN_FAIL_TIMES, v.joinToString(",")).apply()

    /** 连续登录失败计数（成功即清零） */
    var loginConsecutive: Int
        get() = sp.getInt(K_LOGIN_CONSECUTIVE, 0)
        set(v) = sp.edit().putInt(K_LOGIN_CONSECUTIVE, v).apply()

    /** 连续登录失败到阈值时自动重启投屏链路（自愈） */
    var autoSelfHeal: Boolean
        get() = sp.getBoolean(K_AUTO_SELF_HEAL, true)
        set(v) = sp.edit().putBoolean(K_AUTO_SELF_HEAL, v).apply()

    fun clearToken() {
        sp.edit().remove(K_SERVICE_TOKEN).remove(K_SSECURITY).remove(K_TOKEN_UID).apply()
    }

    private companion object {
        const val K_AUTH_MODE = "auth_mode"
        const val K_USER_ID = "user_id"
        const val K_PASS_TOKEN = "pass_token"
        const val K_ACCOUNT = "account"
        const val K_PASSWORD = "password"
        const val K_SERVICE_TOKEN = "service_token"
        const val K_SSECURITY = "ssecurity"
        const val K_TOKEN_UID = "token_uid"
        const val K_DEVICE_ID = "device_id"
        const val K_DEVICE_NAME = "device_name"
        const val K_HARDWARE = "hardware"
        const val K_UDN = "udn"
        const val K_DLNA_NAME = "dlna_name"
        const val K_MUSIC_API = "music_api"
        const val K_MUSIC_API_EXPLICIT = "music_api_explicit"
        const val K_PROXY = "proxy"
        const val K_BUFFER = "buffer"
        const val K_COMPAT_MODE = "compat_mode"
        const val K_LIGHT = "light"
        const val K_PLAY_TYPE = "play_type"
        const val K_VOLUME = "volume"
        const val K_GUIDE_COLLAPSED = "guide_collapsed"
        const val K_PORT = "port"
        const val K_LOGIN_FAIL_TIMES = "login_fail_times"
        const val K_LOGIN_CONSECUTIVE = "login_consecutive"
        const val K_AUTO_SELF_HEAL = "auto_self_heal"
    }
}
