package com.tangren.xiaoairc.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 设计 tokens（与《UI生成提示词》第 1 节一致）。
 *
 * 核心特征：主操作用灰阶而非品牌色；唯一强调色 #1A73E8 只出现在「选中态」与「进度条」。
 */
object Xc {
    val Bg = Color(0xFFFFFFFF)
    val TextPrimary = Color(0xFF111111)
    val TextSecondary = Color(0xFF757575)
    val TextDisabled = Color(0xFF9E9E9E)

    val Surface = Color(0xFFFFFFFF)
    val Border = Color(0xFFE3E3E3)

    val PrimaryBlock = Color(0xFF5F5F5F)
    val OnPrimaryBlock = Color(0xFFFFFFFF)
    val OnPrimaryBlockSub = Color(0xFFD8D8D8)

    val Accent = Color(0xFF1A73E8)
    val Icon = Color(0xFF1F1F1F)
    val Track = Color(0xFFDADADA)
    val ChipBg = Color(0xFFF0F0F0)

    /** 品牌色：仅用于首页 logo 与「关于」页 */
    val Brand = Color(0xFF1D6FE0)

    // 语义色（自检 / 步骤状态）
    val Ok = Color(0xFF12B76A)
    val Warn = Color(0xFFF79009)
    val Fail = Color(0xFFF04438)
}

/** 字阶：20/18/15/14/12/11 */
private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Xc.TextPrimary
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Xc.TextPrimary
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Xc.TextPrimary
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp, fontWeight = FontWeight.Normal, color = Xc.TextPrimary
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp, fontWeight = FontWeight.Normal, color = Xc.TextSecondary
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp, fontWeight = FontWeight.Normal, color = Xc.TextSecondary
    ),
)

/** 无深色模式：colorScheme 固定 light（提示词第 8 节）。 */
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
