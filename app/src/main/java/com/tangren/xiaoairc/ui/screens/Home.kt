package com.tangren.xiaoairc.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tangren.xiaoairc.R
import com.tangren.xiaoairc.ui.ACard
import com.tangren.xiaoairc.ui.ARowDivider
import com.tangren.xiaoairc.ui.AppState
import com.tangren.xiaoairc.ui.Chevron
import com.tangren.xiaoairc.ui.FeatureRow
import com.tangren.xiaoairc.ui.Motion
import com.tangren.xiaoairc.ui.Screen
import com.tangren.xiaoairc.ui.SpeakerAvatar
import com.tangren.xiaoairc.ui.StaggerIn
import com.tangren.xiaoairc.ui.WaveformBars
import com.tangren.xiaoairc.ui.pressScale
import com.tangren.xiaoairc.ui.theme.Dimens
import com.tangren.xiaoairc.ui.theme.Xc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 主界面（版本 A，对应设计文档 3.5）。
 *
 * 结构：品牌头 → 状态卡片 → 设备卡片 → 分组功能列表 → 底部 tagline。
 *
 * 注：原设计稿里的「快捷操作栏」（刷新/日志/帮助）与右下角「停止 FAB」已移除——
 * 这三项功能在别处均有入口（刷新在设备页、日志/帮助在下方列表、停止在状态卡左侧按钮），
 * 重复摆放反而让首页变啰嗦。
 */
