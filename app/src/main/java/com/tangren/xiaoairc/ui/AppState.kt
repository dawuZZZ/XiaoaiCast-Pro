package com.tangren.xiaoairc.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.tangren.xiaoairc.CastService
import com.tangren.xiaoairc.LogBus
import com.tangren.xiaoairc.Prefs
import com.tangren.xiaoairc.util.Masking
import com.tangren.xiaoairc.xiaomi.DeviceCatalog
import com.tangren.xiaoairc.xiaomi.LoginGuard
import com.tangren.xiaoairc.xiaomi.MiAccount
import com.tangren.xiaoairc.xiaomi.MiDevice
import com.tangren.xiaoairc.xiaomi.MiNaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** 页面路由（单 Activity + 轻量返回栈） */
enum class Screen { Home, Devices, Account, Log, Settings, Help }

/** 音箱实时状态快照：status 0=停止 1=播放 2=暂停 */
data class SpkStatus(val status: Int, val volume: Int, val position: Double)

/** 扫码登录的界面阶段 */
enum class QrStage { Idle, Loading, Waiting, Scanned, Success, Failed, Expired }

/**
 * 应用状态与动作（等价于 ViewModel）。
 * 业务实现仍复用既有类：CastService / MiAccount / MiNaClient / LoginGuard / SelfCheck 等。
 */
class AppState(private val app: Context) {

    val prefs = Prefs(app)

    init {
        // 冷启动清零"连续登录失败"计数：它只服务于本次会话的自愈判断，
        // 跨进程持久化会让之前积累的失败在今天无谓地触发链路重启
        LoginGuard.onAppColdStart(prefs)
    }

    // ---------------- 路由 ----------------
    var stack by mutableStateOf(listOf(Screen.Home))
        private set
    val current: Screen get() = stack.last()

    fun go(s: Screen) {
        stack = stack + s
    }

    fun back() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    // ---------------- 服务状态（镜像 CastService 静态量） ----------------
    var running by mutableStateOf(CastService.running); private set
    var ssdpReady by mutableStateOf(CastService.ssdpReady); private set
    var httpBase by mutableStateOf(CastService.httpBase); private set
    var statusText by mutableStateOf(CastService.statusText); private set
    var selfHeal by mutableStateOf(CastService.lastSelfHeal); private set

    fun syncService() {
        running = CastService.running
        ssdpReady = CastService.ssdpReady
        httpBase = CastService.httpBase
        statusText = CastService.statusText
        selfHeal = CastService.lastSelfHeal
    }

    // ---------------- 音箱 ----------------
    var devices by mutableStateOf<List<MiDevice>>(emptyList()); private set
    var statusMap by mutableStateOf<Map<String, SpkStatus>>(emptyMap()); private set
    var deviceId by mutableStateOf(prefs.deviceId); private set

    /** 当前选中音箱的展示信息（可观察，供 Compose 重组） */
    var deviceName by mutableStateOf(prefs.deviceName); private set
    var hardware by mutableStateOf(prefs.hardware); private set
    var fakeName by mutableStateOf(
        prefs.dlnaName.ifEmpty { prefs.deviceName.ifEmpty { "小爱音箱" } }
    ); private set

    fun apiPath(): String = if (DeviceCatalog.shouldUseMusicApi(hardware, useMusicApi, compatibilityMode, musicApiExplicit)) {
        "player_play_music"
    } else {
        "player_play_url"
    }

    fun selectDevice(d: MiDevice) {
        prefs.deviceId = d.deviceId
        prefs.deviceName = d.name
        prefs.hardware = d.hardware
        deviceId = d.deviceId
        deviceName = d.name
        hardware = d.hardware
        if (prefs.dlnaName.isEmpty()) fakeName = d.name.ifEmpty { "小爱音箱" }
    }

    // ---------------- 账号 ----------------
    var authMode by mutableStateOf(prefs.authMode)
    var account by mutableStateOf(prefs.account)
    var password by mutableStateOf(prefs.password)
    var userId by mutableStateOf(prefs.userId)
    var passToken by mutableStateOf(prefs.passToken)
    var loginMsg by mutableStateOf("")
    var tokenCached by mutableStateOf(prefs.serviceToken.isNotEmpty()); private set

