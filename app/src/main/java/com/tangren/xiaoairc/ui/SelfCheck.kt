package com.tangren.xiaoairc.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.tangren.xiaoairc.LogBus
import com.tangren.xiaoairc.NetUtil
import com.tangren.xiaoairc.Prefs
import com.tangren.xiaoairc.xiaomi.MiAccount
import com.tangren.xiaoairc.xiaomi.MiNaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ServerSocket

/** 一条自检结果。level：OK 绿 / WARN 黄 / FAIL 红 */
data class CheckItem(val level: Level, val title: String, val detail: String) {
    enum class Level(val mark: String) { OK("✅"), WARN("⚠️"), FAIL("❌") }
}

/**
 * 一键自检。
 *
 * 实际使用中 80% 的"投不上去"只有四个原因：没连 Wi-Fi / 没选音箱 / 端口被占 / token 过期。
 * 与其让用户去翻 500 行日志，不如把结论直接摆出来。
 */
object SelfCheck {

    suspend fun run(
        ctx: Context,
        prefs: Prefs,
        running: Boolean,
        ssdpReady: Boolean,
        testDeviceOnline: Boolean = true
    ): List<CheckItem> {
        val items = ArrayList<CheckItem>(9)

        // 1 通知权限
        items += if (GuideSteps.hasNotificationPermission(ctx)) {
            CheckItem(CheckItem.Level.OK, "通知权限", "已允许，前台服务能常驻")
        } else {
            CheckItem(CheckItem.Level.FAIL, "通知权限", "没允许，前台服务容易被系统杀掉 → 点第 1 步去允许")
        }

        // 2 电池优化白名单
        items += if (GuideSteps.isIgnoringBattery(ctx)) {
            CheckItem(CheckItem.Level.OK, "后台运行", "已在电池优化白名单里")
        } else {
            CheckItem(CheckItem.Level.WARN, "后台运行", "未加白名单，锁屏久了可能收不到投屏指令 → 点第 2 步")
        }

        // 3 Wi-Fi 连接
        val onWifi = isOnWifi(ctx)
        items += if (onWifi) {
            CheckItem(CheckItem.Level.OK, "Wi-Fi", "已连接 Wi-Fi（投屏要求同一局域网，禁用热点）")
        } else {
            CheckItem(CheckItem.Level.FAIL, "Wi-Fi", "当前不在 Wi-Fi 上，或连的是数据网络 → 连家用 Wi-Fi")
        }

        // 4 局域网 IP
        val ip = NetUtil.wifiIpv4(ctx)
        items += if (!ip.isNullOrEmpty() && !ip.startsWith("127.")) {
            CheckItem(CheckItem.Level.OK, "局域网 IP", ip)
        } else {
            CheckItem(CheckItem.Level.FAIL, "局域网 IP", "拿不到局域网地址 → 检查路由器是否开了 AP 隔离")
        }

        // 5 端口
        val port = prefs.serverPort
        items += when {
            port !in 1024..65535 ->
                CheckItem(CheckItem.Level.FAIL, "端口", "$port 不合法（应 1024-65535）")
            running && port == prefs.serverPort ->
                CheckItem(CheckItem.Level.OK, "端口", "$port 正被本服务占用（正常）")
            isPortFree(port) ->
                CheckItem(CheckItem.Level.OK, "端口", "$port 空闲可用")
            else ->
                CheckItem(CheckItem.Level.FAIL, "端口", "$port 已被别的程序占用 → 改成 8301 再启动")
        }

        // 6 账号凭据
        var account: MiAccount? = null
        val loginOk = withContext(Dispatchers.IO) {
            try {
                val acc = MiAccount(prefs)
                val ok = acc.loadCachedToken() || acc.login()
                if (ok) account = acc
                ok
            } catch (t: Throwable) {
                LogBus.e("自检登录异常：${t.message}")
                false
            }
        }
        items += if (loginOk) {
            CheckItem(CheckItem.Level.OK, "小米账号", "凭据有效（userId=${prefs.tokenUserId.ifEmpty { prefs.userId }}）")
        } else {
            CheckItem(
                CheckItem.Level.FAIL, "小米账号",
                if (prefs.authMode == "password") "账号密码登录失败（可能需人机验证）→ 改用 passToken 方式"
                else "passToken 可能已过期 → 重新复制一次"
            )
        }

        // 7 已选音箱
        items += if (prefs.deviceId.isNotEmpty()) {
            CheckItem(CheckItem.Level.OK, "目标音箱", "${prefs.deviceName.ifEmpty { "已选" }} (${prefs.hardware})")
        } else {
            CheckItem(CheckItem.Level.FAIL, "目标音箱", "还没选 → 点「刷新音箱列表」")
        }

        // 8 音箱在线（真的去问一次云端）
        val acc = account
        if (acc != null && prefs.deviceId.isNotEmpty() && testDeviceOnline) {
            val status = withContext(Dispatchers.IO) {
                try {
                    MiNaClient(acc).getPlayStatus(prefs.deviceId)
                } catch (t: Throwable) {
                    null
                }
            }
            items += if (status != null) {
                CheckItem(
                    CheckItem.Level.OK, "音箱在线",
                    "状态=${statusText(status.first)} 音量=${status.second}"
                )
            } else {
                CheckItem(CheckItem.Level.FAIL, "音箱在线", "云端问不到这台音箱 → 确认它通电并连着同一个 Wi-Fi")
            }
        } else {
            items += CheckItem(CheckItem.Level.WARN, "音箱在线", "账号或设备没准备好，跳过这项检查")
        }

        // 9 SSDP
        items += if (running && ssdpReady) {
            CheckItem(CheckItem.Level.OK, "SSDP 服务", "已就绪，音乐 App 能发现这台设备")
        } else if (running) {
            CheckItem(CheckItem.Level.WARN, "SSDP 服务", "服务在跑但组播未就绪 → 关掉再开一次 Wi-Fi 重试")
        } else {
            CheckItem(CheckItem.Level.WARN, "SSDP 服务", "投屏服务尚未启动（这是启动前的正常状态）")
        }

        return items
    }

    fun summary(items: List<CheckItem>): String {
        val ok = items.count { it.level == CheckItem.Level.OK }
        return "$ok/${items.size} 项通过"
    }

    private fun statusText(s: Int): String = when (s) {
        1 -> "播放中"
        2 -> "已暂停"
        else -> "已停止"
    }

    private fun isPortFree(port: Int): Boolean = try {
        ServerSocket(port).use { true }
    } catch (_: Throwable) {
        false
    }

    private fun isOnWifi(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    } catch (_: Throwable) {
        false
    }
}
