package com.tangren.xiaoairc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.tangren.xiaoairc.dlna.MediaBuffer
import com.tangren.xiaoairc.dlna.MediaProxy
import com.tangren.xiaoairc.dlna.SsdpResponder
import com.tangren.xiaoairc.dlna.UpnpHttpServer
import com.tangren.xiaoairc.dlna.UpnpRenderer
import com.tangren.xiaoairc.xiaomi.LoginGuard
import com.tangren.xiaoairc.xiaomi.MiAccount
import com.tangren.xiaoairc.xiaomi.MiNaClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 投屏服务：把 SSDP 应答器 + DLNA 渲染器 + 音频代理串成一条链路，并保活。
 *
 * 相比最初版本新增：
 *  - 音频缓冲模式（MediaBuffer，文件落地）；
 *  - 子服务热重启（ACTION_RESTART，不退出 App 重建整条链路）；
 *  - 自愈看门狗：连续登录失败到阈值时自动重启链路。
 */
class CastService : Service() {

    companion object {
        const val ACTION_START = "com.tangren.xiaoairc.START"
        const val ACTION_STOP = "com.tangren.xiaoairc.STOP"

        /** 热重启：不退出进程，重建整条投屏链路（改端口/配置后立即生效） */
        const val ACTION_RESTART = "com.tangren.xiaoairc.RESTART"

        private const val CHANNEL_ID = "xiaoairc_cast"
        private const val NOTIF_ID = 0x9A01

        /** 自愈重启的最小间隔：退避窗口，避免凭据错误时反复打登录接口 */
        private const val SELF_HEAL_BACKOFF_MS = 10 * 60_000L

        /**
         * 音频流代理的读写空闲上限（秒）。
         * 与 MediaBuffer.AVAIL_TIMEOUT_MS 取同一量级：正常的音频 CDN 每秒都有数据到达，
         * 静默 30s 基本可判定为死连接，此时快速失败比无限等待更容易让音箱重连恢复。
         */
        private const val MEDIA_IDLE_TIMEOUT_SEC = 30L

        /** 音箱状态轮询间隔：2s，兼顾及时性与云端请求频率 */
        private const val STATUS_POLL_INTERVAL_MS = 2_000L

        @Volatile
        private var _running = false
        val running: Boolean get() = _running

        @Volatile
        var statusText: String = "服务未启动"
            private set

        /** SSDP 是否真的就绪（组播绑定成功）；界面上的引导清单要用 */
        @Volatile
        var ssdpReady: Boolean = false
            private set

        /** http://ip:port，界面状态卡显示与复制用 */
        @Volatile
        var httpBase: String = ""
            private set

        /** 自愈最近一次动作说明，供界面显示 */
        @Volatile
        var lastSelfHeal: String = ""
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: Prefs

    private var account: MiAccount? = null
    private var naClient: MiNaClient? = null
    private var speaker: SpeakerClient? = null
    private var renderer: UpnpRenderer? = null
    private var server: UpnpHttpServer? = null
    private var ssdp: SsdpResponder? = null
    private var mediaProxy: MediaProxy? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var watchdog: Job? = null

    /** 音箱状态轮询协程：喂给渲染器做播放确认 / 进度对齐 / 结束检测 */
    private var statusPoller: Job? = null

    /** 上次自愈重启的时间戳，用于看门狗退避 */
    @Volatile
    private var lastSelfHealAt: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                LogBus.i("收到热重启请求，正在重建投屏链路…")
                teardown()
                startEverything()
            }

            else -> startEverything()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------

