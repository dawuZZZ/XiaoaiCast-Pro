package com.tangren.xiaoairc.dlna

import android.content.Context
import com.tangren.xiaoairc.LogBus
import com.tangren.xiaoairc.R
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import java.util.UUID

/**
 * 内嵌 HTTP 服务：承载 UPnP 设备描述、SOAP 控制点、GENA 事件订阅、音频流代理。
 * 对应 MiAir 的 dlna/device_server.py + eventing.py。
 */
class UpnpHttpServer(
    port: Int,
    private val context: Context,
    private val udn: String,
    private val renderer: UpnpRenderer,
    private val proxy: MediaProxy
) : NanoHTTPD(port) {

    private data class Subscription(
        val callback: String,
        /** 订阅的是哪个服务：决定事件体的命名空间与内容（AVT 只有状态、RCS 只有音量） */
        val service: String,
        var expireAt: Long,
        var seq: Int = 0
    )

    private val subscriptions = ConcurrentHashMap<String, Subscription>()

    private val notifyClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    /**
     * GENA 通知的发送队列（单线程、按序）。
     *
     * 状态变化可能在毫秒级连发多条（切歌时 STOPPED→TRANSITIONING→PLAYING），
     * 若每条各起一个线程并发发，到达控制点的顺序就不确定了——控制点很可能
     * 最后收到的是较早的状态（比如把界面停在"暂停"）。串行化保证按发生顺序送达。
     */
    private val notifyExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gena-notify").apply { isDaemon = true }
    }

    /** GetPositionInfo 的日志节流（控制点会以十几 Hz 轮询，全打会淹没日志） */
    @Volatile
    private var lastPosLogAt: Long = 0L

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        // 注意：IHTTPSession.method 是 NanoHTTPD.Method 枚举，不是 String
        val method = session.method.name

        return try {
            when {
                uri == "/" -> infoPage()
                uri == "/device/$udn/description.xml" -> {
                    LogBus.i("控制端拉取设备描述：${clientTag(session)}")
                    xml(UpnpConst.deviceDescriptionXml(udn, renderer.friendlyName))
                }
                uri.startsWith("/device/$udn/") && uri.endsWith(".xml") -> scpd(uri, session)
                uri.endsWith("/control") -> control(session)
                uri.endsWith("/event") -> event(session, method)
                uri.startsWith("/media/") -> media(session)
                else -> {
                    // 控制端若在找我们不提供的资源（QPlay 有时会探特定路径），这里能看到
                    LogBus.w("收到未知请求：$method $uri  ${clientTag(session)}")
                    notFound()
                }
            }
        } catch (t: Throwable) {
            LogBus.e("HTTP 处理异常 $uri: ${t.message}")
            NanoHTTPD.newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", "server error: ${t.message}"
            )
        }
    }

    /** 请求方标识：用于定位是哪个音乐 App 在访问、卡在哪一步 */
    private fun clientTag(session: IHTTPSession): String {
        val ip = try {
            session.remoteIpAddress
        } catch (_: Throwable) {
            null
        } ?: "?"
        val ua = session.headers["user-agent"] ?: session.headers["User-Agent"] ?: "?"
        return "ip=$ip ua=${ua.take(90)}"
    }

    // ------------------------------------------------------------------
    // 静态资源
    // ------------------------------------------------------------------

    private fun infoPage(): Response {
        val playing = renderer.currentUri.ifEmpty { "（空）" }
        val html = """
            <html><head><meta charset="utf-8"><title>小爱DLNA</title></head>
            <body style="font-family:sans-serif;padding:24px">
            <h2>小爱DLNA 运行中</h2>
            <p>已伪装成 DLNA 渲染器：<b>${renderer.friendlyName}</b></p>
            <p>UDN: <code>uuid:$udn</code></p>
            <p>当前状态：${renderer.transportState}</p>
            <p>正在播放：$playing</p>
            <hr><p>去手机上的音乐 App 里找"投屏/DLNA/QPlay"按钮即可。</p>
            </body></html>
        """.trimIndent()
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun xml(text: String): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", text)

    private fun notFound(): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")

    /** 服务描述（SCPD）：直接从 MiAir 的模板搬过来放在 res/raw 里。 */
    private fun scpd(uri: String, session: IHTTPSession): Response {
        val name = uri.substringAfterLast('/')
        val resId = when (name) {
            "AVTransport.xml" -> R.raw.avtransport_scpd
            "RenderingControl.xml" -> R.raw.rendering_control_scpd
            "ConnectionManager.xml" -> R.raw.connection_manager_scpd
            else -> return notFound()
        }
        LogBus.i("控制端拉取 SCPD：$name  ${clientTag(session)}")
        val text = context.resources.openRawResource(resId).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        return xml(text)
    }

    // ------------------------------------------------------------------
    // SOAP 控制
    // ------------------------------------------------------------------

    private fun control(session: IHTTPSession): Response {
        var soapAction = session.headers["soapaction"] ?: ""
        val body = readBody(session)

        var serviceUrn = ""
        var action = ""
        if (soapAction.isNotEmpty()) {
            val parsed = parseSoapAction(soapAction)
            serviceUrn = parsed.first
            action = parsed.second
        }
        if (action.isEmpty() || serviceUrn.isEmpty()) {
            // 少数控制端不带 SOAPAction 头，从 body 里捞
            val m = Regex("xmlns:u=\"([^\"]+)\"").find(body)
            if (m != null) serviceUrn = m.groupValues[1]
            action = firstActionName(body)
        }

        val params = parseSoapParams(body)
        LogBus.i("控制指令：$action  $params")

        val (xmlText, httpCode) = runBlocking { dispatch(serviceUrn, action, params) }
        val status = when (httpCode) {
            200 -> Response.Status.OK
            500 -> Response.Status.INTERNAL_ERROR
            else -> Response.Status.lookup(httpCode) ?: Response.Status.INTERNAL_ERROR
        }
        return NanoHTTPD.newFixedLengthResponse(status, "text/xml; charset=utf-8", xmlText)
    }

    private suspend fun dispatch(
        serviceUrn: String,
        action: String,
        params: Map<String, String>
    ): Pair<String, Int> {
        return when (serviceUrn) {
            UpnpConst.AVTRANSPORT_URN -> handleAvTransport(action, params)
            UpnpConst.RENDERING_CONTROL_URN -> handleRenderingControl(action, params)
            UpnpConst.CONNECTION_MANAGER_URN -> handleConnectionManager(action, params)
            else -> UpnpConst.soapFault(UpnpConst.UPNP_ERROR_INVALID_ACTION, "Invalid Service") to 500
        }
    }

    private suspend fun handleAvTransport(action: String, params: Map<String, String>): Pair<String, Int> =
        when (action) {
            "SetAVTransportURI" -> {
                val ok = renderer.setAvTransportUri(
                    params["CurrentURI"] ?: "",
                    params["CurrentURIMetaData"] ?: ""
                )
                if (ok) UpnpConst.soapResponse(UpnpConst.AVTRANSPORT_URN, action, emptyMap()) to 200
                else UpnpConst.soapFault(
                    UpnpConst.UPNP_ERROR_TRANSITION_NOT_AVAILABLE,
                    "Unsupported media type (video not supported)"
                ) to 500
            }

            "Play" -> {
                val ok = renderer.play()
                if (ok) UpnpConst.soapResponse(UpnpConst.AVTRANSPORT_URN, action, emptyMap()) to 200
                else UpnpConst.soapFault(UpnpConst.UPNP_ERROR_ACTION_FAILED, "Play failed") to 500
            }

            "Pause" -> {
                val ok = renderer.pause()
                if (ok) {
                    UpnpConst.soapResponse(UpnpConst.AVTRANSPORT_URN, action, emptyMap()) to 200
                } else {
                    // 如实返回失败，让控制端界面跟着回退，而不是假装暂停成功造成错位
                    UpnpConst.soapFault(
                        UpnpConst.UPNP_ERROR_TRANSITION_NOT_AVAILABLE, "Pause failed"
                    ) to 500
                }
            }

            "Stop" -> {
                val ok = renderer.stop()
                if (ok) {
                    UpnpConst.soapResponse(UpnpConst.AVTRANSPORT_URN, action, emptyMap()) to 200
                } else {
                    UpnpConst.soapFault(
                        UpnpConst.UPNP_ERROR_TRANSITION_NOT_AVAILABLE, "Stop failed"
                    ) to 500
                }
            }

            "Seek" -> {
                val ok = renderer.seek(params["Unit"] ?: "REL_TIME", params["Target"] ?: "00:00:00")
                if (ok) UpnpConst.soapResponse(UpnpConst.AVTRANSPORT_URN, action, emptyMap()) to 200
                else UpnpConst.soapFault(
                    UpnpConst.UPNP_ERROR_SEEK_MODE_NOT_SUPPORTED, "Seek not supported"
                ) to 500
            }

            // QPlay/QQ音乐 会先把下一首预置进来，渲染器在曲末自动接上。
            // 之前这里是空操作 → 曲末无人切歌、音箱把同一首循环，是"不跳下一首"的主因。
            "SetNextAVTransportURI" -> {
                renderer.setNextAvTransportUri(
                    params["NextURI"] ?: "",
                    params["NextURIMetaData"] ?: ""
                )
                UpnpConst.soapResponse(UpnpConst.AVTRANSPORT_URN, action, emptyMap()) to 200
            }

            // 换歌由控制端重新下发 SetAVTransportURI 完成，这里直接回成功
            "Next", "Previous", "SetPlayMode",
            "GetDeviceCapabilities" -> UpnpConst.soapResponse(
                UpnpConst.AVTRANSPORT_URN, action,
                if (action == "GetDeviceCapabilities") mapOf(
                    "PlayMedia" to "NETWORK",
                    "RecMedia" to "NOT_IMPLEMENTED",
                    "RecQualityModes" to "NOT_IMPLEMENTED"
                ) else emptyMap()
            ) to 200

            "GetCurrentTransportActions" -> UpnpConst.soapResponse(
                UpnpConst.AVTRANSPORT_URN, action,
                mapOf("Actions" to renderer.currentTransportActions())
            ) to 200

            "GetTransportInfo" -> UpnpConst.soapResponse(
                UpnpConst.AVTRANSPORT_URN, action, renderer.getTransportInfo()
            ) to 200

            "GetPositionInfo" -> {
                logPositionInfo()
                UpnpConst.soapResponse(
                    UpnpConst.AVTRANSPORT_URN, action, renderer.getPositionInfo()
                ) to 200
            }

            "GetMediaInfo" -> UpnpConst.soapResponse(
                UpnpConst.AVTRANSPORT_URN, action, renderer.getMediaInfo()
            ) to 200

            "GetTransportSettings" -> UpnpConst.soapResponse(
                UpnpConst.AVTRANSPORT_URN, action, renderer.getTransportSettings()
            ) to 200

            else -> {
                LogBus.w("未实现的 AVTransport 动作：$action")
                UpnpConst.soapFault(UpnpConst.UPNP_ERROR_INVALID_ACTION, "Unknown action: $action") to 500
            }
        }

    private suspend fun handleRenderingControl(action: String, params: Map<String, String>): Pair<String, Int> =
        when (action) {
            "GetVolume" -> UpnpConst.soapResponse(
                UpnpConst.RENDERING_CONTROL_URN, action,
                mapOf("CurrentVolume" to renderer.refreshVolume().toString())
            ) to 200

            "SetVolume" -> {
                renderer.setVolume(params["DesiredVolume"]?.toIntOrNull() ?: 50)
                UpnpConst.soapResponse(UpnpConst.RENDERING_CONTROL_URN, action, emptyMap()) to 200
            }

            "GetMute" -> UpnpConst.soapResponse(
                UpnpConst.RENDERING_CONTROL_URN, action,
                mapOf("CurrentMute" to if (renderer.isMuted()) "1" else "0")
            ) to 200

            "SetMute" -> {
                renderer.setMute((params["DesiredMute"] ?: "0") in listOf("1", "true", "True"))
                UpnpConst.soapResponse(UpnpConst.RENDERING_CONTROL_URN, action, emptyMap()) to 200
            }

            "ListPresets" -> UpnpConst.soapResponse(
                UpnpConst.RENDERING_CONTROL_URN, action,
                mapOf("CurrentPresetNameList" to "FactoryDefaults")
            ) to 200

            "SelectPreset" ->
                UpnpConst.soapResponse(UpnpConst.RENDERING_CONTROL_URN, action, emptyMap()) to 200

            else -> {
                LogBus.w("未实现的 RenderingControl 动作：$action")
                UpnpConst.soapFault(UpnpConst.UPNP_ERROR_INVALID_ACTION, "Unknown action: $action") to 500
            }
        }

    private fun handleConnectionManager(action: String, params: Map<String, String>): Pair<String, Int> =
        when (action) {
            "GetProtocolInfo" -> UpnpConst.soapResponse(
                UpnpConst.CONNECTION_MANAGER_URN, action,
                mapOf("Source" to "", "Sink" to UpnpConst.SUPPORTED_PROTOCOLS)
            ) to 200

            "GetCurrentConnectionIDs" -> UpnpConst.soapResponse(
                UpnpConst.CONNECTION_MANAGER_URN, action, mapOf("ConnectionIDs" to "0")
            ) to 200

            "GetCurrentConnectionInfo" -> UpnpConst.soapResponse(
                UpnpConst.CONNECTION_MANAGER_URN, action,
                mapOf(
                    "RcsID" to "0",
                    "AVTransportID" to "0",
                    "ProtocolInfo" to "",
                    "PeerConnectionManager" to "",
                    "PeerConnectionID" to "-1",
                    "Direction" to "Input",
                    "Status" to "OK"
                )
            ) to 200

            else -> UpnpConst.soapFault(
                UpnpConst.UPNP_ERROR_INVALID_ACTION, "Unknown action: $action"
            ) to 500
        }

    // ------------------------------------------------------------------
    // GENA 事件订阅
    // ------------------------------------------------------------------

    private fun event(session: IHTTPSession, method: String): Response {
        pruneExpired()
        val sid = session.headers["sid"]

        // NanoHTTPD 2.3.1 原版 Method 枚举没有 SUBSCRIBE/UNSUBSCRIBE/NOTIFY，
        // 这三个动词会在进入 serve() 之前就被库直接 400 掉。本项目 vendor 的
        // NanoHTTPD.java 已给枚举补齐（见该文件 Method 枚举处的补丁说明），
        // 因此下面的 SUBSCRIBE/UNSUBSCRIBE 分支是可达的，QQ音乐/QPlay 的事件订阅生效。
        when (method) {
            "SUBSCRIBE" -> {
                if (!sid.isNullOrEmpty()) {
                    val sub = subscriptions[sid]
                    if (sub != null) {
                        sub.expireAt = System.currentTimeMillis() + SUB_TIMEOUT_MS
                        LogBus.i("续订事件：$sid")
                        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/xml", "")
                            .apply {
                                addHeader("SID", sid)
                                addHeader("TIMEOUT", "Second-${SUB_TIMEOUT_MS / 1000}")
                            }
                    }
                }
                val callbackRaw = session.headers["callback"] ?: return NanoHTTPD.newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "text/plain", "missing CALLBACK"
                )
                val callback = callbackRaw.trim().removePrefix("<").removeSuffix(">")
                val service = when {
                    session.uri.contains("/AVTransport/") -> UpnpConst.SERVICE_AVT
                    session.uri.contains("/RenderingControl/") -> UpnpConst.SERVICE_RCS
                    session.uri.contains("/ConnectionManager/") -> UpnpConst.SERVICE_CM
                    else -> UpnpConst.SERVICE_AVT
                }
                val newSid = "uuid:" + UUID.randomUUID().toString()
                subscriptions[newSid] = Subscription(
                    callback, service, System.currentTimeMillis() + SUB_TIMEOUT_MS
                )
                LogBus.i("新事件订阅：$service  $callback")
                // 订阅成功后必须立刻推一次初始状态，否则控制端认为设备不响应
                broadcastState()
                return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/xml", "").apply {
                    addHeader("SID", newSid)
                    addHeader("TIMEOUT", "Second-${SUB_TIMEOUT_MS / 1000}")
                }
            }

            "UNSUBSCRIBE" -> {
                if (!sid.isNullOrEmpty()) {
                    subscriptions.remove(sid)
                    LogBus.i("取消订阅：$sid")
                }
                return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/xml", "")
            }

            else -> return NanoHTTPD.newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED, "text/plain", "unsupported"
            )
        }
    }

    /**
     * 状态变化时向所有订阅者推 LastChange。
     *
     * 状态在**这里取快照**再交给发送线程：发送是异步的，如果在发送时才读
     * `renderer.transportState`，等到真正发出去时状态可能已经翻到下一条了
     * （切歌时 STOPPED→PLAYING 只隔几百毫秒），控制点收到的就不是这条事件该有的状态。
     */
    fun broadcastState() {
        if (subscriptions.isEmpty()) return
        val state = renderer.transportState
        val volume = renderer.currentVolume()
        val snapshot = subscriptions.entries.map { it.key to it.value }
        notifyExecutor.execute {
            for ((sid, sub) in snapshot) {
                sub.seq += 1
                // 每个服务的 LastChange 命名空间/内容都不同，按订阅者的服务分别生成
                val body = when (sub.service) {
                    UpnpConst.SERVICE_RCS -> UpnpConst.lastChangeRcs(volume)
                    UpnpConst.SERVICE_CM -> UpnpConst.lastChangeCm()
                    else -> UpnpConst.lastChangeAvt(state)
                }
                try {
                    val req = Request.Builder()
                        .url(sub.callback)
                        .header("Content-Type", "text/xml; charset=\"utf-8\"")
                        .header("NT", "upnp:event")
                        .header("NTS", "upnp:propchange")
                        .header("SID", sid)
                        .header("SEQ", sub.seq.toString())
                        .method("NOTIFY", body.toRequestBody(XML_CT))
                        .build()
                    val resp = notifyClient.newCall(req).execute()
                    val code = resp.code
                    resp.close()
                    if (code !in 200..299) {
                        LogBus.w("事件通知被控制点拒绝：${sub.service} $sid code=$code（状态=$state）")
                    } else {
                        // 逐条记服务与状态：切歌时能看清控制点最终收到的是哪一条
                        LogBus.i("已通知控制点：${sub.service} $state  $sid code=$code")
                    }
                } catch (t: Throwable) {
                    LogBus.w("事件通知发送失败：${sub.service} $sid ${t.message}")
                }
            }
        }
    }

    /**
     * 控制点（网易云）主要靠 RelTime / TrackDuration 判断进度和"是否到头"，
     * 这里节流打一条便于核对（它约十几 Hz 轮询，全打会淹没日志）。
     */
    private fun logPositionInfo() {
        val now = System.currentTimeMillis()
        if (now - lastPosLogAt < 3_000L) return
        lastPosLogAt = now
        LogBus.i(
            "位置上报：RelTime=${renderer.formatTime(renderer.positionSec())}" +
                " / TrackDuration=${renderer.formatTime(renderer.durationSec)}" +
                "  state=${renderer.transportState}"
        )
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        subscriptions.entries.removeIf { it.value.expireAt < now }
    }

    // ------------------------------------------------------------------
    // 音频流代理
    // ------------------------------------------------------------------

    /**
     * 这次取流是不是"从头要整首"（无 Range，或 `bytes=0-`）。
     * 曲末之后，这种请求就等同于"重播本曲"——见 [MediaProxy.Entry.ended]。
     */
    private fun isRestartFromHead(range: String?): Boolean {
        if (range.isNullOrBlank()) return true
        val spec = range.trim().lowercase().removePrefix("bytes=").split(",").first().trim()
        return spec.split("-").firstOrNull()?.toLongOrNull() == 0L
    }

    private fun media(session: IHTTPSession): Response {
        val token = session.uri.removePrefix("/media/").substringBefore('/')
        val entry = proxy.peek(token)
            ?: return NanoHTTPD.newFixedLengthResponse(
                Response.Status.NOT_FOUND, "text/plain", "token 不存在"
            )

        val isHead = session.method == NanoHTTPD.Method.HEAD
        val range = session.headers["range"]
        LogBus.i(
            "音箱来取流：$token  range=${range ?: "无"}${if (isHead) "（HEAD）" else ""}"
        )

        // 曲末/停止后，音箱常拿同一个 token 再从 0 重拉一遍；token 带 seek 偏移，
        // 听感上就是"重播最后几句"。这种"从头请求"一律拒掉，续传请求仍放行。
        if (entry.ended && isRestartFromHead(range)) {
            LogBus.i("拒绝重播请求：$token（本曲已结束，音箱在从头重拉）")
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.NOT_FOUND, "text/plain", "本曲已结束"
            )
        }

        // HEAD 也走真实探测：把源站的长度/Range 信息如实转给音箱，
        // 部分播放器（含 QQ 音乐）先 HEAD 探测再决定怎么取流，残缺的 HEAD 会让它拿不到关键信息
        val pr = proxy.request(entry, range, headOnly = isHead)
            ?: return NanoHTTPD.newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", "源站取流失败"
            )

        try {
            val status = if (pr.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
            val resp: NanoHTTPD.Response
            if (isHead) {
                // HEAD 不发响应体，但长度必须如实声明。
                // NanoHTTPD 是依据 Response 的 contentLength 输出 Content-Length 头的，且
                // vendored 的 sendContentLengthHeaderIfNotAlreadyPresent 是「无条件打印」，
                // 因此这里给空流配上真实长度即可拿到唯一且正确的一条 Content-Length；
                // 若改用 addHeader 手动加，反而会输出两条（HEAD 不会真正读 body，空流安全）。
                resp = NanoHTTPD.newFixedLengthResponse(
                    status,
                    pr.mime,
                    ByteArrayInputStream(ByteArray(0)),
                    if (pr.length > 0) pr.length else 0L
                )
            } else {
                val stream = pr.stream
                if (stream == null) {
                    return NanoHTTPD.newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR, "text/plain", "空流"
                    )
                }
                resp = if (pr.length > 0) {
                    NanoHTTPD.newFixedLengthResponse(status, pr.mime, stream, pr.length)
                } else {
                    // 源站没给长度，只能分块传
                    NanoHTTPD.newChunkedResponse(status, pr.mime, stream)
                }
            }

            resp.addHeader("Accept-Ranges", "bytes")
            // 标准 DLNA 头：严格的控制端（QQ 音乐/QPlay）会检查
            resp.addHeader("transferMode.dlna.org", "Streaming")
            // Content-Range 只允许出现在 206 响应里；200 附带它是非法的，部分播放器会直接拒绝
            if (pr.contentRange != null && status == Response.Status.PARTIAL_CONTENT) {
                resp.addHeader("Content-Range", pr.contentRange)
            }
            return resp
        } finally {
            if (!isHead && pr.stream == null) pr.closeable?.close()
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun readBody(session: IHTTPSession): String {
        return try {
            val map = HashMap<String, String>()
            session.parseBody(map)
            val post = map["postData"]
            if (!post.isNullOrEmpty()) {
                post
            } else {
                // 控制端若用 form-urlencoded 发 SOAP，参数会被拆散，这里捞回来
                map.values.firstOrNull { it.contains("<Envelope") || it.contains("<s:Envelope") } ?: ""
            }
        } catch (t: Throwable) {
            LogBus.w("读取请求体失败：${t.message}")
            ""
        }
    }

    private fun parseSoapAction(header: String): Pair<String, String> {
        val h = header.trim().trim('"')
        return if (h.contains("#")) {
            h.substringBefore("#") to h.substringAfter("#")
        } else {
            "" to h
        }
    }

    /** 从 body 里取 <s:Body> 下第一个元素的标签名 */
    private fun firstActionName(body: String): String {
        val m = Regex("<(?:\\w+:)?([A-Za-z]+)\\s+xmlns:u=").find(body)
        if (m != null) return m.groupValues[1]
        val m2 = Regex("<(?:\\w+:)?([A-Za-z]+)\\s*>").findAll(body).drop(1).firstOrNull()
        return m2?.groupValues?.get(1) ?: ""
    }

    private fun parseSoapParams(body: String): Map<String, String> {
        val out = HashMap<String, String>()
        if (body.isBlank()) return out
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            // 关闭外部实体；部分 ROM 的解析器不认这个 feature，忽略即可
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Throwable) {
            }
            val doc = factory.newDocumentBuilder()
                .parse(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
            val bodyNode = findFirst(doc.documentElement, "Body") ?: return out
            val actionNode = firstElementChild(bodyNode) ?: return out
            var child = firstElementChild(actionNode)
            while (child != null) {
                out[localName(child)] = child.textContent ?: ""
                child = nextElementSibling(child)
            }
        } catch (t: Throwable) {
            LogBus.w("SOAP 参数解析失败（继续按空参数处理）：${t.message}")
        }
        return out
    }

    private fun findFirst(node: Node?, local: String): Element? {
        var current = node
        if (current == null) return null
        if (current.nodeType == Node.ELEMENT_NODE && localName(current) == local) {
            return current as Element
        }
        var child = current.firstChild
        while (child != null) {
            val found = findFirst(child, local)
            if (found != null) return found
            child = child.nextSibling
        }
        return null
    }

    private fun firstElementChild(node: Node): Element? {
        var child = node.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) return child as Element
            child = child.nextSibling
        }
        return null
    }

    private fun nextElementSibling(node: Node): Element? {
        var sib = node.nextSibling
        while (sib != null) {
            if (sib.nodeType == Node.ELEMENT_NODE) return sib as Element
            sib = sib.nextSibling
        }
        return null
    }

    private fun localName(node: Node): String {
        val name = node.nodeName
        val i = name.indexOf(':')
        return if (i >= 0) name.substring(i + 1) else name
    }

    companion object {
        private const val SUB_TIMEOUT_MS = 1800_000L
        private val XML_CT = "text/xml; charset=\"utf-8\"".toMediaType()
    }
}
