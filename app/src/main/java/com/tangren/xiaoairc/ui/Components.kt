package com.tangren.xiaoairc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.tangren.xiaoairc.R
import com.tangren.xiaoairc.ui.theme.Xc

// ============================================================
// 基础元素
// ============================================================

/** 品牌标记：品牌色圆角方块 + 白色音箱图标（提示词第 2 节「可选 28dp logo」） */
@Composable
fun BrandMark(size: Dp = 28.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.29f))
            .background(Xc.Brand),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_brand),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.72f)
        )
    }
}

/** 顶栏：返回箭头 + 标题 + 0~2 个右侧操作图标（高 56dp，水平内边距 16dp） */
@Composable
fun XcTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Xc.Icon,
                modifier = Modifier
                    .size(24.dp)
                    .clickable(onClick = onBack)
            )
            Spacer(Modifier.width(24.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.weight(1f))
        if (actions != null) actions()
    }
}

/** 顶栏操作图标（24dp，间距 24dp） */
@Composable
fun TopBarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Icon(
        imageVector = icon,
        contentDescription = label,
        tint = Xc.Icon,
        modifier = Modifier
            .size(24.dp)
            .clickable(onClick = onClick)
    )
}

/** 分组小标题 */
@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = Xc.TextSecondary,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp)
    )
}

/** 白底 + 1dp 描边 + 圆角 12dp 的容器 */
@Composable
fun XcCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Xc.Surface)
            .border(1.dp, Xc.Border, RoundedCornerShape(12.dp))
            .padding(contentPadding),
        content = content
    )
}

/** 小标签 chip：11sp / #757575，底 #F0F0F0，圆角 4dp，内边距 2×6dp */
@Composable
fun TagChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Xc.ChipBg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 11.sp, color = Xc.TextSecondary)
    }
}

// ============================================================
// 骨架一：主界面
// ============================================================

/**
 * 状态主按钮：全宽 72dp，圆角 12dp。
 * 未激活 = 灰 #5F5F5F；激活 = 蓝 #1A73E8（同步换图标与文案）。
 */
@Composable
fun StatusButton(
    main: String,
    sub: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val bg = if (active) Xc.Accent else Xc.PrimaryBlock
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .border(2.dp, Xc.OnPrimaryBlock, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (active) Icons.Filled.Close else Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Xc.OnPrimaryBlock,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = main,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Xc.OnPrimaryBlock
            )
            Text(
                text = sub,
                fontSize = 12.sp,
                color = Xc.OnPrimaryBlockSub,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 入口卡片：白底描边，高 76dp，图标 + 两行文案 */
@Composable
fun EntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Xc.Surface)
            .border(1.dp, Xc.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Xc.Icon, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 首页入口列表行：行高 56dp，图标与文字间距 24dp，无卡片无分割线 */
@Composable
fun PlainRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Xc.Icon, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing?.invoke()
    }
}

// ============================================================
// 骨架二：复合条目卡片（二级列表页）
// ============================================================

/** 单选指示器：未选中空心灰 1.5dp；选中实心蓝 + 内嵌 8dp 白点 */
@Composable
fun RadioDot(selected: Boolean, size: Dp = 20.dp) {
    if (selected) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Xc.Accent),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(size * 0.4f)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    } else {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .border(1.5.dp, Xc.TextDisabled, CircleShape)
        )
    }
}

/**
 * 复合条目卡片：5 行内容
 * ① 标题 + ⋮  ② 标签 chip  ③ 左右双栏信息  ④ 2dp 进度条  ⑤ 时间/说明
 */
@Composable
fun ItemCard(
    selected: Boolean,
    title: String,
    tag: String?,
    leftInfo: String,
    rightInfo: String,
    progress: Float,
    footer: String,
    onSelect: () -> Unit,
    menuItems: List<Pair<String, () -> Unit>> = emptyList()
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Xc.Surface)
            .border(1.dp, Xc.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .padding(14.dp)
    ) {
        Box(Modifier.padding(top = 2.dp)) { RadioDot(selected) }
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            // ① 标题 + 溢出菜单
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "更多",
                        tint = Xc.Icon,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { menuOpen = true }
                    )
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        menuItems.forEach { (label, action) ->
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

            // ② 标签
            if (tag != null) {
                Spacer(Modifier.height(6.dp))
                TagChip(tag)
            }

            // ③ 双栏信息
            Spacer(Modifier.height(6.dp))
            Row {
                Text(
                    text = leftInfo,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = rightInfo,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 32.dp),
                    maxLines = 1
                )
            }

            // ④ 进度条
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp)),
                color = Xc.Accent,
                trackColor = Xc.Track
            )

            // ⑤ 时间 / 说明
            Spacer(Modifier.height(6.dp))
            Text(footer, fontSize = 12.sp, color = Xc.TextDisabled, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ============================================================
// 骨架三：设置行 & 通用控件
// ============================================================

/** 设置行：最小高 64dp，图标 24dp，间距 24dp */
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Xc.Icon, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing?.invoke()
    }
}

/** 带开关的设置行 */
@Composable
fun SwitchSettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    SettingsRow(icon = icon, title = title, subtitle = subtitle, trailing = {
        Switch(checked = checked, onCheckedChange = onChange)
    })
}

/** 灰阶主操作块按钮（52dp），与首页状态块同一套语言 */
@Composable
fun BlockButton(
    text: String,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val bg = when {
        !enabled -> Xc.TextDisabled
        active -> Xc.Accent
        else -> Xc.PrimaryBlock
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 白底描边输入框（默认色即由 colorScheme 的 primary/outline 对齐设计稿） */
@Composable
fun XcTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    numeric: Boolean = false,
    password: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = singleLine,
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
        modifier = modifier.fillMaxWidth()
    )
}
