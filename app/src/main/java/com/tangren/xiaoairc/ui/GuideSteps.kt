package com.tangren.xiaoairc.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.tangren.xiaoairc.NetUtil
import com.tangren.xiaoairc.Prefs

/**
 * 把整段小米 Cookie 解析成 userId + passToken。
 *
 * 用户从浏览器复制出来的往往是 `cUserId=xxx; userId=123; passToken=v2_yyy; ...` 一整串，
 * 直接粘进 passToken 输入框必然登录失败，而且报错距离现象很远 —— 所以这里智能拆一下。
 */
object CookieParser {

    data class Result(val userId: String, val passToken: String) {
        val ok: Boolean get() = userId.isNotEmpty() && passToken.isNotEmpty()
        val partial: Boolean get() = !ok && (userId.isNotEmpty() || passToken.isNotEmpty())
    }

    fun parse(raw: String): Result {
        val text = raw.trim().trim('"').trim()
        if (text.isEmpty()) return Result("", "")

        var uid = ""
        var token = ""

        // 1) 键值对形式：passToken=xxx / userId=123 / cUserId=123
        Regex("(?:^|[;,&\\s])passToken=([^;,&\\s\"']+)").find(text)?.let { token = it.groupValues[1] }
        Regex("(?:^|[;,&\\s])(?:c)?userId=([0-9]{4,})").find(text)?.let { uid = it.groupValues[1] }

        // 2) JSON 形式：{"passToken":"xxx","userId":"123"}
        if (token.isEmpty()) {
            Regex("\"passToken\"\\s*:\\s*\"([^\"]+)\"").find(text)?.let { token = it.groupValues[1] }
        }
        if (uid.isEmpty()) {
            Regex("\"(?:c)?userId\"\\s*:\\s*\"?([0-9]{4,})\"?").find(text)?.let { uid = it.groupValues[1] }
        }

        // 3) 只粘了一个纯 token（没有等号、没有分号）
        if (token.isEmpty() && !text.contains("=") && !text.contains(";") && text.length >= 16) {
            token = text
        }

        return Result(uid, token)
    }
}

/**
 * 首屏"使用步骤"清单。用户第一诉求是"进去之后知道该干什么"，
 * 所以每一步都带完成判定和可点击的行动。
 */
enum class StepState { TODO, DOING, DONE }

data class GuideStep(
    val index: Int,
    val title: String,
    val detail: String,
    val state: StepState,
    val actionLabel: String? = null
) {
    val done: Boolean get() = state == StepState.DONE
}

object GuideSteps {

    fun hasNotificationPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun isIgnoringBattery(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (_: Throwable) {
        false
    }

    fun compute(
        ctx: Context,
        prefs: Prefs,
        running: Boolean,
        ssdpReady: Boolean,
        hasToken: Boolean
    ): List<GuideStep> {
        val steps = ArrayList<GuideStep>(9)

        // 1 通知权限
        val notify = hasNotificationPermission(ctx)
        steps += GuideStep(
            1, "允许发送通知", "前台服务需要常驻通知，否则容易被系统杀掉",
            if (notify) StepState.DONE else StepState.TODO,
            if (notify) null else "去允许"
        )

        // 2 电池优化白名单
        val battery = isIgnoringBattery(ctx)
        steps += GuideStep(
            2, "允许后台运行", "把 App 加入电池优化白名单，锁屏后也能收投屏指令",
            if (battery) StepState.DONE else StepState.TODO,
            if (battery) null else "去设置"
        )

        // 3 填写账号凭据
        val credFilled = if (prefs.authMode == "password") {
            prefs.account.isNotBlank() && prefs.password.isNotBlank()
        } else {
            prefs.userId.isNotBlank() && prefs.passToken.isNotBlank()
        }
        steps += GuideStep(
            3, "填写小米账号",
            if (prefs.authMode == "password") "填手机号/邮箱 + 密码" else "填小米 ID + passToken",
            if (credFilled) StepState.DONE else StepState.TODO,
            if (credFilled) null else "去填写"
        )

        // 4 登录成功（serviceToken 已拿到）
        steps += GuideStep(
            4, "点「登录小米账号」", "拿到 serviceToken 才算登录成功",
            if (hasToken) StepState.DONE else if (credFilled) StepState.DOING else StepState.TODO,
            if (hasToken) null else "去登录"
        )

        // 5 选音箱
        val devicePicked = prefs.deviceId.isNotEmpty()
        steps += GuideStep(
            5, "刷新并选择音箱", "选中小爱音箱 Play（L05B / L05C）",
            if (devicePicked) StepState.DONE else StepState.TODO,
            if (devicePicked) null else "去刷新"
        )

        // 6 局域网
        val ip = NetUtil.wifiIpv4(ctx)
        val wifiOk = !ip.isNullOrEmpty() && !ip.startsWith("127.")
        steps += GuideStep(
            6, "手机连上 Wi-Fi", "必须和音箱在同一个路由器下，别用热点",
            if (wifiOk) StepState.DONE else StepState.TODO,
            if (wifiOk) null else "去检查"
        )

        // 7 端口
        val port = prefs.serverPort
        val portOk = port in 1024..65535
        steps += GuideStep(
            7, "确认服务端口", "默认 8300，被占用就换 8301",
            if (portOk) StepState.DONE else StepState.TODO,
            if (portOk) null else "去修改"
        )

        // 8 启动服务
        steps += GuideStep(
            8, "启动投屏服务",
            if (running && ssdpReady) "已在运行，音乐 App 现在能发现这台设备"
            else if (running) "服务已起，但 SSDP 通知未就绪"
            else "启动后音乐 App 的设备列表才会出现它",
            if (running && ssdpReady) StepState.DONE else if (running) StepState.DOING else StepState.TODO,
            if (running) null else "去启动"
        )

        // 9 投屏（永远待用户操作）
        steps += GuideStep(
            9, "去音乐 App 点投屏", "网易云播放页 → 右上角投屏 → 选「${prefs.deviceName.ifEmpty { "你的音箱" }}」",
            StepState.TODO, "看指引"
        )

        return steps
    }

    fun countDone(steps: List<GuideStep>): Int = steps.count { it.done }

    /** 前置步骤（1-8）是否全部完成 —— 用来决定要不要把清单自动收起来 */
    fun allRequiredDone(steps: List<GuideStep>): Boolean =
        steps.filter { it.index <= 8 }.all { it.done }
}