    // ---------------- 配置（镜像 Prefs，改动即写回） ----------------
    var useMusicApi by mutableStateOf(prefs.useMusicApi)

    /** 用户是否改过 music 接口开关（改过后型号白名单不再强行覆盖） */
    var musicApiExplicit by mutableStateOf(prefs.musicApiExplicit)
    var proxyEnabled by mutableStateOf(prefs.proxyEnabled)
    var bufferEnabled by mutableStateOf(prefs.bufferEnabled)
    var compatibilityMode by mutableStateOf(prefs.compatibilityMode)
    var autoSelfHeal by mutableStateOf(prefs.autoSelfHeal)
    var lightUp by mutableStateOf(prefs.lightUp)
    var port by mutableStateOf(prefs.serverPort.toString())
    var dlnaName by mutableStateOf(prefs.dlnaName)
    var volume by mutableStateOf(prefs.defaultVolume.toString())
    var urlType by mutableStateOf(prefs.playUrlType.toString())

    /** 端口是否被改过（运行中改端口需重启服务） */
    var portDirty by mutableStateOf(false); private set

    fun persistConfig() {
        prefs.authMode = authMode
        prefs.account = account.trim()
        prefs.password = password
        prefs.userId = userId.trim()
        prefs.passToken = passToken.trim()
        prefs.useMusicApi = useMusicApi
        prefs.musicApiExplicit = musicApiExplicit
        prefs.proxyEnabled = proxyEnabled
        prefs.bufferEnabled = bufferEnabled
        prefs.compatibilityMode = compatibilityMode
        prefs.autoSelfHeal = autoSelfHeal
        prefs.lightUp = lightUp
        prefs.dlnaName = dlnaName.trim()
        prefs.defaultVolume = volume.toIntOrNull()?.coerceIn(0, 100) ?: 0
        prefs.playUrlType = urlType.toIntOrNull()?.coerceIn(1, 2) ?: 2
        val p = port.toIntOrNull()
        if (p != null && p in 1024..65535) {
            portDirty = p != prefs.serverPort
            prefs.serverPort = p
        } else {
            port = prefs.serverPort.toString()
            portDirty = false
        }
        fakeName = prefs.dlnaName.ifEmpty { prefs.deviceName.ifEmpty { "小爱音箱" } }
    }

    fun resetUdn() {
        prefs.udn = ""
        LogBus.i("已重置 UDN，下次启动服务会重新生成设备身份")
    }

    // ---------------- 日志 / 自检 ----------------
    var logText by mutableStateOf(LogBus.snapshot()); private set
    var diag by mutableStateOf("")
    var busy by mutableStateOf(false)

    fun refreshLog() {
        logText = LogBus.snapshot()
    }

    fun clearLog() {
        LogBus.clear()
        logText = ""
    }

    /** 导出/复制前清洗凭据 */
    fun scrubbedLog(): String = Masking.scrubLog(
        LogBus.snapshot(),
        listOf(prefs.passToken, prefs.serviceToken, prefs.ssecurity, prefs.password)
            .filter { it.isNotBlank() }
    )

    fun exportLog(onFail: () -> Unit) {
        if (!ExportLog.share(app, scrubbedLog())) onFail()
    }

    // ---------------- 动作 ----------------

    suspend fun setServiceRunning(target: Boolean) {
        persistConfig()
        val intent = Intent(app, CastService::class.java).apply {
            action = if (target) CastService.ACTION_START else CastService.ACTION_STOP
        }
        if (target) {
            ContextCompat.startForegroundService(app, intent)
        } else {
            app.startService(intent)
        }
        // 稍等让 Service 完成启动/停止
        kotlinx.coroutines.delay(700)
        syncService()
    }

    suspend fun hotRestart() {
        persistConfig()
        app.startService(Intent(app, CastService::class.java).setAction(CastService.ACTION_RESTART))
        kotlinx.coroutines.delay(900)
        syncService()
    }

