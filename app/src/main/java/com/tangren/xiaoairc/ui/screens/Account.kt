package com.tangren.xiaoairc.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tangren.xiaoairc.ui.AppState
import com.tangren.xiaoairc.ui.BlockButton
import com.tangren.xiaoairc.ui.CookieParser
import com.tangren.xiaoairc.ui.QrStage
import com.tangren.xiaoairc.ui.SectionTitle
import com.tangren.xiaoairc.ui.XcCard
import com.tangren.xiaoairc.ui.XcTextField
import com.tangren.xiaoairc.ui.XcTopBar
import com.tangren.xiaoairc.ui.theme.Xc
import com.tangren.xiaoairc.xiaomi.QRLogin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 账号页：扫码 / 密码 / passToken 三种登录路径 */
@Composable
fun AccountScreen(s: AppState, scope: CoroutineScope) {
    val ctx = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        XcTopBar(title = "账号", onBack = { s.back() })

        SectionTitle("登录方式")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ModeChip("账号密码", s.authMode == "password", Modifier.weight(1f)) {
                s.authMode = "password"
                s.persistConfig()
            }
            ModeChip("passToken", s.authMode != "password", Modifier.weight(1f)) {
                s.authMode = "passToken"
                s.persistConfig()
            }
        }

        Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 16.dp)) {
            BlockButton("扫码登录（推荐 · 不触发验证码）", active = true) {
                s.qrDialogOpen = true
            }
        }

        SectionTitle(if (s.authMode == "password") "账号密码" else "小米 ID / passToken")
        Column(Modifier.padding(horizontal = 16.dp)) {
            XcCard {
                if (s.authMode == "password") {
                    XcTextField(s.account, { s.account = it }, "小米账号（手机号 / 邮箱 / 小米 ID）")
                    Spacer(Modifier.height(10.dp))
                    XcTextField(s.password, { s.password = it }, "密码", password = true)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "密码方式偶发人机验证；若报 70016 / 87001，请改用 passToken 或扫码。",
                        style = MaterialTheme.typography.labelSmall
                    )
                } else {
                    XcTextField(s.userId, { s.userId = it }, "小米 ID（userId，一串数字）", numeric = true)
                    Spacer(Modifier.height(10.dp))
                    XcTextField(s.passToken, { s.passToken = it }, "passToken")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "在浏览器登录 account.xiaomi.com 后，从 Cookie 里取 passToken；或直接粘整段 Cookie 点下面的按钮。",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    BlockButton("打开登录页") {
                        runCatching {
                            ctx.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://account.xiaomi.com/pass/serviceLogin?sid=micoapi")
                                )
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    BlockButton("粘贴并解析 Cookie") { pasteCookie(s, ctx) }
                }
            }

            Spacer(Modifier.height(12.dp))
            BlockButton(if (s.busy) "处理中…" else "登录小米账号", enabled = !s.busy) {
                scope.launch { s.login() }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                if (s.loginMsg.isNotEmpty()) s.loginMsg
                else if (s.tokenCached) "已缓存登录凭据，可直接启动服务"
                else "还没有有效凭据，请先登录",
                style = MaterialTheme.typography.bodySmall
            )
            if (s.userId.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("userId ${s.userId}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    if (s.qrDialogOpen) QrDialog(s, scope, ctx)
}

/** 两态选择块 */
@Composable
private fun ModeChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Xc.ChipBg else Xc.Surface)
            .border(1.dp, if (selected) Xc.Accent else Xc.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) Xc.Accent else Xc.TextPrimary
        )
    }
}

@Composable
private fun QrDialog(s: AppState, scope: CoroutineScope, ctx: Context) {
    val qr = remember { QRLogin() }
    var stage by remember { mutableStateOf(QrStage.Loading) }
    var bmp by remember { mutableStateOf<ImageBitmap?>(null) }
    var loginUrl by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        stage = QrStage.Loading
        bmp = null
        val info = withContext(Dispatchers.IO) { qr.fetchQrCode() }
        if (info == null) {
            stage = QrStage.Failed
            return@LaunchedEffect
        }
        loginUrl = info.loginUrl
        bmp = loadBitmap(info.qrImageUrl)
        stage = QrStage.Waiting

        while (true) {
            val r = withContext(Dispatchers.IO) { qr.poll() }
            when (r.state) {
                QRLogin.State.CONFIRMED -> {
                    stage = QrStage.Success
                    s.onQrConfirmed(r.userId, r.passToken)
                    s.qrDialogOpen = false
                    scope.launch { s.login() }
                    return@LaunchedEffect
                }

                QRLogin.State.EXPIRED -> {
                    stage = QrStage.Expired
                    return@LaunchedEffect
                }

                QRLogin.State.FAILED -> {
                    stage = QrStage.Failed
                    return@LaunchedEffect
                }

                else -> if (r.message.contains("确认")) stage = QrStage.Scanned
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { qr.close() }
    }

    AlertDialog(
        onDismissRequest = { s.qrDialogOpen = false },
        title = { Text("扫码登录小米账号") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "用「米家」App 扫码并确认；确认后自动填入凭据并登录。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                Box(Modifier.size(248.dp), contentAlignment = Alignment.Center) {
                    val b = bmp
                    if (b != null) {
                        // 必须显式给尺寸：Image 不给 modifier 时按位图 px 换算 dp，
                        // 在 440dpi 屏上 640px 的图只会显示成 ~233dp 以下，且被降采样导致糊。
                        // 这里铺满 248dp，并用 None 滤波（最近邻）保持二维码边缘锐利，方便扫。
                        Image(
                            bitmap = b,
                            contentDescription = "登录二维码",
                            contentScale = ContentScale.Fit,
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(qrStageText(stage), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(qrStageText(stage), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = { attempt++ }) { Text("刷新二维码") }
        },
        dismissButton = {
            TextButton(onClick = {
                if (loginUrl.isNotEmpty()) {
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl)))
                    }
                }
            }) { Text("浏览器打开") }
        }
    )
}

private fun qrStageText(stage: QrStage): String = when (stage) {
    QrStage.Idle -> ""
    QrStage.Loading -> "正在获取二维码…"
    QrStage.Waiting -> "等待扫码…"
    QrStage.Scanned -> "已扫码，请在手机上确认"
    QrStage.Success -> "登录成功"
    QrStage.Failed -> "获取二维码失败，点「刷新二维码」重试"
    QrStage.Expired -> "二维码已过期，请刷新"
}

private suspend fun loadBitmap(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            resp.body?.byteStream()?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        }
    }.getOrNull()
}

private fun pasteCookie(s: AppState, ctx: Context) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val raw = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
    if (raw.isBlank()) {
        Toast.makeText(ctx, "剪贴板是空的", Toast.LENGTH_SHORT).show()
        return
    }
    val parsed = CookieParser.parse(raw)
    if (parsed.userId.isNotEmpty()) s.userId = parsed.userId
    if (parsed.passToken.isNotEmpty()) s.passToken = parsed.passToken
    if (parsed.ok) {
        s.authMode = "passToken"
        s.persistConfig()
        Toast.makeText(
            ctx,
            "已解析 userId=${parsed.userId}，passToken 长度 ${parsed.passToken.length}",
            Toast.LENGTH_SHORT
        ).show()
    } else {
        Toast.makeText(ctx, "没找到 userId / passToken，请确认复制的是小米 Cookie", Toast.LENGTH_SHORT).show()
    }
}
