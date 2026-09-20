package com.tangren.xiaoairc.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计 tokens —— 对应《小爱DLNA UI 改造设计文档》版本 A（第 3.2~3.4 节）。
 *
 * 字段名保持不变（Account / Log / Help 等页面仍在用），只升级取值；
 * 版本 A 新增的语义色、阴影、开关轨道等追加在下方。
 */
object Xc {
    // ---- 背景 / 卡片 ----
    /** 页面底色：浅灰白（设计稿为 #F8FAFC → #FFFFFF 渐变，渐变由页面自己画） */
    val Bg = Color(0xFFF8FAFC)
    val BgGradientTop = Color(0xFFF8FAFC)
    val BgGradientBottom = Color(0xFFFFFFFF)
    val Surface = Color(0xFFFFFFFF)
    val Border = Color(0xFFE2E8F0)
    val Divider = Color(0xFFE2E8F0)

    // ---- 文本 ----
    val TextPrimary = Color(0xFF0F172A)   // 深 slate
    val TextSecondary = Color(0xFF64748B)
    val TextDisabled = Color(0xFF94A3B8)

    // ---- 主色 / 语义色 ----
    val Accent = Color(0xFF2563EB)        // 科技蓝：启动、运行、主要按钮
    val AccentDark = Color(0xFF1D4ED8)
    val Ok = Color(0xFF10B981)            // 成功/运行
    val Warn = Color(0xFFF59E0B)          // 警告/兼容模式
    val Fail = Color(0xFFEF4444)          // 错误/停止

    // ---- 版本 A 专用 ----
    /** 卡片阴影 rgba(0,0,0,0.04) */
    val CardShadow = Color(0x0A000000)
    /** 开关轨道 */
    val SwitchTrackOn = Color(0xFF2563EB)
    val SwitchTrackOff = Color(0xFFCBD5E1)
    /** 分组标题色 */
    val GroupTitle = Color(0xFF2563EB)
    /** 状态卡片：停止态浅灰渐变 */
    val StoppedCardTop = Color(0xFFF1F5F9)
    val StoppedCardBottom = Color(0xFFFFFFFF)
    /** 输入框底色 */
    val InputBg = Color(0xFFF1F5F9)

    /** 功能列表图标容器配色 */
    val IconAccount = Color(0xFF3B82F6)
    val IconLog = Color(0xFF10B981)
    val IconSettings = Color(0xFFF59E0B)
    val IconHelp = Color(0xFF8B5CF6)

    // ---- 兼容旧字段（勿删：其它页面仍引用）----
    val PrimaryBlock = Color(0xFF5F5F5F)
    val OnPrimaryBlock = Color(0xFFFFFFFF)
    val OnPrimaryBlockSub = Color(0xFFD8D8D8)
    val Icon = Color(0xFF1F1F1F)
    val Track = Color(0xFFE2E8F0)
    val ChipBg = Color(0xFFF1F5F9)
    /** 品牌色：首页 logo 与「关于」 */
    val Brand = Color(0xFF2563EB)
}

/**
 * 版本 A 间距 / 圆角规格（第 3.4 节）。
 * 放在独立对象里，避免和颜色混在一起。
 */
object Dimens {
    val PageH = 20.dp          // 页面水平边距
    val CardPadding = 18.dp    // 卡片内边距
    val CardRadius = 20.dp     // 卡片圆角
    val CardGap = 16.dp        // 卡片间距
    val RowHeight = 64.dp      // 列表项高度
    val RowRadius = 16.dp      // 列表项圆角
    val IconBox = 40.dp        // 图标容器
    val IconBoxRadius = 12.dp
    val SwitchW = 52.dp
    val SwitchH = 32.dp
}

/** 字阶（第 3.3 节）：22 / 18 / 16 / 13 / 12 */
private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Xc.TextPrimary
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Xc.TextPrimary
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Xc.TextPrimary
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Xc.TextPrimary
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp, fontWeight = FontWeight.Normal, color = Xc.TextPrimary
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp, fontWeight = FontWeight.Normal, color = Xc.TextSecondary
    ),
    labelSmall = TextStyle(
        fontSize = 12.sp, fontWeight = FontWeight.Normal, color = Xc.TextSecondary
    ),
)

/** 无深色模式：colorScheme 固定 light（版本 A 为浅色系）。 */
private val AppColors = lightColorScheme(
    primary = Xc.Accent,
    onPrimary = Color.White,
    secondary = Xc.PrimaryBlock,
    onSecondary = Color.White,
    background = Xc.Bg,
    onBackground = Xc.TextPrimary,
    surface = Xc.Surface,
    onSurface = Xc.TextPrimary,
    surfaceVariant = Xc.ChipBg,
    onSurfaceVariant = Xc.TextSecondary,
    outline = Xc.Border,
    outlineVariant = Xc.Border,
    error = Xc.Fail,
)

@Composable
fun XiaoaiDLNATheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, typography = AppTypography, content = content)
}
