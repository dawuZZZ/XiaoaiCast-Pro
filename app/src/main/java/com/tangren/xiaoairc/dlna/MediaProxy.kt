package com.tangren.xiaoairc.dlna

import com.tangren.xiaoairc.LogBus
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 音频流代理。
 *
 * 两种工作模式：
 *  - **直连模式**（默认，向后兼容）：音箱去拉手机上的短链接，手机边取边吐给音箱。
 *    链路过长 / 需要特定 UA / 临时 token 的 CDN 链接成功率更高，且支持 Range。
 *  - **缓冲模式**（bufferFactory 非空时启用）：边下载到本地文件（MediaBuffer）边供给，
 *    开头几十 KB 落盘即起播。下载到本地后供给稳定，源站不支持 Range 时也能 seek；
 *    总长度确切后，进度条与 seek 更准。
 *    借鉴 miair-next dlna/media_buffer.py 的思路，但改为文件落地 + 渐进式读流以适配手机。
 */
class MediaProxy(
    private val client: OkHttpClient,
    /** 非空即启用缓冲模式；入参为原始音频地址，返回一条 MediaBuffer */
    private val bufferFactory: ((String) -> MediaBuffer)? = null
) {

    class Entry(
        val url: String,
        val offset: Long,
        val durationSec: Double
    ) {
        @Volatile
        var contentLength: Long = -1L

        @Volatile
        var mime: String = "audio/mpeg"
    }

    data class ProxyResponse(
        val code: Int,
        val stream: InputStream?,
        val length: Long,
        val mime: String,
        val contentRange: String?,
        val closeable: AutoCloseable?
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val buffers = ConcurrentHashMap<String, MediaBuffer>()
    private val counter = AtomicLong(0)

    /**
     * HEAD / 元数据探测专用的短超时 client。
     *
     * 由主 client 派生（共享连接池与线程池，开销可忽略），只覆盖读超时：
     * 探测只等响应头、不搬数据，没必要跟流式下载共用 30s 的静默容忍度。
     * 上游对 HEAD 挂起时快速失败，避免占住 NanoHTTPD 的请求线程。
     */
    private val probeClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    val buffered: Boolean get() = bufferFactory != null

    fun register(url: String, offset: Long = 0L, durationSec: Double = 0.0): String {
        val token = "t" + counter.incrementAndGet() + "_" + (1000..9999).random()
        entries[token] = Entry(url, offset, durationSec)
        if (bufferFactory != null) {
            ensureBuffer(url)
        }
        // 简单清理，避免长时间运行堆积
        if (entries.size > 64) {
            val evict = entries.keys.take(entries.size - 48)
            evict.forEach { entries.remove(it) }
        }
        if (buffers.size > 8) {
            // 只保留仍然被 token 引用的地址，其余关闭并删除临时文件
            val keep = entries.values.map { it.url }.toSet()
            val stale = buffers.keys.filter { it !in keep }
            for (k in stale) {
                buffers.remove(k)?.close()
            }
        }
        return token
    }

    fun peek(token: String): Entry? = entries[token]

    fun pathFor(token: String): String = "/media/$token"

    private fun ensureBuffer(url: String): MediaBuffer? {
        val f = bufferFactory ?: return null
        return buffers.computeIfAbsent(url) {
            f(it).also { b -> b.start() }
        }
    }

    /** 释放某地址的缓冲（换歌/停止时调用）。 */
    fun releaseBuffer(url: String) {
        buffers.remove(url)?.close()
    }

    fun releaseAll() {
        buffers.values.forEach { it.close() }
        buffers.clear()
    }

    /**
     * 向源站发起请求。
     * @param virtualRange 客户端请求的范围（相对于"虚拟起点"，即 entry.offset 之后的内容）
     */
    fun request(entry: Entry, virtualRange: String?, headOnly: Boolean): ProxyResponse? {
        if (bufferFactory != null) {
            val r = bufferedRequest(entry, virtualRange, headOnly)
            if (r != null) return r
            // 缓冲未就绪/超时 → 本次退回直连，保证音箱不断流
            LogBus.w("缓冲未就绪，本次改走直连")
        }
        return upstreamRequest(entry, virtualRange, headOnly)
    }

    // ------------------------------------------------------------------
    // 缓冲模式
    // ------------------------------------------------------------------

    private fun bufferedRequest(entry: Entry, virtualRange: String?, headOnly: Boolean): ProxyResponse? {
        val buf = ensureBuffer(entry.url) ?: return null

        var vStart = -1L
        var vEnd = -1L
        if (virtualRange != null) {
            parseRange(virtualRange)?.let {
                vStart = it.first
                vEnd = it.second
            }
        }

        if (headOnly) {
            if (!buf.awaitHeaders(HEAD_TIMEOUT_MS)) return null
            if (buf.error != null) return null
            val total = buf.totalSize
            val virtualTotal = if (total > 0) total - entry.offset else -1L
            if (total <= 0) {
                entry.contentLength = -1
                return ProxyResponse(200, null, -1, buf.mime, null, null)
            }
            entry.contentLength = total
            entry.mime = buf.mime
            val startVirtual = if (vStart >= 0) vStart else 0L
            val endVirtual = virtualTotal - 1
            return ProxyResponse(
                code = if (vStart >= 0) 206 else 200,
                stream = null,
                length = (virtualTotal - startVirtual).coerceAtLeast(0),
                mime = buf.mime,
                contentRange = "bytes $startVirtual-$endVirtual/$virtualTotal",
                closeable = null
            )
        }

        if (buf.error != null) return null

        val absStart = entry.offset + (if (vStart >= 0) vStart else 0L)

        // 确定要保证可读到的末字节
        val ready: Boolean
        val length: Long
        if (vEnd >= 0) {
            // 闭区间（bytes=N-M）：必须保证到 absEnd 都已落盘，读流才不会中途 EOF
            val absEnd = entry.offset + vEnd
            ready = buf.awaitDownloaded(absEnd, RANGE_TIMEOUT_MS)
            length = (absEnd - absStart + 1).coerceAtLeast(0)
        } else {
            // 开区间（bytes=N-）：渐进式供给——等开头几十 KB 落盘就开始给音箱吐数据，
            // 读流跟随下载进度（MediaBuffer.FollowerInputStream），起播不再等整首下完
            ready = buf.awaitStart(absStart, START_TIMEOUT_MS)
            length = if (buf.totalSize > 0) (buf.totalSize - absStart).coerceAtLeast(0) else -1
        }
        if (!ready || buf.downloaded <= absStart) return null

        val bounded = if (buf.totalSize > 0) length.coerceAtMost(buf.totalSize - absStart) else length
        val stream = try {
            buf.openRange(absStart)
        } catch (t: Throwable) {
            LogBus.e("打开缓冲读流失败：${t.message}")
            return null
        }
        entry.contentLength = buf.totalSize
        entry.mime = buf.mime

        val virtualTotal = if (buf.totalSize > 0) buf.totalSize - entry.offset else -1L
        val startVirtual = if (vStart >= 0) vStart else 0L
        val endVirtual = if (bounded > 0) startVirtual + bounded - 1 else -1L
        val cr = if (virtualTotal > 0 && endVirtual >= 0) {
            "bytes $startVirtual-$endVirtual/$virtualTotal"
        } else null

        return ProxyResponse(
            code = if (vStart >= 0) 206 else 200,
            stream = stream,
            length = bounded,
            mime = buf.mime,
            contentRange = cr,
            closeable = null // 交给 NanoHTTPD 关闭
        )
    }

    // ------------------------------------------------------------------
    // 直连模式
    // ------------------------------------------------------------------

    private fun upstreamRequest(entry: Entry, virtualRange: String?, headOnly: Boolean): ProxyResponse? {
        val host = runCatching { java.net.URI(entry.url).host }.getOrNull() ?: ""
        val parsed = virtualRange?.let { parseRange(it) }
        val vStart = parsed?.first ?: -1L
        val vEnd = parsed?.second ?: -1L

        var resp = fetchUpstream(entry, vStart, vEnd, headOnly, PROXY_UA)
        // 源站拒绝（403 等）或回了 HTML（CDN 错误页/风控页）时，换浏览器 UA 再试一次
        if (resp != null && isRejectedUpstream(resp)) {
            LogBus.w("上游 host=$host 返回 ${resp.code}/${resp.header("Content-Type")}，疑似拒绝代理 UA，换浏览器 UA 重试")
            resp.close()
            resp = fetchUpstream(entry, vStart, vEnd, headOnly, MediaBuffer.BROWSER_UA)
        }

        if (resp == null) return null

        if (resp.code != 200 && resp.code != 206) {
            LogBus.e("源站返回 ${resp.code}（host=$host），放弃代理")
            resp.close()
            return null
        }

        val body = resp.body
        // 长度必须按请求类型分别取：
        //  - HEAD 响应没有响应体，OkHttp 对它的 body.contentLength() 恒为 0，
        //    只能读响应头里的 Content-Length，否则转给音箱的长度是假的（0）；
        //  - 流式 GET 则以 body 的实际长度为准。
        val upstreamLen = if (headOnly) {
            resp.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
        } else {
            body?.contentLength() ?: -1L
        }
        val contentType = resp.header("Content-Type")
        if (contentType != null && contentType.isNotEmpty()) {
            entry.mime = contentType.substringBefore(';').trim()
        }
        LogBus.i(
            "上游就绪：host=$host code=${resp.code} type=${entry.mime} " +
                "len=$upstreamLen${if (headOnly) "（HEAD）" else ""}"
        )

        // 学习总长度：整文件 200 响应，或 206 的 Content-Range
        val contentRange = resp.header("Content-Range")
        if (contentRange != null && contentRange.contains("/")) {
            val total = contentRange.substringAfterLast('/').trim().toLongOrNull() ?: -1L
            if (total > 0) entry.contentLength = total
        } else if (resp.code == 200 && upstreamLen > 0 && entry.offset == 0L) {
            entry.contentLength = upstreamLen
        }

        val virtualTotal = if (entry.contentLength > 0) entry.contentLength - entry.offset else -1L
        val startVirtual = if (vStart >= 0) vStart else 0L
        val endVirtual = if (upstreamLen > 0) startVirtual + upstreamLen - 1 else -1L
        val outRange =
            if (virtualTotal > 0 && endVirtual >= 0) "bytes $startVirtual-$endVirtual/$virtualTotal" else null

        val stream: InputStream? = if (headOnly) null else body?.byteStream()

        if (headOnly) resp.close()

        return ProxyResponse(
            code = resp.code,
            stream = stream,
            length = upstreamLen,
            mime = entry.mime,
            contentRange = outRange,
            closeable = if (headOnly) null else resp
        )
    }

    /** 向源站发起一次请求；网络异常时返回 null（已记日志）。 */
    private fun fetchUpstream(
        entry: Entry,
        vStart: Long,
        vEnd: Long,
        headOnly: Boolean,
        ua: String
    ): okhttp3.Response? {
        val upStart = if (vStart >= 0) entry.offset + vStart else entry.offset
        val builder = Request.Builder()
            .url(entry.url)
            .header("Accept", "*/*")
            .header("User-Agent", ua)

        if (upStart > 0 || vStart >= 0) {
            val rangeValue = if (vEnd >= 0) {
                "bytes=$upStart-${entry.offset + vEnd}"
            } else {
                "bytes=$upStart-"
            }
            builder.header("Range", rangeValue)
        }
        if (headOnly) builder.head() else builder.get()

        // 探测类请求走短超时的 probeClient，流式下载走主 client
        val callClient = if (headOnly) probeClient else client

        return try {
            callClient.newCall(builder.build()).execute()
        } catch (t: Throwable) {
            LogBus.e("代理取流失败: ${t.message}")
            null
        }
    }

    /** 视为"源站不欢迎本次请求"：非 200/206，或回了 HTML（CDN 错误页/风控页）。 */
    private fun isRejectedUpstream(resp: okhttp3.Response): Boolean {
        if (resp.code != 200 && resp.code != 206) return true
        val ct = resp.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
        return ct == "text/html"
    }

    /** 估算 seek 目标对应的字节偏移：缓冲模式下用确切长度，直连模式下退回比例估算。 */
    fun byteOffsetFor(entry: Entry, positionSec: Double): Long? {
        bufferFactory?.let { f ->
            val buf = buffers[entry.url]
            if (buf != null && buf.totalSize > 0 && entry.durationSec > 0.0) {
                return buf.byteOffsetFor(positionSec, entry.durationSec)
            }
        }
        val total = entry.contentLength
        if (total <= 0 || entry.durationSec <= 0.0) return null
        val pos = positionSec.coerceIn(0.0, entry.durationSec)
        return ((pos / entry.durationSec) * total).toLong().coerceIn(0L, total - 1)
    }

    private fun parseRange(header: String): Pair<Long, Long>? {
        val h = header.trim().lowercase()
        if (!h.startsWith("bytes=")) return null
        val spec = h.removePrefix("bytes=").split(",").first().trim()
        val parts = spec.split("-")
        val start = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val end = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: -1L
        return start to end
    }

    private companion object {
        const val HEAD_TIMEOUT_MS = 10_000L
        const val RANGE_TIMEOUT_MS = 30_000L

        /** 渐进式起播：等第一批数据落盘的上限，超时回退直连 */
        const val START_TIMEOUT_MS = 15_000L

        /** HEAD / 元数据探测的读超时（秒）：只等响应头，不需要长容忍 */
        private const val PROBE_TIMEOUT_SEC = 10L

        /** 直连模式默认 UA；被源站拒绝时自动换 MediaBuffer.BROWSER_UA 重试 */
        const val PROXY_UA = "XiaoaiDLNA/0.2"
    }
}
