package com.tangren.xiaoairc.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tangren.xiaoairc.CastService
import com.tangren.xiaoairc.ui.AppState
import com.tangren.xiaoairc.ui.BlockButton
import com.tangren.xiaoairc.ui.SectionTitle
import com.tangren.xiaoairc.ui.SettingsRow
import com.tangren.xiaoairc.ui.SwitchSettingRow
import com.tangren.xiaoairc.ui.XcCard
import com.tangren.xiaoairc.ui.XcTextField
import com.tangren.xiaoairc.ui.XcTopBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 骨架三：设置页 —— 分组入口 + 开关 + 参数 */
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

        SectionTitle("播放")
        Column(Modifier.padding(horizontal = 16.dp)) {
            XcCard {
                SwitchSettingRow(
                    icon = Icons.Filled.PlayArrow,
                    title = "使用 player_play_music 接口",
                    subtitle = "L05B / L05C / LX05 等机型必需；未手动改过时按型号自动分派",
                    checked = s.useMusicApi
                ) { s.useMusicApi = it; s.musicApiExplicit = true; s.persistConfig() }

                SwitchSettingRow(
                    icon = Icons.Filled.Build,
                    title = "兼容模式",
                    subtitle = "强制走 player_play_url，忽略型号白名单（排查用）",
                    checked = s.compatibilityMode
                ) { s.compatibilityMode = it; s.persistConfig() }

                SwitchSettingRow(
                    icon = Icons.Filled.Notifications,
                    title = "点亮「正在播放」指示灯",
                    subtitle = "把 audio_type 置为 MUSIC",
                    checked = s.lightUp
                ) { s.lightUp = it; s.persistConfig() }
            }
        }

        SectionTitle("音频")
        Column(Modifier.padding(horizontal = 16.dp)) {
            XcCard {
                SwitchSettingRow(
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

                SwitchSettingRow(
                    icon = Icons.Filled.Share,
                    title = "音频流经手机代理",
                    subtitle = "音箱改拉手机短链接，兼容性更好",
                    checked = s.proxyEnabled
                ) { s.proxyEnabled = it; s.persistConfig() }
            }
        }

        SectionTitle("稳定性")
        Column(Modifier.padding(horizontal = 16.dp)) {
            XcCard {
                SwitchSettingRow(
                    icon = Icons.Filled.CheckCircle,
                    title = "连续登录失败自动自愈",
                    subtitle = "连续失败达阈值时自动热重启投屏链路",
                    checked = s.autoSelfHeal
                ) { s.autoSelfHeal = it; s.persistConfig() }

                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.Send,
                    title = "热重启投屏服务",
                    subtitle = "改完配置不用退 App，直接重建链路",
                    onClick = {
                        scope.launch {
                            s.hotRestart()
                            Toast.makeText(ctx, "已热重启投屏服务", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }

        SectionTitle("服务参数")
        Column(Modifier.padding(horizontal = 16.dp)) {
            XcCard {
                XcTextField(s.port, { s.port = it }, "服务端口（默认 8300）", numeric = true)
                Spacer(Modifier.height(10.dp))
                XcTextField(s.dlnaName, { s.dlnaName = it }, "伪装设备名（音乐 App 里显示的名字）")
                Spacer(Modifier.height(10.dp))
                XcTextField(s.volume, { s.volume = it }, "启动时把音箱音量设为（0 = 不改动）", numeric = true)
                Spacer(Modifier.height(10.dp))
                XcTextField(s.urlType, { s.urlType = it }, "备用接口 player_play_url 的 type", numeric = true)
                Spacer(Modifier.height(12.dp))
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

        SectionTitle("其他")
        Column(Modifier.padding(horizontal = 16.dp)) {
            XcCard {
                SettingsRow(
                    icon = Icons.Filled.Delete,
                    title = "重置设备身份（UDN）",
                    subtitle = "重置后音乐 App 会把它当成新设备重新发现",
                    onClick = {
                        s.resetUdn()
                        Toast.makeText(ctx, "已重置 UDN", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        SectionTitle("关于")
        Column(Modifier.padding(horizontal = 16.dp)) {
            XcCard {
                SettingsRow(
                    icon = Icons.Filled.Info,
                    title = "版本",
                    subtitle = "小爱DLNA $versionName（手机端增强版）"
                )
                SettingsRow(
                    icon = Icons.Filled.Star,
                    title = "开源许可",
                    subtitle = "MIT；协议层移植自 MiAir / miservice / XiaoMusic"
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "仅供个人学习与自用；请勿使用绑定摄像头等敏感设备的账号，也不要把服务端口暴露到公网。",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}