@Composable
fun HomeScreen(s: AppState, scope: CoroutineScope) {

    // 服务状态是静态量，靠轮询同步（1s 一次，开销可忽略）
    LaunchedEffect(Unit) {
        while (true) {
            s.syncService()
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp)
    ) {
        StaggerIn(0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = Dimens.PageH, end = Dimens.PageH,
                        top = 18.dp, bottom = 20.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SpeakerAvatar(48.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        "把手机音频投放到小爱音箱",
                        fontSize = 12.sp,
                        color = Xc.TextSecondary
                    )
                }
            }
        }

        Column(Modifier.padding(horizontal = Dimens.PageH)) {
            StaggerIn(1) {
                ServiceStatusCard(
                    running = s.running,
                    httpBase = s.httpBase,
                    onClick = { scope.launch { s.setServiceRunning(!s.running) } }
                )
            }

            Spacer(Modifier.height(Dimens.CardGap))

            StaggerIn(2) {
                DeviceSelectorCard(
                    hasDevice = s.deviceId.isNotEmpty(),
                    name = s.deviceName,
                    connected = s.running && s.deviceId.isNotEmpty(),
                    onClick = { s.go(Screen.Devices) }
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        StaggerIn(3) {
            Column(Modifier.padding(horizontal = Dimens.PageH)) {
                ACard(padding = 0.dp) {
                    FeatureRow(
                        Icons.Filled.Person, Xc.IconAccount,
                        "账号", "登录 / 扫码 / passToken"
                    ) { s.go(Screen.Account) }
                    ARowDivider()
                    FeatureRow(
                        Icons.AutoMirrored.Filled.List, Xc.IconLog,
                        "日志", "运行日志 · 一键自检 · 导出"
                    ) { s.go(Screen.Log) }
                    ARowDivider()
                    FeatureRow(
                        Icons.Filled.Settings, Xc.IconSettings,
                        "设置", "播放 · 缓冲 · 端口 · 自愈"
                    ) { s.go(Screen.Settings) }
                    ARowDivider()
                    FeatureRow(
                        Icons.Filled.Info, Xc.IconHelp,
                        "帮助", "使用步骤 · 投屏指引"
                    ) { s.go(Screen.Help) }
                }
            }
        }

        if (s.selfHeal.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "自愈：${s.selfHeal}",
                fontSize = 12.sp,
                color = Xc.Warn,
                modifier = Modifier.padding(horizontal = Dimens.PageH)
            )
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "小爱 DLNA · 让音乐无处不在",
            fontSize = 12.sp,
            color = Xc.TextDisabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.PageH),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 状态卡片（核心，第 3.5.2 节）：停止 = 浅灰渐变；运行 = 蓝色渐变 + 白色文字 + 声波。
 * 状态切换时背景色 400ms 渐变、图标交叉淡入。整卡可点：运行中点击即停止。
 */
@Composable
private fun ServiceStatusCard(
    running: Boolean,
    httpBase: String,
    onClick: () -> Unit
) {
    val top by animateColorAsState(
        targetValue = if (running) Xc.Accent else Xc.StoppedCardTop,
        animationSpec = tween(400),
        label = "cardTop"
    )
    val bottom by animateColorAsState(
        targetValue = if (running) Xc.AccentDark else Xc.StoppedCardBottom,
        animationSpec = tween(400),
        label = "cardBottom"
    )
    val mainColor by animateColorAsState(
        targetValue = if (running) Color.White else Xc.TextPrimary,
        animationSpec = tween(400),
        label = "cardMain"
    )
    val subColor by animateColorAsState(
        targetValue = if (running) Xc.OnPrimaryBlockSub else Xc.TextSecondary,
        animationSpec = tween(400),
        label = "cardSub"
    )

    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .pressScale(interaction)
            .shadow(
                elevation = if (running) 12.dp else 8.dp,
                shape = RoundedCornerShape(Dimens.CardRadius),
                clip = false,
                ambientColor = Xc.CardShadow,
                spotColor = Xc.CardShadow
            )
            .clip(RoundedCornerShape(Dimens.CardRadius))
            .background(Brush.linearGradient(listOf(top, bottom)))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧圆形按钮：停止 = 空心播放；运行 = 实心停止 + 呼吸光环
        Box(contentAlignment = Alignment.Center) {
            if (running) {
                val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "orbPulse")
                val ring by transition.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 0f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = tween(1600, easing = Motion.EaseOutCubic),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Restart
                    ),
                    label = "orbRing"
                )
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .graphicsLayer { alpha = ring }
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(
                        if (running) Color.White.copy(alpha = 0.22f)
                        else Xc.StoppedCardTop
                    ),
                contentAlignment = Alignment.Center
            ) {
                Crossfade(targetState = running, label = "orbIcon") { isRunning ->
                    Icon(
                        imageVector = if (isRunning) Icons.Filled.Close else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = if (isRunning) Color.White else Xc.TextDisabled,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }

        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = if (running) "运行中" else "已停止",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = mainColor
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (running) {
                    (httpBase.ifEmpty { "正在获取地址…" }) + " · 点此停止"
                } else {
                    "点击启动服务"
                },
                fontSize = 13.sp,
                color = subColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (running) {
            Spacer(Modifier.width(12.dp))
            WaveformBars(color = Color.White.copy(alpha = 0.9f))
        }
    }
}

/**
 * 设备卡片（第 3.5.3 节）：白底 + 左侧 4dp 状态竖条（已连接 = 绿）+ 圆形音箱图标 + 箭头。
 */
@Composable
private fun DeviceSelectorCard(
    hasDevice: Boolean,
    name: String,
    connected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(Dimens.CardRadius)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .pressScale(interaction, down = 0.98f)
            .shadow(8.dp, shape, clip = false, ambientColor = Xc.CardShadow, spotColor = Xc.CardShadow)
            .clip(shape)
            .background(Xc.Surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧状态竖条
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(84.dp)
                .background(if (connected) Xc.Ok else Xc.Divider)
        )
        Spacer(Modifier.width(14.dp))
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Xc.Accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            SpeakerAvatar(44.dp, glyphSize = 22.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (hasDevice) name.ifEmpty { "小爱音箱" } else "还没有选择音箱",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (hasDevice) "已选择 · 点此更换音箱" else "点此选择要投屏的小爱音箱",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Chevron()
        Spacer(Modifier.width(Dimens.CardPadding))
    }
}
