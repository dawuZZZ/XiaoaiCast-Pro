package com.tangren.xiaoairc.dlna

import com.tangren.xiaoairc.LogBus
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 服务端音频缓冲（落地到文件，避免手机内存被打爆）。
 *
 * 为什么需要它（对齐 miair-next 的 dlna/media_buffer.py 思路）：
 *  1. 音箱直接拉音乐平台 CDN 长链接时，中途断流/重定向/需要特定 UA 会导致播放中断；
 *     先把整条音频拉到本地，再由手机稳定地把数据吐给音箱，成功率明显更高。
 *  2. 源站不支持 Range 时，seek 无从谈起；本地缓冲后 Range 完全由我们掌控。
 *  3. 拿到确切总长度后，进度条与 seek 才是准的（不再是"按比例估算"）。
 *
 * 与 Python 版的差异：
 *  - Python 版整段进内存（bytearray）；手机端改为**落地文件 + 分段读**，大文件也不会 OOM；
 *  - Python 版用 ffmpeg 转码；手机端不做转码（MediaCodec 转码成本高、收益低），
 *    源音频本身是什么格式就原样透传（L05B/L05C 不吃 flac 的限制由用户选源决定）。
 *
 * 采用阻塞式实现（后台线程 + 条件等待），以便直接嵌进现有同步的 NanoHTTPD 请求处理路径。
 */
