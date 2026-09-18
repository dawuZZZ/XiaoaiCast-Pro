package com.tangren.xiaoairc.dlna

import com.tangren.xiaoairc.LogBus
import com.tangren.xiaoairc.Prefs
import com.tangren.xiaoairc.SpeakerClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * DLNA 渲染器状态机：对外是一个"音箱"，对内把指令翻译成小米云 API 调用。
 *
 * 对应 MiAir 的 dlna/renderer.py。
 */
class UpnpRenderer(
    val udn: String,
    val friendlyName: String,
    private val speaker: SpeakerClient,
    private val proxy: MediaProxy,
    private val prefs: Prefs,
    private val serverHost: String,
    private val serverPort: Int
) {

    private val lock = Mutex()

    @Volatile
    var transportState: String = UpnpConst.TRANSPORT_STATE_NO_MEDIA
        private set

    @Volatile
    var currentUri: String = ""
        private set

    @Volatile
    var currentMeta: String = ""
        private set

    @Volatile
    var durationSec: Double = 0.0
        private set

    @Volatile
    var volume: Int = 50
        private set

    @Volatile
    var mute: Boolean = false
        private set

    private var basePosition: Double = 0.0
    private var playStartedAt: Long = 0L

    /** GENA 事件回调，由 HTTP 服务注入 */
    var onStateChanged: (() -> Unit)? = null

    private val videoExtensions = setOf(
        ".mp4", ".mov", ".avi", ".mkv", ".flv", ".wmv", ".m4v", ".3gp", ".ts", ".mts", ".m2ts"
    )

    private fun isVideoUri(uri: String): Boolean {
        val lower = uri.lowercase()
        if (videoExtensions.any { lower.endsWith(it) || lower.contains("$it?") || lower.contains("$it&") }) return true
        return lower.contains("video/")
    }

    private fun notifyState() {
        try {
            onStateChanged?.invoke()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------
    // AVTransport
    // ------------------------------------------------------------------

    suspend fun setAvTransportUri(uri: String, metadata: String): Boolean {
        if (isVideoUri(uri)) {
            LogBus.w("这是视频链接，音箱放不了，已拒绝：${uri.take(80)}")
            return false
        }
        lock.withLock {
            val previous = currentUri
            currentUri = uri
            currentMeta = metadata
            transportState = UpnpConst.TRANSPORT_STATE_STOPPED
            basePosition = 0.0
            durationSec = parseDurationFromMetadata(metadata)
            // 换歌：释放上一首的本地缓冲，避免临时文件堆积
            if (previous.isNotEmpty() && previous != uri) {
                proxy.releaseBuffer(previous)
            }
        }
        LogBus.i("收到投送：${uri.take(120)}")
        notifyState()
        return true
    }

    suspend fun play(): Boolean {
        val uri = currentUri
        if (uri.isEmpty()) {
            LogBus.w("Play 时还没有收到投送地址")
            return false
        }

        transportState = UpnpConst.TRANSPORT_STATE_TRANSITIONING
        notifyState()

        val resumeFrom = basePosition
        val playUrl = buildPlayUrl(uri, resumeFrom)
        val ok = speaker.playUrl(playUrl)

        if (ok) {
            transportState = UpnpConst.TRANSPORT_STATE_PLAYING
            basePosition = resumeFrom
            playStartedAt = System.currentTimeMillis()
        } else {
            transportState = UpnpConst.TRANSPORT_STATE_STOPPED
        }
        notifyState()
        return ok
    }

    suspend fun pause(): Boolean {
        val ok = speaker.pause()
        if (basePosition >= 0) {
            basePosition = positionSec()
        }
        // 只有确认停下来了才改状态：否则界面显示暂停、音箱还在放，是最容易让人困惑的错位
        if (ok) {
            transportState = UpnpConst.TRANSPORT_STATE_PAUSED
        } else {
            LogBus.w("暂停未生效，保持 PLAYING 状态，避免界面与音箱不一致")
        }
        notifyState()
        return ok
    }

    suspend fun stop(): Boolean {
        val ok = speaker.stop()
        transportState = UpnpConst.TRANSPORT_STATE_STOPPED
        basePosition = 0.0
        notifyState()
        return ok
    }

    suspend fun seek(unit: String, target: String): Boolean {
        val target1 = parseTime(target)
        if (target1 == null) {
            LogBus.w("无法解析 seek 目标：$unit $target")
            return false
        }
        val uri = currentUri
        if (uri.isEmpty()) return false

        val wasPlaying = transportState == UpnpConst.TRANSPORT_STATE_PLAYING
        val playUrl = buildPlayUrl(uri, target1)
        val ok = speaker.playUrl(playUrl)
        if (ok) {
            basePosition = target1
            playStartedAt = System.currentTimeMillis()
            if (!wasPlaying) {
                transportState = UpnpConst.TRANSPORT_STATE_PLAYING
            }
            notifyState()
            LogBus.i("已跳转到 ${formatTime(target1)}")
        }
        return ok
    }

    private suspend fun buildPlayUrl(uri: String, positionSec: Double): String {
        if (!prefs.proxyEnabled) return uri

        var offset = 0L
        if (positionSec > 0.5) {
            offset = probeOffset(uri, positionSec) ?: 0L
        }
        val token = proxy.register(uri, offset, durationSec)
        val url = "http://$serverHost:$serverPort${proxy.pathFor(token)}"
        LogBus.i("代理播放地址：$url" + if (offset > 0) "（偏移 $offset 字节）" else "")
        return url
    }

    /** 只有知道资源大小才能按比例算 seek 偏移；先 HEAD，不支持就退化成 Range: 0-0 探长度。 */
    private fun probeOffset(uri: String, positionSec: Double): Long? {
        val probe = MediaProxy.Entry(uri, 0L, durationSec)
        try {
            var resp = proxy.request(probe, null, headOnly = true)
            if (resp == null || probe.contentLength <= 0) {
                resp?.closeable?.close()
                resp?.stream?.close()
                resp = proxy.request(probe, "bytes=0-0", headOnly = false)
            }
            // 直连模式关 OkHttp 响应即可；缓冲模式的 stream 是本地文件流，必须显式关闭
            resp?.closeable?.close()
            resp?.stream?.close()
        } catch (t: Throwable) {
            LogBus.w("探测资源大小失败，seek 忽略：${t.message}")
            return null
        }
        if (probe.contentLength <= 0) return null
        return proxy.byteOffsetFor(probe, positionSec)
    }

    // ------------------------------------------------------------------
    // RenderingControl
    // ------------------------------------------------------------------

    suspend fun setVolume(v: Int): Boolean {
        val target = v.coerceIn(0, 100)
        val ok = speaker.setVolume(target)
        if (ok) volume = target
        notifyState()
        return ok
    }

    suspend fun refreshVolume(): Int {
        volume = speaker.getVolume()
        return volume
    }

    // 方法名不能叫 getVolume/getMute：会和 volume/mute 属性的 getter 撞 JVM 签名
    fun currentVolume(): Int = volume

    fun isMuted(): Boolean = mute

    suspend fun setMute(m: Boolean): Boolean {
        mute = m
        val ok = speaker.setVolume(if (m) 0 else 50)
        notifyState()
        return ok
    }

    // ------------------------------------------------------------------
    // 查询类
    // ------------------------------------------------------------------

    fun positionSec(): Double {
        var pos = basePosition
        if (transportState == UpnpConst.TRANSPORT_STATE_PLAYING && playStartedAt > 0) {
            pos += (System.currentTimeMillis() - playStartedAt) / 1000.0
        }
        if (durationSec > 0) pos = pos.coerceAtMost(durationSec)
        return pos.coerceAtLeast(0.0)
    }

    fun formatTime(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    fun getTransportInfo(): Map<String, String> = mapOf(
        "CurrentTransportState" to transportState,
        "CurrentTransportStatus" to "OK",
        "CurrentSpeed" to "1"
    )

    fun getPositionInfo(): Map<String, String> {
        val rel = formatTime(positionSec())
        return mapOf(
            "Track" to "1",
            "TrackDuration" to formatTime(durationSec),
            "TrackMetaData" to currentMeta,
            "TrackURI" to currentUri,
            "RelTime" to rel,
            "AbsTime" to rel,
            "RelCount" to "2147483647",
            "AbsCount" to "2147483647"
        )
    }

    fun getMediaInfo(): Map<String, String> = mapOf(
        "NrTracks" to "1",
        "MediaDuration" to formatTime(durationSec),
        "CurrentURI" to currentUri,
        "CurrentURIMetaData" to currentMeta,
        "NextURI" to "",
        "NextURIMetaData" to "",
        "PlayMedium" to "NETWORK",
        "RecordMedium" to "NOT_IMPLEMENTED",
        "WriteStatus" to "NOT_IMPLEMENTED"
    )

    fun getTransportSettings(): Map<String, String> = mapOf(
        "PlayMode" to "NORMAL",
        "RecQualityMode" to "NOT_IMPLEMENTED"
    )

    fun currentTransportActions(): String = "Play,Stop,Pause,Seek"

    fun resetToIdle() {
        transportState = UpnpConst.TRANSPORT_STATE_NO_MEDIA
        currentUri = ""
        currentMeta = ""
        basePosition = 0.0
        durationSec = 0.0
        notifyState()
    }

    // ------------------------------------------------------------------
    // 元数据解析
    // ------------------------------------------------------------------

    private fun parseDurationFromMetadata(metadata: String): Double {
        if (metadata.isEmpty()) return 0.0
        // 常见形式：res duration="0:03:45.000"
        val m = Regex("duration\\s*=\\s*\"([0-9:.]+)\"").find(metadata) ?: return 0.0
        return parseTime(m.groupValues[1]) ?: 0.0
    }

    /** 支持 "H:MM:SS.mmm" / "MM:SS" / 纯秒数 */
    private fun parseTime(raw: String): Double? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        s.toDoubleOrNull()?.let { return it }
        val parts = s.split(":")
        return try {
            when (parts.size) {
                3 -> parts[0].toDouble() * 3600 + parts[1].toDouble() * 60 + parts[2].toDouble()
                2 -> parts[0].toDouble() * 60 + parts[1].toDouble()
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
