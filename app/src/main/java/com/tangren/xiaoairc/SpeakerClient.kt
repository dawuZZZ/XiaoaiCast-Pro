package com.tangren.xiaoairc

import com.tangren.xiaoairc.xiaomi.DeviceCatalog
import com.tangren.xiaoairc.xiaomi.MiAccount
import com.tangren.xiaoairc.xiaomi.MiNaClient
import kotlinx.coroutines.delay

/**
 * 单台音箱的控制封装，对应 MiAir 的 SpeakerController。
 *
 * 两个关键经验（来自 MiAir / miservice 的实测）：
 *  1. L05B / L05C / LX05 这批机型要走 player_play_music，直接 player_play_url 不生效；
 *     一旦 deviceId 报错，就自动降级到 player_play_url，避免整条链路卡死。
 *  2. 走 music 接口的机型，pause 实际要调 stop，否则云端状态不更新。
 */
class SpeakerClient(
    private val account: MiAccount,
    private val na: MiNaClient,
    private val prefs: Prefs,
    val deviceId: String,
    val deviceName: String
) {

    companion object {
        /** 从 MiAir 抄来的 audio_id，走 player_play_music 时必须有值 */
        const val DEFAULT_AUDIO_ID = "448161862632079419"
    }

    private var lastVolume = 50
    private var musicApiFailed = false

    /**
     * 是否走 player_play_music。
     * 按型号白名单自动分派（对齐 miair-next 的 _should_use_music_api），
     * 运行中一旦 music 接口失败，本会话降级为 player_play_url。
     */
    val usingMusicApi: Boolean
        get() = !musicApiFailed && DeviceCatalog.shouldUseMusicApi(
            hardware = prefs.hardware,
            forceMusicApi = prefs.useMusicApi,
            compatibilityMode = prefs.compatibilityMode,
            userExplicit = prefs.musicApiExplicit
        )

    suspend fun playUrl(url: String): Boolean {
        if (tryPlay(url)) return true
        LogBus.w("播放请求失败，重新登录后再试一次…")
        if (account.relogin() && tryPlay(url)) return true
        LogBus.e("播放请求最终失败")
        return false
    }

    private suspend fun tryPlay(url: String): Boolean {
        if (usingMusicApi) {
            if (na.playByMusicUrl(deviceId, url, DEFAULT_AUDIO_ID, prefs.lightUp)) {
                LogBus.i("player_play_music 已下发")
                return true
            }
            LogBus.w("player_play_music 失败，本次会话降级为 player_play_url")
            musicApiFailed = true
        }
        val ok = na.playByUrl(deviceId, url, prefs.playUrlType)
        LogBus.i(if (ok) "player_play_url 已下发" else "player_play_url 失败")
        return ok
    }

    /**
     * 暂停。
     *
     * 实测经验（对齐 xiaomusic 的 force_stop_xiaoai 和 MiAir 的注释）：
     *  - 走 player_play_music 投上去的流，**单发 pause 或单发 stop 都可能不生效**，
     *    xiaomusic 的做法是 pause 之后再 stop，两步都发；
     *  - 发完必须回查云端状态确认，否则就是用户遇到的那种
     *    "界面显示暂停了、音箱还在放"。
     */
    suspend fun pause(): Boolean = hardStop("暂停")

    suspend fun stop(): Boolean = hardStop("停止")

    /** pause + stop 双发，然后轮询云端状态确认，最多补 2 次。 */
    private suspend fun hardStop(what: String): Boolean {
        na.playOperation(deviceId, "pause")
        na.playOperation(deviceId, "stop")

        repeat(3) { attempt ->
            delay(700)
            val st = try {
                na.getPlayStatus(deviceId)
            } catch (_: Throwable) {
                null
            }
            if (st == null) {
                LogBus.w("$what 后查不到播放状态，无法确认结果")
                return true
            }
            if (st.first != 1) {
                LogBus.i("$what 成功（云端状态=${st.first}）")
                return true
            }
            LogBus.w("$what 第 ${attempt + 1} 次未生效，云端仍显示播放中，补发一次")
            na.playOperation(deviceId, "pause")
            na.playOperation(deviceId, "stop")
        }
        LogBus.e("$what 失败：云端仍显示播放中，这台固件可能不吃 stop")
        return false
    }

    suspend fun setVolume(volume: Int): Boolean {
        val v = volume.coerceIn(0, 100)
        val ok = na.setVolume(deviceId, v)
        if (ok) lastVolume = v
        LogBus.i(if (ok) "音量 → $v" else "设置音量失败")
        return ok
    }

    suspend fun getVolume(): Int {
        val st = na.getPlayStatus(deviceId) ?: return lastVolume
        if (st.second > 0) lastVolume = st.second
        return lastVolume
    }

    suspend fun getStatus(): Triple<Int, Int, Double>? = na.getPlayStatus(deviceId)

    fun resetMusicApiFlag() {
        musicApiFailed = false
    }
}
