package com.tangren.xiaoairc.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tangren.xiaoairc.ui.AppState
import com.tangren.xiaoairc.ui.BlockButton
import com.tangren.xiaoairc.ui.TopBarAction
import com.tangren.xiaoairc.ui.XcCard
import com.tangren.xiaoairc.ui.XcTopBar
import com.tangren.xiaoairc.ui.theme.Xc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 日志页：自检 + 实时日志 + 复制/导出/清空 */
@Composable
fun LogScreen(s: AppState, scope: CoroutineScope) {
    val ctx = LocalContext.current

    // 实时刷新日志（LogBus 是内存环形缓冲，直接轮询最省事）
    LaunchedEffect(Unit) {
        while (true) {
            s.refreshLog()
            delay(800)
        }
    }

    Column(Modifier.fillMaxSize()) {
        XcTopBar(
            title = "日志",
            onBack = { s.back() },
            actions = {
                TopBarAction(Icons.Filled.Share, "导出分享") {
                    s.exportLog { Toast.makeText(ctx, "导出失败", Toast.LENGTH_SHORT).show() }
                }
                Spacer(Modifier.width(24.dp))
                TopBarAction(Icons.Filled.Delete, "清空") { s.clearLog() }
            }
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    BlockButton("复制日志") {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("xiaoairc", s.scrubbedLog()))
                        Toast.makeText(ctx, "已复制（凭据已脱敏）", Toast.LENGTH_SHORT).show()
                    }
                }
                Box(Modifier.weight(1f)) {
                    BlockButton("一键自检") { scope.launch { s.runSelfCheck() } }
                }
            }

            if (s.diag.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                XcCard {
                    Text(
                        text = s.diag,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Xc.TextPrimary
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            XcCard(Modifier.fillMaxSize()) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = s.logText.ifEmpty { "（暂无日志）" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Xc.TextPrimary
                    )
                }
            }
        }
    }
}