    /** 登录（按当前 authMode）；成功后顺手拉设备列表 */
    suspend fun login() {
        persistConfig()
        busy = true
        loginMsg = "登录中…"
        val ok = withContext(Dispatchers.IO) {
            runCatching { MiAccount(prefs).login() }.getOrDefault(false)
        }
        busy = false
        tokenCached = prefs.serviceToken.isNotEmpty()
        if (ok) {
            loginMsg = "登录成功（userId=${prefs.tokenUserId.ifEmpty { prefs.userId }}）"
            loadDevices()
        } else {
            loginMsg = "登录失败，看「日志」里的错误码"
        }
    }

    suspend fun loadDevices() {
        busy = true
        val list = withContext(Dispatchers.IO) {
            runCatching {
                val acc = MiAccount(prefs)
                if (!(acc.loadCachedToken() || acc.login())) return@runCatching emptyList<MiDevice>()
                MiNaClient(acc).deviceList()
            }.getOrDefault(emptyList())
        }
        devices = list
        if (list.isNotEmpty()) {
            val idx = list.indexOfFirst { it.deviceId == prefs.deviceId }
            val chosen = list[if (idx >= 0) idx else 0]
            // 只在没选过设备时才自动落选第一台，避免覆盖用户的选择
            if (prefs.deviceId.isEmpty()) selectDevice(chosen)
            deviceId = prefs.deviceId
            refreshStatuses()
        }
        busy = false
    }

    /** 并发拉取各音箱实时状态（用于卡片的信息行 / 进度条） */
    suspend fun refreshStatuses() {
        val list = devices
        if (list.isEmpty()) return
        val result = withContext(Dispatchers.IO) {
            coroutineScope {
                list.map { d ->
                    async {
                        val st = runCatching {
                            val acc = MiAccount(prefs)
                            if (!(acc.loadCachedToken() || acc.login())) null
                            else MiNaClient(acc).getPlayStatus(d.deviceId)
                        }.getOrNull()
                        d.deviceId to st?.let { SpkStatus(it.first, it.second, it.third) }
                    }
                }.awaitAll()
            }
        }
        statusMap = result.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
    }

    suspend fun testTts(did: String = prefs.deviceId): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val acc = MiAccount(prefs)
            if (!(acc.loadCachedToken() || acc.login())) return@runCatching false
            MiNaClient(acc).textToSpeech(did, "小爱DLNA 已连接，可以投屏了")
        }.getOrDefault(false)
    }

    suspend fun stopPlayback(did: String = prefs.deviceId): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val acc = MiAccount(prefs)
            if (!(acc.loadCachedToken() || acc.login())) return@runCatching false
            MiNaClient(acc).playOperation(did, "stop")
        }.getOrDefault(false)
    }

    /** 手动兜底添加一台音箱（自动发现失败时用） */
    fun addManualDevice(did: String, name: String) {
        val d = MiDevice(
            deviceId = did,
            miotDid = "",
            name = name.ifEmpty { "手动音箱" },
            hardware = "",
            model = ""
        )
        devices = devices.filter { it.deviceId != did } + d
        selectDevice(d)
    }

    suspend fun runSelfCheck() {
        diag = "正在自检…"
        val items = SelfCheck.run(app, prefs, CastService.running, CastService.ssdpReady)
        val sb = StringBuilder()
        sb.append(SelfCheck.summary(items)).append("\n\n")
        for (item in items) {
            sb.append("${item.level.mark} ${item.title}：${item.detail}\n")
        }
        diag = sb.toString().trimEnd()
    }

    // ---------------- 扫码登录 ----------------
    /** 扫码对话框是否打开（二维码与轮询状态由对话框自身持有） */
    var qrDialogOpen by mutableStateOf(false)

    /** 由界面负责协程；这里只处理结果落地 */
    fun onQrConfirmed(uid: String, ptk: String) {
        authMode = "passToken"
        userId = uid
        passToken = ptk
        persistConfig()
        tokenCached = prefs.serviceToken.isNotEmpty()
        loginMsg = "扫码登录成功"
    }
}
