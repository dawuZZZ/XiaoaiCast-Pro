package com.tangren.xiaoairc.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tangren.xiaoairc.R
import com.tangren.xiaoairc.ui.theme.Dimens
import com.tangren.xiaoairc.ui.theme.Xc
import kotlinx.coroutines.delay

/**
 * 版本 A 的动效常量与组件库（对应设计文档 3.7 动效规范）。
 *
 * 与旧 Components.kt 并存：旧组件仍在被 Account / Log / Help 使用，
 * 这里只新增版本 A 所需的卡片、开关、波形、按压缩放等。
 */
object Motion {
    /** easeOutCubic —— 页面/列表入场 */
    val EaseOutCubic = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

    /** 卡片按压 */
    val EaseOutQuad = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)
}

/** 按压时轻微缩放（设计稿：卡片 0.97 / 按钮 0.92） */
@Composable
fun Modifier.pressScale(
    interaction: MutableInteractionSource,
    down: Float = 0.97f,
    durationMs: Int = 150
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(
        targetValue = if (pressed) down else 1f,
        animationSpec = tween(durationMs, easing = Motion.EaseOutQuad),
        label = "pressScale"
    )
    return this.graphicsLayer {
        scaleX = s
        scaleY = s
    }
}

/** 入场动画：淡入 + 自下而上滑入，按 index 依次错开（stagger 30ms） */
@Composable
fun StaggerIn(index: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 30L)
        shown = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(300, easing = Motion.EaseOutCubic),
        label = "staggerAlpha"
    )
    val ty by animateFloatAsState(
        targetValue = if (shown) 0f else 24f,
        animationSpec = tween(300, easing = Motion.EaseOutCubic),
        label = "staggerY"
    )
    Box(
        Modifier.graphicsLayer {
            this.alpha = alpha
            translationY = ty
        }
    ) { content() }
}

/** 版本 A 的标准卡片：白底 + 20dp 圆角 + 柔和阴影 */
@Composable
fun ACard(
    modifier: Modifier = Modifier,
    padding: Dp = Dimens.CardPadding,
    radius: Dp = Dimens.CardRadius,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = shape,
                clip = false,
                ambientColor = Xc.CardShadow,
                spotColor = Xc.CardShadow
            )
            .clip(shape)
            .background(Xc.Surface)
            .padding(padding),
        content = content
    )
}

/** 分组标题（第 3.6.1 节）：12sp SemiBold 主色，顶距 24dp、左侧 20dp */
@Composable
fun AGroupTitle(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = Xc.GroupTitle,
        modifier = Modifier.padding(start = Dimens.PageH, top = 24.dp, bottom = 8.dp)
    )
}

/**
 * 自定义开关（第 3.6.2 / 3.7 节）：52×32dp，
 * 圆点 200ms 弹性滑动、轨道 150ms 颜色渐变。
 */
@Composable
fun AnimatedSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val w = Dimens.SwitchW
    val h = Dimens.SwitchH
    val track by animateColorAsState(
        targetValue = if (checked) Xc.SwitchTrackOn else Xc.SwitchTrackOff,
        animationSpec = tween(150),
        label = "switchTrack"
    )
    val padding = 3.dp
    val thumb = h - padding * 2
    val thumbX by animateDpAsState(
        targetValue = if (checked) (w - thumb - padding) else padding,
        animationSpec = spring(
            dampingRatio = 0.75f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "switchThumb"
    )
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(w, h)
            .clip(CircleShape)
            .background(track)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null
            ) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbX)
                .size(thumb)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

/**
 * 运行态声波可视化：3 条竖线上下波动（第 3.5.2 节）。
 * 用错开相位的无限动画实现，无需 Canvas。
 */
@Composable
fun WaveformBars(
    count: Int = 3,
    color: Color = Xc.OnPrimaryBlock,
    barWidth: Dp = 3.dp,
    maxHeight: Dp = 26.dp,
    minHeight: Dp = 8.dp
) {
    val transition = rememberInfiniteTransition(label = "wave")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val h by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(620, delayMillis = i * 140, easing = androidx.compose.animation.core.LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "waveBar$i"
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .width(barWidth)
                    .height((minHeight.value + (maxHeight.value - minHeight.value) * h).dp)
                    .clip(RoundedCornerShape(barWidth))
                    .background(color)
            )
        }
    }
}

/**
 * 圆形品牌头像：蓝色渐变底 + 白色音箱图形（首页 logo / 设备图标通用）。
 * 运行态可带一圈呼吸光环。
 */
@Composable
fun SpeakerAvatar(
    size: Dp,
    running: Boolean = false,
    glyphSize: Dp = size * 0.5f
) {
    val ringAlpha = if (running) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val v by transition.animateFloat(
            initialValue = 0.28f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1800, easing = Motion.EaseOutCubic),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulseAlpha"
        )
        v
    } else 0f

    Box(contentAlignment = Alignment.Center) {
        if (running) {
            Box(
                modifier = Modifier
                    .size(size * 1.18f)
                    .graphicsLayer { alpha = ringAlpha }
                    .clip(CircleShape)
                    .background(Xc.Accent)
            )
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(Xc.Accent, Xc.AccentDark))
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_brand),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(glyphSize)
            )
        }
    }
}

/** 图标容器：40dp 圆角方块，用于功能列表（第 3.5.5 节） */
@Composable
fun IconBadge(
    icon: ImageVector,
    tint: Color,
    size: Dp = Dimens.IconBox,
    radius: Dp = Dimens.IconBoxRadius
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** 右向 chevron（列表统一尾部指示） */
@Composable
fun Chevron(tint: Color = Xc.TextDisabled) {
    Icon(
        imageVector = Icons.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(22.dp)
    )
}

/**
 * 功能列表行：彩色图标容器 + 标题 + 描述 + 箭头，
 * 按压时背景变亮并右移 2dp（第 3.5.5 节）。
 */
@Composable
fun FeatureRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        targetValue = if (pressed) Xc.BgGradientTop else Xc.Surface,
        animationSpec = tween(150),
        label = "rowBg"
    )
    val dx by animateFloatAsState(
        targetValue = if (pressed) 2f else 0f,
        animationSpec = tween(150, easing = Motion.EaseOutQuad),
        label = "rowDx"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .graphicsLayer { translationX = dx }
            .padding(horizontal = Dimens.CardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Chevron()
    }
}

/** 行间 1dp 分隔线（卡片内使用，左右内缩到图标外沿） */
@Composable
fun ARowDivider(startIndent: Dp = Dimens.CardPadding) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(1.dp)
            .background(Xc.Divider)
    )
}

/** 卡片内的一组行（自动插入分隔线） */
@Composable
fun ARowGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Top, content = content)
}

/**
 * 版本 A 输入框（第 3.6.4 节）：12dp 圆角、浅灰底 #F1F5F9、聚焦时蓝色边框。
 */
@Composable
fun AXTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    password: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                numeric -> KeyboardType.Number
                password -> KeyboardType.Password
                else -> KeyboardType.Text
            }
        ),
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Xc.Accent,
            unfocusedBorderColor = Color.Transparent,
            focusedContainerColor = Xc.InputBg,
            unfocusedContainerColor = Xc.InputBg
        ),
        modifier = modifier.fillMaxWidth()
    )
}