class MediaBuffer(
    private val url: String,
    private val cacheDir: File,
    private val client: OkHttpClient,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) {

    companion object {
        /** 单文件缓冲硬上限：媒体端点无鉴权，远端 Content-Length 不可信，必须限流防撑爆磁盘 */
        const val DEFAULT_MAX_BYTES = 200L * 1024 * 1024

        /** 供 MediaProxy 重试时共用的浏览器 UA */
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** 渐进式起播：开头至少落盘这么多字节才把流交给音箱 */
        const val MIN_START_BYTES = 64 * 1024

        private const val READ_CHUNK = 64 * 1024
        private const val UA = BROWSER_UA

        /** 读流等待新数据的上限：下载停滞超过此时长按 EOF 处理 */
        private const val AVAIL_TIMEOUT_MS = 30_000L
    }

    private val file: File = File(
        cacheDir,
        "xiaoairc-" + Integer.toHexString(url.hashCode()) + "-" + System.nanoTime() + ".buf"
    )
    private val gate = Object()
    private val stopped = AtomicBoolean(false)
    private var raf: RandomAccessFile? = null
    private var worker: Thread? = null

    @Volatile
    var totalSize: Long = -1L // -1 = 未知
        private set

    @Volatile
    var mime: String = "audio/mpeg"
        private set

    @Volatile
    var downloaded: Long = 0L
        private set

    @Volatile
    var complete: Boolean = false
        private set

    @Volatile
    var error: String? = null
        private set

    @Volatile
    var headersReady: Boolean = false
        private set

    private val started = AtomicBoolean(false)

    /** 启动后台下载（幂等）。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        file.parentFile?.mkdirs()
        worker = Thread({ runDownload() }, "MediaBuffer").apply {
            isDaemon = true
            start()
        }
    }

    private fun runDownload() {
        try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "*/*")
                .header("User-Agent", UA)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code != 200 && resp.code != 206) {
                    error = "源站 HTTP ${resp.code}"
                    return
                }
                val body = resp.body
                if (body == null) {
                    error = "空响应体"
                    return
                }
                resp.header("Content-Type")?.takeIf { it.isNotEmpty() }?.let {
                    mime = it.substringBefore(';').trim()
                }
                val len = body.contentLength()
                totalSize = len
                headersReady = true
                signal()

                if (len > maxBytes) {
                    error = "文件过大（$len > 上限 $maxBytes）"
                    LogBus.w("缓冲拒绝下载：$error")
                    return
                }

                val r = RandomAccessFile(file, "rw")
                raf = r
                body.byteStream().use { input ->
                    val buf = ByteArray(READ_CHUNK)
                    while (!stopped.get()) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (downloaded + n > maxBytes) {
                            error = "下载超过上限（$maxBytes bytes），已中止"
                            LogBus.w(error!!)
                            break
                        }
                        r.seek(downloaded)
                        r.write(buf, 0, n)
                        downloaded += n
                        signal()
                    }
                }
                if (error == null && !stopped.get()) {
                    if (totalSize < 0) totalSize = downloaded
                    complete = true
                    LogBus.i("音频缓冲完成：$totalSize bytes（${url.take(80)}…）")
                }
            }
        } catch (t: Throwable) {
            if (!stopped.get()) {
                error = t.message ?: t.javaClass.simpleName
                LogBus.e("音频缓冲下载失败：$error")
            }
        } finally {
            signal()
        }
    }

    private fun signal() {
        synchronized(gate) { gate.notifyAll() }
    }

    /** 等待响应头就绪（拿到 content-type / 总长度）。 */
    fun awaitHeaders(timeoutMs: Long): Boolean =
        waitUntil(timeoutMs) { headersReady || complete || error != null }

    /** 等待已下载字节数覆盖到 [needInclusive]（或结束/出错）。 */
    fun awaitDownloaded(needInclusive: Long, timeoutMs: Long): Boolean =
        waitUntil(timeoutMs) { complete || error != null || downloaded > needInclusive }

    /** 等待整条下载完成（或结束/出错）。 */
    fun awaitComplete(timeoutMs: Long): Boolean =
        waitUntil(timeoutMs) { complete || error != null }

    /**
     * 等待开头可读：[start] 之后落盘了 MIN_START_BYTES（或已完成/出错）即返回。
     * 渐进式供给的关键——起播不等整首下完。
     */
    fun awaitStart(start: Long, timeoutMs: Long): Boolean =
        waitUntil(timeoutMs) { complete || error != null || downloaded > start + MIN_START_BYTES }

    private inline fun waitUntil(timeoutMs: Long, done: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(gate) {
            while (!done()) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) return false
                try {
                    gate.wait(remain)
                } catch (_: InterruptedException) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * 打开一个从 [start] 开始、跟随下载进度的读流（调用方自行限制读取长度）。
     * 读追上写入位置时等新数据落盘而不是提前 EOF，保证"边下边播"。
     */
    fun openRange(start: Long): InputStream = FollowerInputStream(start.coerceAtLeast(0))

    /** 读到写入头（downloaded）之前绝对不返回 EOF；出错时抛异常让连接断开。 */
    private inner class FollowerInputStream(private var pos: Long) : InputStream() {

        private var fis: FileInputStream? = null

        private fun stream(): FileInputStream {
            var s = fis
            if (s == null) {
                s = FileInputStream(file)
                try {
                    s.channel.position(pos)
                } catch (_: Throwable) {
                }
                fis = s
            }
            return s
        }

        override fun read(): Int {
            val b = ByteArray(1)
            val n = read(b, 0, 1)
            return if (n < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0
            // 等有新数据 / 完成 / 出错；超时按 EOF 处理（下载长期停滞时别把音箱吊死）
            if (!waitUntil(AVAIL_TIMEOUT_MS) { complete || error != null || downloaded > pos }) return -1
            if (downloaded <= pos) {
                if (error != null && !complete) throw java.io.IOException("缓冲中断：$error")
                return -1
            }
            // 只读已落盘的部分，绝不越过写入头
            val want = minOf(len.toLong(), downloaded - pos).toInt()
            val got = stream().read(b, off, want)
            if (got < 0) return -1
            pos += got
            return got
        }

        override fun close() {
            try {
                fis?.close()
            } catch (_: Throwable) {
            }
            fis = null
        }
    }

    /** 准确的 seek 字节偏移：总长度已知时按比例精确换算。 */
    fun byteOffsetFor(positionSec: Double, durationSec: Double): Long? {
        val total = totalSize
        if (total <= 0 || durationSec <= 0.0) return null
        val pos = positionSec.coerceIn(0.0, durationSec)
        return ((pos / durationSec) * total).toLong().coerceIn(0L, total - 1)
    }

    fun close() {
        stopped.set(true)
        signal()
        worker?.interrupt()
        try {
            raf?.close()
        } catch (_: Throwable) {
        }
        raf = null
        try {
            file.delete()
        } catch (_: Throwable) {
        }
    }
}
