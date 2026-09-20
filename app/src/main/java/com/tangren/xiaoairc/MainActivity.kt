package com.tangren.xiaoairc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.tangren.xiaoairc.ui.AppState
import com.tangren.xiaoairc.ui.Motion
import com.tangren.xiaoairc.ui.Screen
import com.tangren.xiaoairc.ui.screens.AccountScreen
import com.tangren.xiaoairc.ui.screens.DevicesScreen
import com.tangren.xiaoairc.ui.screens.HelpScreen
import com.tangren.xiaoairc.ui.screens.HomeScreen
import com.tangren.xiaoairc.ui.screens.LogScreen
import com.tangren.xiaoairc.ui.screens.SettingsScreen
import com.tangren.xiaoairc.ui.theme.Xc
import com.tangren.xiaoairc.ui.theme.XiaoaiDLNATheme

/**
 * 单 Activity + Compose 多页面路由。
 * 业务逻辑（CastService / MiAccount / MiNaClient / LoginGuard 等）不变，UI 层改为 Compose。
 *
 * 版本 A 起：统一浅色渐变背景 + 页面转场动画（右进左出）。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            XiaoaiDLNATheme {
                val state = remember { AppState(applicationContext) }
                val scope = rememberCoroutineScope()

                // 系统返回键：有返回栈就退一页
                BackHandler(enabled = state.stack.size > 1) { state.back() }

                Surface(
                    color = Xc.Bg,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Xc.BgGradientTop, Xc.BgGradientBottom)
                                )
                            )
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        AnimatedContent(
                            targetState = state.current,
                            transitionSpec = {
                                // 回首页 = 后退（从左滑入）；进其它页 = 前进（从右滑入）
                                if (targetState != Screen.Home) {
                                    (slideInHorizontally(
                                        animationSpec = tween(300, easing = Motion.EaseOutCubic)
                                    ) { it } + fadeIn(tween(200))) togetherWith
                                        (slideOutHorizontally(
                                            animationSpec = tween(300, easing = Motion.EaseOutCubic)
                                        ) { -it / 4 } + fadeOut(tween(200)))
                                } else {
                                    (slideInHorizontally(
                                        animationSpec = tween(300, easing = Motion.EaseOutCubic)
                                    ) { -it / 4 } + fadeIn(tween(200))) togetherWith
                                        (slideOutHorizontally(
                                            animationSpec = tween(300, easing = Motion.EaseOutCubic)
                                        ) { it } + fadeOut(tween(200)))
                                }
                            },
                            label = "screen"
                        ) { screen ->
                            when (screen) {
                                Screen.Home -> HomeScreen(state, scope)
                                Screen.Devices -> DevicesScreen(state, scope)
                                Screen.Account -> AccountScreen(state, scope)
                                Screen.Log -> LogScreen(state, scope)
                                Screen.Settings -> SettingsScreen(state, scope)
                                Screen.Help -> HelpScreen(state)
                            }
                        }
                    }
                }
            }
        }

        askNotificationPermission()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }
}
