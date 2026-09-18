package com.tangren.xiaoairc.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tangren.xiaoairc.ui.AppState
import com.tangren.xiaoairc.ui.GuideSteps
import com.tangren.xiaoairc.ui.SectionTitle
import com.tangren.xiaoairc.ui.StepState
import com.tangren.xiaoairc.ui.XcCard
import com.tangren.xiaoairc.ui.XcTopBar
import com.tangren.xiaoairc.ui.theme.Xc

/** 帮助页：使用步骤（沿用既有引导逻辑）+ 投屏指引 */
@Composable
fun HelpScreen(s: AppState) {
    val ctx = LocalContext.current

    LaunchedEffect(Unit) { s.syncService() }

    val steps = GuideSteps.compute(
        ctx, s.prefs, s.running, s.ssdpReady, s.prefs.serviceToken.isNotEmpty()
    )
    val done = GuideSteps.countDone(steps)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        XcTopBar(title = "帮助", onBack = { s.back() })

        SectionTitle("使用步骤（已完成 $done / ${steps.size}）")
        Column(Modifier.padding(horizontal = 16.dp)) {
            XcCard {
                steps.forEach { step ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 7.dp)
                    ) {
                        val color = when (step.state) {
                            StepState.DONE -> Xc.Ok
                            StepState.DOING -> Xc.Warn
                            StepState.TODO -> Xc.TextDisabled
                        }
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(color),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = when (step.state) {
                                    StepState.DONE -> "✓"
                                    StepState.DOING -> "…"
                                    StepState.TODO -> step.index.toString()
                                },
                                color = androidx.compose.ui.graphics.Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(step.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                step.detail,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        SectionTitle("怎么投屏")
        Column(Modifier.padding(horizontal = 16.dp)) {
            XcCard {
                Text(
                    "以网易云音乐为例：\n\n" +
                        "1. 打开网易云音乐，正常播放一首歌\n" +
                        "2. 点播放页右上角的「投屏」图标\n" +
                        "3. 设备列表里会出现「${s.fakeName}」，选中它\n" +
                        "4. 声音就会从音箱出来，手机可以锁屏\n\n" +
                        "QQ 音乐同理，找「QPlay / 投屏」按钮。\n\n" +
                        "找不到设备时：去「日志」页点「一键自检」，重点看 Wi-Fi 和 SSDP 两项。",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        SectionTitle("说明")
        Column(Modifier.padding(horizontal = 16.dp)) {
            XcCard {
                Text(
                    "• 只支持 DLNA / QPlay 投送，不支持 AirPlay（iPhone 请用其他方案）。\n" +
                        "• L05B / L05C 不支持 flac，投送的源建议 mp3 / aac。\n" +
                        "• 手机需与音箱在同一路由器下，且不要用热点。\n" +
                        "• 手机得活着：已做前台服务 + WakeLock + 电池白名单，部分国产 ROM 仍可能杀后台。\n" +
                        "• 控制音箱需向小米云提交账号凭据，请勿使用绑定摄像头等敏感设备的账号。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
