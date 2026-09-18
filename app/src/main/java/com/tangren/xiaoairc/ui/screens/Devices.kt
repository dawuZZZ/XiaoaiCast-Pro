package com.tangren.xiaoairc.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tangren.xiaoairc.ui.AppState
import com.tangren.xiaoairc.ui.BlockButton
import com.tangren.xiaoairc.ui.ItemCard
import com.tangren.xiaoairc.ui.SpkStatus
import com.tangren.xiaoairc.ui.TopBarAction
import com.tangren.xiaoairc.ui.XcTextField
import com.tangren.xiaoairc.ui.XcTopBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 骨架二：二级列表页 —— 每台音箱一张复合卡片（单选） */
@Composable
fun DevicesScreen(s: AppState, scope: CoroutineScope) {
    val ctx = LocalContext.current
    var addOpen by remember { mutableStateOf(false) }
    var newId by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        XcTopBar(
            title = "音箱",
            onBack = { s.back() },
            actions = {
                TopBarAction(Icons.Filled.Refresh, "刷新") { scope.launch { s.loadDevices() } }
                Spacer(Modifier.width(24.dp))
                TopBarAction(Icons.Filled.Add, "手动添加") { addOpen = true }
            }
        )

        if (s.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }

        if (s.devices.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("还没加载到音箱", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "点右上角刷新；若一直为空，先去「账号」页登录小米账号",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))
                BlockButton("刷新音箱列表") { scope.launch { s.loadDevices() } }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(s.devices, key = { it.deviceId }) { d ->
                    val st = s.statusMap[d.deviceId]
                    val selected = d.deviceId == s.deviceId
                    ItemCard(
                        selected = selected,
                        title = d.name.ifEmpty { "小爱音箱" },
                        tag = d.hardware.ifEmpty { d.model }.ifEmpty { null },
                        leftInfo = "状态 " + statusText(st),
                        rightInfo = st?.let { "音量 ${it.volume}%" } ?: "未获取",
                        progress = (st?.volume ?: 0) / 100f,
                        footer = if (selected) "当前使用中" else "点此选择这台音箱",
                        onSelect = {
                            s.selectDevice(d)
                            toast(ctx, "已选择 ${d.name.ifEmpty { "小爱音箱" }}")
                        },
                        menuItems = listOf(
                            "测试播报" to {
                                scope.launch {
                                    toast(ctx, if (s.testTts(d.deviceId)) "已发出播报指令" else "播报失败")
                                }
                            },
                            "停止播放" to {
                                scope.launch {
                                    toast(ctx, if (s.stopPlayback(d.deviceId)) "已让音箱停止" else "停止失败")
                                }
                            },
                            "复制 deviceId" to {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("xiaoairc", d.deviceId))
                                toast(ctx, "已复制 deviceId")
                            }
                        )
                    )
                }
            }
        }
    }

    if (addOpen) {
        AlertDialog(
            onDismissRequest = { addOpen = false },
            title = { Text("手动添加音箱") },
            text = {
                Column {
                    Text(
                        "自动发现失败时兜底：把 deviceId 粘进来（可从米家 App 的日志或云端接口拿到）",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    XcTextField(newId, { newId = it }, "deviceId")
                    Spacer(Modifier.height(8.dp))
                    XcTextField(newName, { newName = it }, "名称（可选）")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newId.isNotBlank()) {
                        s.addManualDevice(newId.trim(), newName.trim())
                        addOpen = false
                        newId = ""
                        newName = ""
                        scope.launch { s.refreshStatuses() }
                    }
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { addOpen = false }) { Text("取消") }
            }
        )
    }
}

private fun statusText(st: SpkStatus?): String = when (st?.status) {
    1 -> "播放中"
    2 -> "已暂停"
    0 -> "已停止"
    else -> "未知"
}

private fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}
