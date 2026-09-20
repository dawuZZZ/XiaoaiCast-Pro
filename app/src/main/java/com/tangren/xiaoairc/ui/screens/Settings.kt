package com.tangren.xiaoairc.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tangren.xiaoairc.CastService
import com.tangren.xiaoairc.ui.ACard
import com.tangren.xiaoairc.ui.AGroupTitle
import com.tangren.xiaoairc.ui.ARowDivider
import com.tangren.xiaoairc.ui.AXTextField
import com.tangren.xiaoairc.ui.AnimatedSwitch
import com.tangren.xiaoairc.ui.AppState
import com.tangren.xiaoairc.ui.BlockButton
import com.tangren.xiaoairc.ui.Chevron
import com.tangren.xiaoairc.ui.StaggerIn
import com.tangren.xiaoairc.ui.XcTopBar
import com.tangren.xiaoairc.ui.theme.Dimens
import com.tangren.xiaoairc.ui.theme.Xc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 设置页（版本 A，对应设计文档 3.6）：分组标题 + 白色圆角分组卡片 + 自定义开关 */
@Composable
fun SettingsScreen(s: AppState, scope: CoroutineScope) {
    val ctx = LocalContext.current

    // 版本号动态读取：这里曾经写死成 0.2.0，发版后忘了同步
    val versionName = remember(ctx) {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        XcTopBar(title = "设置", onBack = { s.back() })

        AGroupTitle("播放")
        StaggerIn(0) {
            Column(Modifier.padding(horizontal = Dimens.PageH)) {
                ACard(padding = 0.dp) {
                    SettingSwitchRow(
                        icon = Icons.Filled.PlayArrow,
                        title = "使用 player_play_music 接口",
                        subtitle = "L05B / L05C / LX05 等机型必需；未手动改过时按型号自动分派",
                        checked = s.useMusicApi
                    ) { s.useMusicApi = it; s.musicApiExplicit = true; s.persistConfig() }
                    ARowDivider()
                    SettingSwitchRow(
                        icon = Icons.Filled.Build,
                        title = "兼容模式",
                        subtitle = "强制走 player_play_url，忽略型号白名单（排查用）",
                        checked = s.compatibilityMode
                    ) { s.compatibilityMode = it; s.persistConfig() }
                    ARowDivider()
                    SettingSwitchRow(
                        icon = Icons.Filled.Notifications,
                        title = "点亮「正在播放」指示灯",
                        subtitle = "把 audio_type 置为 MUSIC",
                        checked = s.lightUp
                    ) { s.lightUp = it; s.persistConfig() }
                }
            }
        }

        AGroupTitle("音频")
        StaggerIn(1) {
            Column(Modifier.padding(horizontal = Dimens.PageH)) {
                ACard(padding = 0.dp) {
                    SettingSwitchRow(
                        icon = Icons.Filled.Refresh,
                        title = "音频缓冲",
                        subtitle = "边下到本地边供给：更稳、seek 更准，起播略慢（默认关；服务运行中改动会自动热重启生效）",
                        checked = s.bufferEnabled
                    ) {
                        s.bufferEnabled = it
                        s.persistConfig()
                        // 代理对象在服务启动时构建，缓冲开关要重建链路才生效
                        if (CastService.running) {
                            scope.launch { s.hotRestart() }
                        }
                    }
                    ARowDivider()
                    SettingSwitchRow(
                        icon = Icons.Filled.Share,
                        title = "音频流经手机代理",
                        subtitle = "音箱改拉手机短链接，兼容性更好",
                        checked = s.proxyEnabled
                    ) { s.proxyEnabled = it; s.persistConfig() }
                }
            }
        }

        AGroupTitle("稳定性")
        StaggerIn(2) {
            Column(Modifier.padding(horizontal = Dimens.PageH)) {
                ACard(padding = 0.dp) {
                    SettingSwitchRow(
                        icon = Icons.Filled.CheckCircle,
                        title = "连续登录失败自动自愈",
                        subtitle = "连续失败达阈值时自动热重启投屏链路",
                        checked = s.autoSelfHeal
                    ) { s.autoSelfHeal = it; s.persistConfig() }
                    ARowDivider()
                    SettingTapRow(
                        icon = Icons.AutoMirrored.Filled.Send,
                        title = "热重启投屏服务",
                        subtitle = "改完配置不用退 App，直接重建链路"
                    ) {
                        scope.launch {
                            s.hotRestart()
                            Toast.makeText(ctx, "已热重启投屏服务", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        AGroupTitle("服务参数")
        StaggerIn(3) {
            Column(Modifier.padding(horizontal = Dimens.PageH)) {
                ACard {
                    AXTextField(s.port, { s.port = it }, "服务端口（默认 8300）", numeric = true)
                    Spacer(Modifier.height(12.dp))
                    AXTextField(s.dlnaName, { s.dlnaName = it }, "伪装设备名（音乐 App 里显示的名字）")
                    Spacer(Modifier.height(12.dp))
                    AXTextField(s.volume, { s.volume = it }, "启动时把音箱音量设为（0 = 不改动）", numeric = true)
                    Spacer(Modifier.height(12.dp))
                    AXTextField(s.urlType, { s.urlType = it }, "备用接口 player_play_url 的 type", numeric = true)
                    Spacer(Modifier.height(16.dp))
                    BlockButton("保存设置") {
                        s.persistConfig()
                        Toast.makeText(
                            ctx,
                            if (s.portDirty) "已保存；端口变了，请热重启服务" else "已保存",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        AGroupTitle("其他")
        StaggerIn(4) {
            Column(Modifier.padding(horizontal = Dimens.PageH)) {
                ACard(padding = 0.dp) {
                    SettingTapRow(
                        icon = Icons.Filled.Delete,
                        title = "重置设备身份（UDN）",
                        subtitle = "重置后音乐 App 会把它当成新设备重新发现"
                    ) {
                        s.resetUdn()
                        Toast.makeText(ctx, "已重置 UDN", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        AGroupTitle("关于")
        StaggerIn(5) {
            Column(Modifier.padding(horizontal = Dimens.PageH)) {
                ACard(padding = 0.dp) {
                    SettingTapRow(
                        icon = Icons.Filled.Info,
                        title = "版本",
                        subtitle = "小爱DLNA $versionName（手机端增强版）",
                        showChevron = false
                    ) {}
                    ARowDivider()
                    SettingTapRow(
                        icon = Icons.Filled.Star,
                        title = "开源许可",
                        subtitle = "MIT；协议层移植自 MiAir / miservice / XiaoMusic",
                        showChevron = false
                    ) {}
                    ARowDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.CardPadding, vertical = 14.dp)
                    ) {
                        Icon(
                            Icons.Filled.Settings, null,
                            tint = Xc.TextDisabled,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "仅供个人学习与自用；请勿使用绑定摄像头等敏感设备的账号，也不要把服务端口暴露到公网。",
                            fontSize = 12.sp,
                            color = Xc.TextDisabled
                        )
                    }
                }
            }
        }
    }
}

/** 设置项（带开关）：图标 24dp + 标题 + 描述 + AnimatedSwitch */
@Composable
private fun SettingSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.RowHeight)
            .padding(horizontal = Dimens.CardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Xc.Accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        AnimatedSwitch(checked = checked, onCheckedChange = onChange)
    }
}

/** 设置项（可点）：图标 + 标题 + 描述 + chevron */
@Composable
private fun SettingTapRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.RowHeight)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Dimens.CardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Xc.Accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showChevron) {
            Spacer(Modifier.width(8.dp))
            Chevron()
        }
    }
}
