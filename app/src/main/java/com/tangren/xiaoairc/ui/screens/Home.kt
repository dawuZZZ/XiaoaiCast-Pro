package com.tangren.xiaoairc.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tangren.xiaoairc.R
import com.tangren.xiaoairc.ui.AppState
import com.tangren.xiaoairc.ui.BrandMark
import com.tangren.xiaoairc.ui.EntryCard
import com.tangren.xiaoairc.ui.PlainRow
import com.tangren.xiaoairc.ui.Screen
import com.tangren.xiaoairc.ui.StatusButton
import com.tangren.xiaoairc.ui.theme.Xc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 骨架一：主界面 = 标题区 + 状态块 + 入口卡 + 平铺入口列表 */
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
            .padding(bottom = 24.dp)
    ) {
        // 标题区：logo + App 名称（普通 Row，非 AppBar）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandMark(28.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            // 状态主按钮（灰 ↔ 蓝）
            StatusButton(
                main = if (s.running) "投屏服务运行中" else "已停止",
                sub = if (s.running) {
                    (s.httpBase.ifEmpty { "正在获取地址…" }) + " · 点此停止"
                } else {
                    "点此启动"
                },
                active = s.running
            ) { scope.launch { s.setServiceRunning(!s.running) } }

            Spacer(Modifier.height(12.dp))

            // 入口卡片：当前音箱（只展示音箱名，不把 deviceId 之类的内部标识铺在首页）
            EntryCard(
                icon = Icons.Filled.PlayArrow,
                title = s.deviceName.ifEmpty { "还没有选择音箱" },
                subtitle = if (s.deviceId.isEmpty()) {
                    "点此选择要投屏的小爱音箱"
                } else {
                    "已选择 · 点此更换音箱"
                }
            ) { s.go(Screen.Devices) }
        }

        Spacer(Modifier.height(24.dp))

        // 平铺入口列表（无卡片、无分割线）
        PlainRow(Icons.Filled.Person, "账号", "登录 / 扫码 / passToken") { s.go(Screen.Account) }
        PlainRow(Icons.AutoMirrored.Filled.List, "日志", "运行日志 · 一键自检 · 导出") { s.go(Screen.Log) }
        PlainRow(Icons.Filled.Settings, "设置", "播放 · 缓冲 · 端口 · 自愈") { s.go(Screen.Settings) }
        PlainRow(Icons.Filled.Info, "帮助", "使用步骤 · 投屏指引") { s.go(Screen.Help) }

        if (s.selfHeal.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "自愈：${s.selfHeal}",
                fontSize = 11.sp,
                color = Xc.Warn,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}
