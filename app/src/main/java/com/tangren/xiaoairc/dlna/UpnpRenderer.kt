package com.tangren.xiaoairc.dlna

import com.tangren.xiaoairc.LogBus
import com.tangren.xiaoairc.Prefs
import com.tangren.xiaoairc.SpeakerClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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

    // ------------------------------------------------------------------
    // 音箱真实状态锚点（由 CastService 的状态轮询器周期喂入）
    //
    // v0.2.4 起，进度、播放确认、结束检测都改为以"音箱真实状态"为准，
    // 而不是我们自己的墙钟——否则手机显示的位置会领先音箱实际播放。
    // ------------------------------------------------------------------

    /** 最近一次音箱上报的播放位置（秒）；<0 表示未知 */
    @Volatile
    private var speakerPos: Double = -1.0
    @Volatile
    private var speakerPosAt: Long = 0L

    /** 音箱是否可信地上报位置：部分固件对流式 URL 恒报 0，此时退回本机墙钟估算 */
    @Volatile
    private var speakerPosTrusted: Boolean = true
    @Volatile
    private var posMissStreak: Int = 0

    /** 最近一次下发给音箱的代理 URL，播放确认失败时用它补发（不重新生成 token） */
    @Volatile
    private var lastPlayUrl: String = ""

    /** 播放下发后待确认：轮询到音箱未真正播放就补发，最多 REISSUE_MAX 次 */
    @Volatile
    private var confirmPending: Boolean = false
    @Volatile
    private var confirmRetries: Int = 0

    /** 结束检测：连续"已停止"计数、去重标志、上一帧音箱位置（用于识别重播回绕） */
    @Volatile
    private var stoppedStreak: Int = 0
    @Volatile
    private var endingHandled: Boolean = false
    @Volatile
    private var lastSpeakerPos: Double = -1.0

    /** 本曲是否已"提前静音"（曲末前抢先停掉音箱，防它回绕重播；换歌时重置） */
    @Volatile
    private var preEndSilenced: Boolean = false

    /**
     * 下一首（QPlay 的 SetNextAVTransportURI 预置）。
     * 本曲结束时若已预置，则自动切播——这是 QPlay/QQ音乐"连播"的关键：
     * 控制点把下一首预置给渲染器，由渲染器在曲末自动接上，而不是每首都由 App 重新下发。
     */
    @Volatile
    var nextUri: String = ""
        private set
    @Volatile
    var nextMeta: String = ""
        private set

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
            // 换歌：重置结束检测/播放确认/进度锚点，作废上一首的流并释放本地缓冲，
            // 避免"音箱回头去重拉上一首"和临时文件堆积
            resetTransientFlags()
            markCurrentStreamEnded()
            if (previous.isNotEmpty() && previous != uri) {
                proxy.releaseBuffer(previous)
            }
        }
        LogBus.i("收到投送：${uri.take(120)}")
        notifyState()
        return true
    }

    /**
     * QPlay / QQ音乐 会把下一首预置进来，等本曲结束由渲染器自动接上。
     * 之前这里被当空操作吞掉，导致曲末无人切歌、音箱把同一首循环。
     */
    fun setNextAvTransportUri(uri: String, metadata: String) {
        if (isVideoUri(uri)) {
            LogBus.w("预置的下一首是视频链接，已忽略：${uri.take(80)}")
            return
        }
        nextUri = uri
        nextMeta = metadata
        LogBus.i("已预置下一首：${uri.take(120)}")
    }

    /** 重置"换歌/重新播放"时需要清零的瞬时状态（不动 nextUri，那由消费方管理）。 */
    private fun resetTransientFlags() {
        stoppedStreak = 0
        endingHandled = false
        confirmPending = false
        confirmRetries = 0
        posMissStreak = 0
        speakerPos = -1.0
        speakerPosAt = 0L
        lastSpeakerPos = -1.0
        preEndSilenced = false
    }

    /**
     * 把当前正在用的流标记为"本曲已结束"（曲末 / 停止 / 换歌时调用）。
     *
     * 理由见 [MediaProxy.Entry.ended]：音箱把一条流读完后会拿同一个 token 从头重拉，而 token
     * 带 seek 偏移，重拉就变成"把最后那段再放一遍"（用户听到的"结尾重播几句"）。
     * 标记后它的重拉请求会被直接拒掉，只能停——这比等云端确认 stop 快得多（那条要 3~7 秒）。
     */
    private fun markCurrentStreamEnded() {
        val url = lastPlayUrl
        if (url.isEmpty()) return
        val token = url.substringAfterLast("/media/", "")
        if (token.isEmpty()) return
        proxy.markEnded(token)
        LogBus.i("已结束当前流（$token）：音箱若重拉本曲会被拒")
    }

    suspend fun play(): Boolean {
        val uri = currentUri
        if (uri.isEmpty()) {
            LogBus.w("Play 时还没有收到投送地址")
            return false
        }

        // 刻意**不广播 TRANSITIONING**：控制点（网易云）收到过渡态会先把界面画成"暂停/加载"，
        // 而我们的播放是立即开始的（SetAVTransportURI 已把状态置 STOPPED）。
        // 对外只呈现 STOPPED → PLAYING 的干净序列，少一个让界面卡住的机会。
        val resumeFrom = basePosition
        val playUrl = buildPlayUrl(uri, resumeFrom)
        val ok = speaker.playUrl(playUrl)

        if (ok) {
            transportState = UpnpConst.TRANSPORT_STATE_PLAYING
            basePosition = resumeFrom
            playStartedAt = System.currentTimeMillis()
            // 下发给音箱后不在这里死等确认：小米云 API 返回成功 ≠ 音箱已经开始放。
            // 记住 URL，交给状态轮询器确认——若音箱迟迟没进入播放就补发，避免"手机在放、音箱哑"。
            lastPlayUrl = playUrl
            confirmPending = true
            confirmRetries = 0
            stoppedStreak = 0
            endingHandled = false
            preEndSilenced = false
            speakerPos = resumeFrom
            speakerPosAt = System.currentTimeMillis()
            // lastSpeakerPos 保持"未知"：它是"回绕检测"的基准，不能拿本地值去种，
            // 否则音箱上报 0 时会被误判成"从 resumeFrom 回绕到 0"
            lastSpeakerPos = -1.0
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
        // 暂停时停止结束检测与播放确认，避免把"我们主动暂停"误判成曲末
        confirmPending = false
        stoppedStreak = 0
        endingHandled = false
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
        // 主动停止也要作废流：否则音箱把残流读完还会从头重拉一遍
        markCurrentStreamEnded()
        val ok = speaker.stop()
        transportState = UpnpConst.TRANSPORT_STATE_STOPPED
        basePosition = 0.0
        nextUri = ""
        nextMeta = ""
        lastPlayUrl = ""
        resetTransientFlags()
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
            // seek 等于用新偏移重发一次播放，重置结束检测/确认，并把进度锚点拉到目标位置
            lastPlayUrl = playUrl
            confirmPending = true
            confirmRetries = 0
            stoppedStreak = 0
            endingHandled = false
            preEndSilenced = false
            speakerPos = target1
            speakerPosAt = System.currentTimeMillis()
            // 同 play()：不要让 seek 目标污染"回绕检测"基准
            lastSpeakerPos = -1.0
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
        val state = transportState
        val now = System.currentTimeMillis()
        var pos: Double
        if (state == UpnpConst.TRANSPORT_STATE_PLAYING) {
            val fresh = speakerPosTrusted && speakerPos >= 0.0 &&
                (now - speakerPosAt) in 0..SPEAKER_POS_TTL_MS
            pos = if (fresh) {
                // 以音箱真实位置为锚点，两帧之间用本机时钟补间 → 手机显示与音箱对齐
                speakerPos + (now - speakerPosAt) / 1000.0
            } else {
                // 音箱不上报位置（部分固件对流式 URL 恒报 0）或久未刷新时，退回本机墙钟估算
                basePosition + if (playStartedAt > 0) (now - playStartedAt) / 1000.0 else 0.0
            }
        } else {
            pos = basePosition
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

    // ------------------------------------------------------------------
    // 音箱状态回调（由 CastService 的轮询器周期调用）
    // ------------------------------------------------------------------

    /**
     * 喂入音箱真实状态，承担三件事：
     *  1. **播放确认**：刚下发播放后音箱迟迟没进入播放，就补发播放命令（最多 REISSUE_MAX 次），
     *     解决"手机已经在放、音箱没声"的静默失败；
     *  2. **进度锚点**：记录真实位置供 [positionSec] 对齐；固件不上报位置时自动退回墙钟；
     *  3. **结束检测**：连续停止 / 位置回绕 / 到达曲长 时判定本曲结束，切下一首或广播 STOPPED。
     *
     * @param st (status, volume, position秒)；status: 0=停止 1=播放 2=暂停。null=本次没拿到。
     */
    suspend fun reportSpeakerStatus(st: Triple<Int, Int, Double>?) {
        if (st == null) return
        val status = st.first
        val position = st.third
        val now = System.currentTimeMillis()

        // 1) 播放确认：只在音箱明确报"已停止"时才补发，避免把"还在缓冲"误当失败造成重复起播
        if (transportState == UpnpConst.TRANSPORT_STATE_PLAYING && confirmPending) {
            if (status == 1) {
                LogBus.i("音箱已确认进入播放（下发后 ${now - playStartedAt}ms）")
                confirmPending = false
                confirmRetries = 0
                // 补发一次 PLAYING：切歌瞬间发的事件可能到达得太早（控制点的状态机还没就位），
                // 等音箱**真的**开始播了再报一次权威状态，界面才有机会纠正过来。幂等，无副作用。
                notifyState()
            } else if (status == 0 && now - playStartedAt > CONFIRM_GRACE_MS) {
                if (confirmRetries < REISSUE_MAX && lastPlayUrl.isNotEmpty()) {
                    confirmRetries++
                    LogBus.w("下发后音箱未进入播放（状态=0），第 ${confirmRetries} 次补发播放命令")
                    runCatching { speaker.playUrl(lastPlayUrl) }
                } else {
                    LogBus.e("多次补发后音箱仍未播放，判定本次投送失败")
                    confirmPending = false
                    transportState = UpnpConst.TRANSPORT_STATE_STOPPED
                    basePosition = 0.0
                    notifyState()
                    return
                }
            }
        }

        if (transportState != UpnpConst.TRANSPORT_STATE_PLAYING) return

        // 1.5) 曲末前"提前静音"
        //
        // 实测：音箱把一条流预读完后，会在**曲末前约 0.7 秒**就回头重拉本曲开始重播；
        // 而拖过进度的 token 只剩最后那段，听感上就是"最后几句又放了一遍"。
        // stop 命令传到音箱要 ~1 秒，等曲末再发必然晚一步——所以提前把声音停掉。
        // 注意这里是**纯静音**：不改 transportState、不发事件，控制点仍按正常曲末流程
        // 收到 STOPPED 并切歌，界面表现不受影响。
        if (!preEndSilenced && durationSec > 0.0 &&
            positionSec() >= durationSec - PRE_END_SILENCE_SEC
        ) {
            preEndSilenced = true
            LogBus.i("曲末前 ${PRE_END_SILENCE_SEC}s 提前静音（防音箱回绕重播）")
            runCatching { speaker.stopFast() }
        }

        // 2) 进度锚点 + 位置可信度
        if (status == 1) {
            if (position > 0.5) {
                speakerPos = position
                speakerPosAt = now
                posMissStreak = 0
                if (!speakerPosTrusted) LogBus.i("音箱恢复上报播放位置，进度重新对齐")
                speakerPosTrusted = true
            } else {
                posMissStreak++
                if (posMissStreak >= POS_MISS_MAX && speakerPosTrusted) {
                    LogBus.w("音箱对当前流不上报播放位置，进度改用本机时钟估算")
                    speakerPosTrusted = false
                }
            }
        }

        // 3) 结束检测
        // 3a) 连续两次"已停止"（我们并未主动 stop；暂停走状态 2，不会误判）
        if (status == 0) {
            stoppedStreak++
            if (stoppedStreak >= STOP_STREAK_MAX) {
                handleTrackEnd()
                return
            }
        } else {
            stoppedStreak = 0
        }

        // 3b) 位置回绕：同一首被重播。
        //     **只在音箱可信上报位置时才判**——固件对流式 URL 恒报 0 时，会把"上报 0"误当成
        //     "进度从后段跳到 0"而错误打断播放（v0.2.4 首版就在这里把拖进度条后的播放误停了）。
        if (status == 1 && speakerPosTrusted) {
            if (lastSpeakerPos > 0 && position in 0.0..(lastSpeakerPos - LOOP_BACK_SEC) &&
                (durationSec <= 0.0 || lastSpeakerPos >= durationSec * 0.5)
            ) {
                LogBus.w("检测到音箱把同一首重播（进度 ${lastSpeakerPos.toInt()}→${position.toInt()}s），打断循环")
                handleTrackEnd()
                return
            }
            lastSpeakerPos = position
        }

        // 3c) 本机时钟推进到曲长。音箱不上报位置时，这是唯一可靠的"曲末"信号；
        //     不依赖音箱状态——部分固件曲末报的是"暂停(2)"而非"停止(0)"，靠状态会漏判。
        if (durationSec > 0.0 && positionSec() >= durationSec - END_GRACE_SEC) {
            LogBus.i("进度已到曲末（${positionSec().toInt()}/${durationSec.toInt()}s），按本曲结束处理")
            handleTrackEnd()
        }
    }

    /**
     * 本曲结束：优先自动切到预置的下一首（QPlay 连播）；没有预置则广播 STOPPED，
     * 并把音箱停掉。
     *
     * 这里有两道防"抢时序"的保护（都是实测踩出来的）：
     *  1. stop 用 [STOP_TIMEOUT_MS] 限时——小米云可能因"云端仍显示播放中"重试好几秒，
     *     实测卡过 8 秒；
     *  2. stop 前后校验 [currentUri]——如果这期间控制点已经把下一首切好（网易云收到
     *     STOPPED 后 0.5 秒就切歌），这次曲末处理就已经过时，直接放弃，绝不去打断新歌。
     */
    private suspend fun handleTrackEnd() {
        if (endingHandled) return
        endingHandled = true
        stoppedStreak = 0
        confirmPending = false

        val uriAtEnd = currentUri

        // 第一件事就是作废当前流：音箱读完流的那一刻就会回头重拉（日志实测与曲末判定
        // 只差十几毫秒），此时它拉不到数据就只能停——这是拦"回绕重播"唯一来得及的手段。
        markCurrentStreamEnded()

        val nUri = nextUri
        val nMeta = nextMeta
        if (nUri.isNotEmpty()) {
            nextUri = ""
            nextMeta = ""
            LogBus.i("本曲结束，自动切到预置的下一首：${nUri.take(100)}")
            setAvTransportUri(nUri, nMeta) // 内部会重置结束检测标志/进度锚点
            if (!play()) {
                transportState = UpnpConst.TRANSPORT_STATE_STOPPED
                basePosition = 0.0
                notifyState()
            }
            return
        }

        // 无条件补一次 stop：L05B 这类固件把同一 URL 放完会自己从头重播（实测每 ~27s 重新拉一次流），
        // 而它此刻往往**不上报"正在播放"**，只按状态判断会漏停，导致"最后一句歌词反复放"。
        // 曲末要抢时间：先"只下发不确认"地快速停一次（几百毫秒）。
        // 音箱把流读完会立刻回头重放本曲，若等 hardStop 那套"轮询云端确认"（实测 3~7 秒），
        // 它早把最后几句重放完了——用户听到的"结尾重播最后几句"就是这么来的。
        runCatching { speaker.stopFast() }

        // 限时执行：卡住的 stop 会一直拖到控制点切完歌才回来，那时再停就是误伤新歌。
        val stopResult = runCatching { withTimeoutOrNull(STOP_TIMEOUT_MS) { speaker.stop() } }.getOrNull()
        if (stopResult == null) {
            LogBus.w("曲末补发 stop 超时（${STOP_TIMEOUT_MS}ms），不再阻塞曲末处理")
        }

        if (currentUri != uriAtEnd) {
            // 控制点已经把下一首推上来了：这次曲末处理作废，状态交给新一轮播放去写
            LogBus.i("曲末处理期间控制点已切歌，放弃本次 STOPPED（避免打断新歌）")
            return
        }

        transportState = UpnpConst.TRANSPORT_STATE_STOPPED
        // 关键：曲末位置**停在曲长上，不要归零**。
        // 部分控制点（网易云）不订阅 GENA 事件，只靠轮询 GetPositionInfo 的 RelTime/TrackDuration
        // 判断到头——若这里把位置清 0，它看到的是"进度突然回到 00:00:00"，既不认为到头也不会切歌，
        // 会一直卡在"正在投放"里干等。RelTime 稳在 TrackDuration 才能让它判定曲末、自动下一首。
        basePosition = if (durationSec > 0.0) durationSec else 0.0
        lastPlayUrl = ""
        notifyState()
        LogBus.i("本曲结束（无预置下一首），已置为 STOPPED（位置停在 ${formatTime(basePosition)}）")
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
        "NextURI" to nextUri,
        "NextURIMetaData" to nextMeta,
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
        nextUri = ""
        nextMeta = ""
        lastPlayUrl = ""
        basePosition = 0.0
        durationSec = 0.0
        resetTransientFlags()
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

    private companion object {
        /** 下发播放后多久才开始判"没播起来"（给小米云传播/音箱拉流留出余量） */
        const val CONFIRM_GRACE_MS = 1500L

        /** 播放确认失败时最多补发几次 */
        const val REISSUE_MAX = 3

        /** 连续多少次音箱位置≈0 就判定"该固件不上报位置" */
        const val POS_MISS_MAX = 3

        /** 连续多少次"已停止"判定为曲末（防抖，避免网络闪断误判） */
        const val STOP_STREAK_MAX = 2

        /** 进度回绕超过这么多秒，视为同一首重播 */
        const val LOOP_BACK_SEC = 30.0

        /** 音箱位置锚点的有效期：超过就退回墙钟估算（配合 2s 轮询，容忍个别丢帧） */
        const val SPEAKER_POS_TTL_MS = 6000L

        /** 本机时钟到曲长的容差：进入这个窗口就按"曲末"处理（仅抵消本机时钟相对音箱的起播领先） */
        const val END_GRACE_SEC = 0.5

        /** 曲末补发 stop 的限时：超时就放弃，避免拖住曲末处理、进而误伤控制点已切好的新歌 */
        const val STOP_TIMEOUT_MS = 3000L

        /**
         * 曲末前多久"提前静音"。
         *
         * 取值要盖住三段时间：音箱回绕发生在曲末前 ~0.7s + stop 下发 ~0.6s + 云端传播 ~0.3s，
         * 所以 2.0s 比较稳。代价是结尾最后约 2 秒会被静音——对歌曲尾奏/淡出基本听不出来，
         * 但换来"不再重播最后几句"。
         */
        const val PRE_END_SILENCE_SEC = 2.0
    }
}