    private fun startEverything() {
        if (_running) {
            LogBus.i("投屏服务已在运行")
            return
        }
        goForeground("正在启动…")

        val deviceId = prefs.deviceId
        if (deviceId.isEmpty()) {
            fail("还没有选择音箱：请先在界面上点“刷新音箱列表”并选一台")
            return
        }
        val ip = NetUtil.wifiIpv4(this)
        if (ip.isNullOrEmpty()) {
            fail("拿不到局域网 IP：请确认已连接 Wi-Fi（不要用热点/数据网络）")
            return
        }

        val port = prefs.serverPort
        val name = prefs.dlnaName.ifEmpty { prefs.deviceName.ifEmpty { "小爱音箱" } }
        val udn = if (prefs.udn.isNotEmpty()) prefs.udn else {
            val u = NetUtil.md5Hex("xiaoairc-$deviceId").let {
                "${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-" +
                    "${it.substring(16, 20)}-${it.substring(20, 32)}"
            }
            prefs.udn = u
            u
        }

        acquireLocks()

        val acct = MiAccount(prefs)
        val hasCache = acct.loadCachedToken()
        account = acct
        val na = MiNaClient(acct)
        naClient = na
        val sp = SpeakerClient(acct, na, prefs, deviceId, name)
        speaker = sp

        // 音频流代理的 client。
        //
        // 读/写超时**不能**设为 0（无限）：上游 CDN 接受连接后挂起不吐数据、或音箱停下不再读，
        // 那个请求就会永久阻塞。NanoHTTPD 是「每个请求一个线程」，于是线程与连接只会越堆越多
        //（实测国外源站会让 8300 端口攒下一批无人回收的 CLOSE_WAIT 连接）。
        //
        // OkHttp 的 readTimeout/writeTimeout 是「两次成功读写之间的最大空闲」，不是整个请求的总时长，
        // 所以对流式下载是安全的——只要数据还在流动就不会被打断；静默超过 MEDIA_IDLE_TIMEOUT_SEC 才判定连接已死。
        val mediaClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(MEDIA_IDLE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(MEDIA_IDLE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
        val proxy = if (prefs.bufferEnabled) {
            val bufferDir = File(cacheDir, "buffers")
            MediaProxy(mediaClient) { url -> MediaBuffer(url, bufferDir, mediaClient) }
        } else {
            MediaProxy(mediaClient)
        }
        mediaProxy = proxy

        val rend = UpnpRenderer(udn, name, sp, proxy, prefs, ip, port)
        renderer = rend

        val srv = UpnpHttpServer(port, this, udn, rend, proxy)
        rend.onStateChanged = { srv.broadcastState() }

        try {
            srv.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } catch (t: Throwable) {
            fail("HTTP 服务启动失败（端口 $port 可能被占用）：${t.message}")
            return
        }
        server = srv

        val responder = SsdpResponder(udn, name, ip, port, scope)
        val ssdpOk = responder.start()
        ssdp = responder

        _running = true
        httpBase = "http://$ip:$port"
        ssdpReady = ssdpOk
        statusText = "运行中 · $httpBase · 设备名「$name」" +
            if (proxy.buffered) " · 缓冲模式" else ""
        LogBus.i("投屏链路已就绪：$statusText")
        if (!ssdpOk) {
            LogBus.e("SSDP 启动失败，音乐 App 可能发现不了设备（可尝试重启 Wi-Fi）")
        }
        goForeground(getString(R.string.notif_text))

        // 后台补一次登录与音量同步，不阻塞界面
        scope.launch {
            try {
                // 有缓存 token 就直接用，失效时 minaRequest 会自动重登
                if (hasCache || acct.login()) {
                    LogBus.i("小米账号凭据就绪，链路可用")
                    if (prefs.defaultVolume in 1..100) {
                        sp.setVolume(prefs.defaultVolume)
                    } else {
                        LogBus.i("音箱当前音量 ${sp.getVolume()}")
                    }
                } else {
                    LogBus.e("小米账号登录失败，投送时会再试一次")
                }
            } catch (t: Throwable) {
                LogBus.e("启动后置任务异常：${t.message}")
            }
        }

        startWatchdog()
        startStatusPoller()
    }

    /**
     * 自愈看门狗：每 60s 检查一次连续登录失败计数；
     * 达到阈值且用户开启自愈时，热重启整条链路（对标 miair-next 的 auto restart 思路）。
     * 带 10 分钟退避：凭据错误时避免陷入"重启→再登录失败→再重启"的循环反复打登录接口。
     */
    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive) {
                delay(60_000)
                if (!_running) continue
                if (!prefs.autoSelfHeal) continue
                val now = System.currentTimeMillis()
                if (now - lastSelfHealAt < SELF_HEAL_BACKOFF_MS) continue
                if (LoginGuard.consumeSelfHeal(prefs)) {
                    lastSelfHealAt = now
                    lastSelfHeal = "连续登录失败过多，已自动重启投屏链路（$now；10 分钟内不会再次触发）"
                    LogBus.w(lastSelfHeal)
                    restartPipeline()
                }
            }
        }
    }

    private fun restartPipeline() {
        teardown()
        startEverything()
    }

    /**
     * 音箱状态轮询：每 2s 拉一次小米云端播放状态，喂给渲染器。
     *
     * 渲染器据此完成三件事：① 播放确认（没播起来就补发，治"手机在放音箱哑"）；
     * ② 进度锚定（手机显示跟音箱实际播放对齐）；③ 结束检测（切下一首 / 广播 STOPPED）。
     */
    private fun startStatusPoller() {
        statusPoller?.cancel()
        statusPoller = scope.launch {
            while (isActive) {
                delay(STATUS_POLL_INTERVAL_MS)
                if (!_running) continue
                val sp = speaker ?: continue
                val rend = renderer ?: continue
                val st = try {
                    sp.getStatus()
                } catch (_: Throwable) {
                    null
                }
                try {
                    rend.reportSpeakerStatus(st)
                } catch (t: Throwable) {
                    LogBus.w("状态轮询处理异常：${t.message}")
                }
            }
        }
    }

    private fun teardown() {
        watchdog?.cancel()
        watchdog = null
        statusPoller?.cancel()
        statusPoller = null
        if (!_running && server == null && ssdp == null) {
            releaseLocks()
            return
        }
        try {
            ssdp?.stop()
        } catch (_: Throwable) {
        }
        try {
            server?.stop()
        } catch (_: Throwable) {
        }
        try {
            mediaProxy?.releaseAll()
        } catch (_: Throwable) {
        }
        ssdp = null
        server = null
        renderer = null
        speaker = null
        mediaProxy = null
        naClient = null
        account = null
        releaseLocks()
        _running = false
        ssdpReady = false
        httpBase = ""
        statusText = "服务已停止"
        LogBus.i("投屏服务已停止")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun fail(reason: String) {
        LogBus.e(reason)
        statusText = "启动失败：$reason"
        _running = false
        ssdpReady = false
        httpBase = ""
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XiaoaiDLNA::Cast").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            LogBus.w("获取 WakeLock 失败：${t.message}")
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("XiaoaiDLNA").apply {
                setReferenceCounted(false)
                acquire()
            }
            LogBus.i("MulticastLock 已获取（SSDP 组播必需）")
        } catch (t: Throwable) {
            LogBus.w("获取 MulticastLock 失败：${t.message}（SSDP 可能收不到广播）")
        }
    }

    private fun releaseLocks() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        multicastLock = null
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    // ------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.notif_text) }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, CastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val restartPi = PendingIntent.getService(
            this, 2,
            Intent(this, CastService::class.java).setAction(ACTION_RESTART),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(Notification.Action.Builder(null, "重启", restartPi).build())
            .addAction(Notification.Action.Builder(null, "停止", stopPi).build())
            .build()
    }

    private fun goForeground(text: String) {
        val notification = buildNotification(text)
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else 0
        )
    }
}
