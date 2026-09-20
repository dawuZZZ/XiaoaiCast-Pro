package com.tangren.xiaoairc.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tangren.xiaoairc.ui.AppState
import com.tangren.xiaoairc.ui.BlockButton
import com.tangren.xiaoairc.ui.Chevron
import com.tangren.xiaoairc.ui.SpkStatus
import com.tangren.xiaoairc.ui.SpeakerAvatar
import com.tangren.xiaoairc.ui.XcTextField
import com.tangren.xiaoairc.ui.theme.Dimens
import com.tangren.xiaoairc.ui.theme.Xc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 二级列表页（版本 A）：每台音箱一张卡片，选中态蓝色对勾 + 左侧蓝条 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DevicesScreen(s: AppState, scope: CoroutineScope) {
    val ctx = LocalContext.current
    var addOpen by remember { mutableStateOf(false) }
    var newId by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        // 居中标题 + 副标题的自定义头部
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Xc.TextPrimary,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(24.dp)
                    .clickable { s.back() }
            )
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("选择设备", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Xc.TextPrimary)
                Spacer(Modifier.height(2.dp))
                Text(
                    "选择要投放音频的小爱音箱",
                    fontSize = 12.sp,
                    color = Xc.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "手动添加",
                tint = Xc.TextPrimary,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(24.dp)
                    .clickable { addOpen = true }
            )
        }

        if (s.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }

        if (s.devices.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("还没加载到音箱", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "点下方「刷新设备」；若一直为空，先去「账号」页登录小米账号",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = Dimens.PageH, end = Dimens.PageH, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(s.devices, key = { it.deviceId }) { d ->
                    val st = s.statusMap[d.deviceId]
                    val selected = d.deviceId == s.deviceId
                    var menuOpen by remember { mutableStateOf(false) }

                    val shape = RoundedCornerShape(18.dp)
                    val interaction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(8.dp, shape, clip = false, ambientColor = Xc.CardShadow, spotColor = Xc.CardShadow)
                            .clip(shape)
                            .background(Xc.Surface)
                            .combinedClickable(
                                interactionSource = interaction,
                                indication = null,
                                onClick = {
                                    s.selectDevice(d)
                                    toast(ctx, "已选择 ${d.name.ifEmpty { "小爱音箱" }}")
                                },
                                onLongClick = { menuOpen = true }
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 左侧选中蓝条
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(88.dp)
                                .background(if (selected) Xc.Accent else Color.Transparent)
                        )
                        Spacer(Modifier.width(14.dp))
                        SpeakerAvatar(56.dp, glyphSize = 28.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                d.name.ifEmpty { "小爱音箱" },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = Xc.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (st != null) {
                                    Text(
                                        "在线 · ${statusText(st)}",
                                        fontSize = 13.sp,
                                        color = Xc.Ok,
                                        maxLines = 1
                                    )
                                } else {
                                    Text("离线", fontSize = 13.sp, color = Xc.TextSecondary)
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Xc.ChipBg)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("离线", fontSize = 11.sp, color = Xc.TextSecondary)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            if (selected) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(Xc.Accent),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Filled.Check, null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            } else {
                                Chevron()
                            }
                        }
                        Spacer(Modifier.width(Dimens.CardPadding))

                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            listOf(
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
                            ).forEach { (label, action) ->
                                DropdownMenuItem(
                                    text = { Text(label, fontSize = 14.sp) },
                                    onClick = {
                                        menuOpen = false
                                        action()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 底部「刷新设备」描边胶囊按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            val interaction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .border(1.2.dp, Xc.Accent, CircleShape)
                    .clickable(interactionSource = interaction, indication = null) {
                        scope.launch { s.loadDevices() }
                    }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Refresh, null, tint = Xc.Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("刷新设备", fontSize = 14.sp, color = Xc.Accent, fontWeight = FontWeight.Medium)
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
