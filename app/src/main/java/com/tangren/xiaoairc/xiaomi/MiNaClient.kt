package com.tangren.xiaoairc.xiaomi

import com.tangren.xiaoairc.LogBus
import org.json.JSONArray
import org.json.JSONObject

data class MiDevice(
    val deviceId: String,
    val miotDid: String,
    val name: String,
    val hardware: String,
    val model: String
) {
    fun label(): String {
        val n = name.ifEmpty { "小爱音箱" }
        val h = hardware.ifEmpty { model }
        return "$n  ($h)"
    }
}

/**
 * 小爱音箱控制（MiNA / micoapi）。
 *
 * 接口签名对齐 miservice：
 *   ubus: POST /remote/ubus  form = deviceId, message(json), method, path
 *   播放: mediaplayer/{player_play_music | player_play_url | player_play_operation | player_set_volume | player_get_play_status}
 */
class MiNaClient(private val account: MiAccount) {

    suspend fun deviceList(): List<MiDevice> {
        val json = account.minaRequest("/admin/v2/device_list?master=0", null) ?: return emptyList()
        val data: JSONArray = json.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<MiDevice>(data.length())
        for (i in 0 until data.length()) {
            val d = data.optJSONObject(i) ?: continue
            val deviceId = d.optString("deviceID")
            if (deviceId.isEmpty()) continue
            out += MiDevice(
                deviceId = deviceId,
                miotDid = d.optString("miotDID"),
                name = d.optString("name"),
                hardware = d.optString("hardware"),
                model = d.optString("model")
            )
        }
        LogBus.i("云端返回 ${out.size} 台设备")
        return out
    }

    private suspend fun ubus(
        deviceId: String,
        method: String,
        path: String,
        message: JSONObject
    ): JSONObject? {
        val form = linkedMapOf(
            "deviceId" to deviceId,
            "message" to message.toString(),
            "method" to method,
            "path" to path
        )
        return account.minaRequest("/remote/ubus", form)
    }

    /**
     * player_play_music：L05B / L05C / LX05 这批机型必须走这个接口，
     * 直接 player_play_url 会返回错误码。
     */
    suspend fun playByMusicUrl(
        deviceId: String,
        url: String,
        audioId: String,
        lightUp: Boolean
    ): Boolean {
        val item = JSONObject().apply {
            put("item_id", JSONObject().apply {
                put("audio_id", audioId)
                put("cp", JSONObject().apply {
                    put("album_id", "-1")
                    put("episode_index", 0)
                    put("id", "355454500")
                    put("name", "xiaowei")
                })
            })
            put("stream", JSONObject().apply { put("url", url) })
        }
        val music = JSONObject().apply {
            put("payload", JSONObject().apply {
                // 置为 MUSIC 会让音箱点亮"正在播放"指示灯
                put("audio_type", if (lightUp) "MUSIC" else "")
                put("audio_items", JSONArray().apply { put(item) })
                put("list_params", JSONObject().apply {
                    put("listId", "-1")
                    put("loadmore_offset", 0)
                    put("origin", "xiaowei")
                    put("type", "MUSIC")
                })
            })
            put("play_behavior", "REPLACE_ALL")
        }
        val message = JSONObject().apply {
            put("startaudioid", audioId)
            put("music", music.toString())
        }
        val ret = ubus(deviceId, "player_play_music", "mediaplayer", message) ?: return false
        return ret.optInt("code", -1) == 0
    }

    /** player_play_url：老机型/兼容模式走这条。 */
    suspend fun playByUrl(deviceId: String, url: String, type: Int): Boolean {
        val message = JSONObject().apply {
            put("url", url)
            put("type", type)
            put("media", "app_ios")
        }
        val ret = ubus(deviceId, "player_play_url", "mediaplayer", message) ?: return false
        return ret.optInt("code", -1) == 0
    }

    /** 让音箱播报一句话（mibrain/text_to_speech），用来验证"账号 + deviceId"这条链路。 */
    suspend fun textToSpeech(deviceId: String, text: String): Boolean {
        val message = JSONObject().apply { put("text", text) }
        val ret = ubus(deviceId, "text_to_speech", "mibrain", message) ?: return false
        return ret.optInt("code", -1) == 0
    }

    suspend fun playOperation(deviceId: String, action: String): Boolean {
        val message = JSONObject().apply {
            put("action", action) // play / pause / stop
            put("media", "app_ios")
        }
        val ret = ubus(deviceId, "player_play_operation", "mediaplayer", message) ?: return false
        return ret.optInt("code", -1) == 0
    }

    suspend fun setVolume(deviceId: String, volume: Int): Boolean {
        val message = JSONObject().apply {
            put("volume", volume.coerceIn(0, 100))
            put("media", "app_ios")
        }
        val ret = ubus(deviceId, "player_set_volume", "mediaplayer", message) ?: return false
        return ret.optInt("code", -1) == 0
    }

    /** 返回 (status, volume, position秒)；status: 0=停止 1=播放 2=暂停。失败返回 null。 */
    suspend fun getPlayStatus(deviceId: String): Triple<Int, Int, Double>? {
        val message = JSONObject().apply { put("media", "app_ios") }
        val ret = ubus(deviceId, "player_get_play_status", "mediaplayer", message) ?: return null
        if (ret.optInt("code", -1) != 0) return null
        val infoStr = ret.optJSONObject("data")?.optString("info") ?: return null
        if (infoStr.isEmpty()) return null
        val info = try {
            JSONObject(infoStr)
        } catch (_: Throwable) {
            return null
        }
        val status = info.optInt("status", 0)
        val volume = info.optInt("volume", 0)
        var position = info.optDouble("position", 0.0)
        if (position.isNaN()) position = 0.0
        // 部分固件把时长放在 duration/play_time 里，做个兜底
        if (position <= 0.0) {
            val t = info.optDouble("play_time", 0.0)
            if (!t.isNaN()) position = t
        }
        return Triple(status, volume, position)
    }
}
