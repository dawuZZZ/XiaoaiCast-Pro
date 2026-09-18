# UI 设计系统模板 + 三屏骨架提示词

> 本文件只保留**结构、配色、几何、字阶**等可复用的设计语言，不含任何具体业务内容。
> 文中所有 `[占位]` 文案（如 `[App 名称]`、`[主文案]`、`[列表项]`）均可替换为你的实际内容。
> 用途：粘贴给 AI 编程助手（Cursor / Claude Code / v0）生成界面；或给图像模型（Midjourney / 即梦 / Figma AI）出 UI 概念图。

---

## 0. 设计语言概述

- **形态**：Android 原生 App，单 Activity + 多页面路由
- **风格**：极简 Material Design 变体 —— 纯白底、高对比黑字、灰色主操作块、细边框卡片、无阴影（flat）
- **信息密度**：低，行高宽松，大量留白
- **字体**：Roboto / 系统默认无衬线，中文走系统黑体
- **三屏骨架**：主界面（标题 + 状态块 + 入口卡 + 列表）→ 条目列表页（顶栏 + 复合卡片列表）→ 设置页（顶栏 + 分组入口列表）

---

## 1. 设计系统（Design Tokens）

### 1.1 色板

| Token | 值 | 用途 |
|---|---|---|
| `bg-base` | `#FFFFFF` | 全局背景 |
| `text-primary` | `#111111` | 标题、列表主文字 |
| `text-secondary` | `#757575` | 副标题、说明文字、时间戳 |
| `text-disabled` | `#9E9E9E` | 未选中的辅助信息 |
| `surface-card` | `#FFFFFF` | 卡片底 |
| `border-card` | `#E3E3E3` | 卡片 1dp 描边 |
| `primary-block` | `#5F5F5F` | 主状态/主操作大按钮（灰阶） |
| `on-primary-block` | `#FFFFFF` | 灰块上的主文字 |
| `on-primary-block-sub` | `#D8D8D8` | 灰块上的副文字 |
| `accent` | `#1A73E8` | 选中态圆点、进度条高亮 |
| `icon` | `#1F1F1F` | 列表图标 |
| `divider-progress` | `#DADADA` | 进度条轨道 |

> 核心特征：**主操作用灰阶而非品牌色，唯一强调色 `#1A73E8` 只出现在"选中态"与"进度条"。**

### 1.2 字阶

| Token | 字号 / 字重 | 用途 |
|---|---|---|
| `title-xl` | 20sp / Bold | 首页 App 名称 |
| `title-lg` | 18sp / SemiBold | 顶栏页面标题 |
| `body-lg` | 15sp / Medium | 列表项、卡片标题 |
| `body-md` | 14sp / Regular | 卡片主信息 |
| `body-sm` | 12sp / Regular | 副标题、时间、标签 |
| `label-xs` | 11sp / Regular | 微标签 |

### 1.3 几何

- 屏幕水平安全边距：`16dp`
- 卡片圆角：`12dp`；灰块圆角：`12dp`
- 卡片内边距：`14dp`（竖直）/ `16dp`（水平）
- 卡片间距：`12dp`
- 列表行高：`56dp`（首页）/ `64dp`（设置页）
- 图标尺寸：`24dp`（列表）、`28dp`（首页品牌 logo，可选）
- 进度条高度：`2dp`，全圆角
- 无阴影、无 elevation；卡片靠描边区分层次

---

## 2. 骨架一：主界面

### 2.1 结构拆解

```
[状态栏]  （系统栏，不实现）
────────────────────────────────────
[标题区]  (可选 logo 28dp)  [App 名称]        ← title-xl, 20sp Bold
                                                     间距 16dp
┌──────────────────────────────────┐
│ ([圆形图标 32dp])                  │  ← 背景 #5F5F5F, 圆角 12dp
│  [主文案]          ← 16sp, #FFFFFF │     高度约 72dp, 内边距 16dp
│  [副文案]          ← 12sp, #D8D8D8 │
└──────────────────────────────────┘
                                                     间距 12dp
┌──────────────────────────────────┐
│ ([列表图标])  [主文案]             │  ← 白底 + 1dp 描边, 圆角 12dp
│              [副文案]              │     高度约 76dp
└──────────────────────────────────┘
                                                     间距 24dp
  ([图标])  [列表项 A]                              ← 行高 56dp
  ([图标])  [列表项 B]
  ([图标])  [列表项 C]
  ([图标])  [列表项 D]
```

**关键细节**
- 标题区为普通 Row：可选 logo 在左，标题紧随其后，垂直居中，左对齐 16dp（非 AppBar 风格）。
- 状态块左侧为圆形轮廓图标；右侧两行文字（主/副），整块可点击。
- 状态块两种状态：灰 `#5F5F5F`（未激活）/ 蓝 `#1A73E8`（激活），激活态切换图标与文案。
- 入口卡片为白底描边卡，左侧图标 + 右侧两行（主/副文案）。
- 下方入口列表**不包卡片**，平铺在白底，图标与文字间距 24dp，无分割线。

### 2.2 提示词（中文 · 代码生成）

```
用 Jetpack Compose（Material 3）实现一个 Android 应用主界面，纯白背景，无阴影，极简风格。
所有文案均为占位符，请按实际业务替换。

1) 标题区：Row 垂直居中，间距 12dp。左侧可选 28dp 品牌 logo（自定义 SVG），
   右侧 [App 名称]，20sp、Bold、#111111。

2) 状态主按钮：全宽圆角矩形，背景 #5F5F5F，圆角 12dp，高 72dp，内边距 16dp，Row 垂直居中。
   - 左：32dp 圆形轮廓图标（2dp 描边 #FFFFFF）
   - 右：Column，主文案 16sp #FFFFFF，副文案 12sp #D8D8D8
   - 可点击切换状态：灰阶未激活 ↔ 蓝色 #1A73E8 激活（同步替换图标与文案）

3) 入口卡片：全宽，白底，1dp 描边 #E3E3E3，圆角 12dp，高 76dp，内边距 16dp，Row 垂直居中。
   - 左：24dp 列表图标 #1F1F1F
   - 右：Column，主文案 15sp Medium #111111；副文案 12sp #757575

4) 入口列表：上间距 24dp，N 项（示例 4 项）。每项 Row 高 56dp，图标 24dp #1F1F1F，
   与文字间距 24dp，文字 15sp Medium #111111。无卡片、无分割线。
```

### 2.3 提示词（English · 图像生成）

```
Mobile app UI mockup, single phone screen, pure white background, flat minimal Material Design,
extremely clean. Top: optional small brand logo next to a bold black app title. Below: a large
rounded dark-gray (#5F5F5F) card with a circular icon on the left and two lines of white text
(main + secondary). Below it: a white outlined rounded card with a list icon and two lines of
text (title + subtitle). Then a vertical menu list of plain rows with simple black line icons
and label text. Generous whitespace, 16px side margins, no shadows, monochrome palette with one
blue accent. Screenshot style, 9:19.5 aspect ratio.
```

---

## 3. 骨架二：条目列表页（二级菜单）

### 3.1 结构拆解

```
[顶栏]  ← 返回    [页面标题]              [刷新]  [新建]
        高度 56dp，标题 18sp SemiBold，图标 24dp #1F1F1F
────────────────────────────────────
┌────────────────────────────────────┐
│ ○  [条目 A]                    ⋮   │  ← 未选中：空心圆（1.5dp #9E9E9E 描边）
│    [标签]                          │  ← 11sp 小标签，浅灰底圆角框
│    [左信息]              [右信息]    │  ← 同基线左右分布
│    ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬           │  ← 2dp 进度条，轨道 #DADADA
│    [时间/说明]                     │  ← 12sp #9E9E9E
└────────────────────────────────────┘
                                    间距 12dp
┌────────────────────────────────────┐
│ ◉  [条目 B]                    ⋮   │  ← 选中：实心蓝点 #1A73E8
│    [标签]
│    [左信息]              [右信息]
│    ▬▬▬▬▬▬▬▬▬▬
│    [时间/说明]
└────────────────────────────────────┘
```

**关键细节**
- 卡片为**复合内容卡片**：标题行 + 标签行 + 双栏信息行 + 进度条 + 时间行，共 5 行。
- 左侧选择圆点与标题行居中对齐；未选中空心灰、选中实心蓝 `#1A73E8` + 内嵌白点。
- `[标签]` 为小 chip（浅灰底 `#F0F0F0`、圆角 4dp、内边距 2×6dp）。
- 双栏信息行：左信息靠左、右信息靠右，右端为始终可见的 `⋮` 溢出菜单。
- 整卡点击=切换选中（单选语义，蓝点唯一）；点 `⋮` 弹出操作菜单。

### 3.2 提示词（中文 · 代码生成）

```
用 Jetpack Compose 实现二级列表页，纯白背景，无阴影。所有文案为占位符。

1) 顶栏：高度 56dp，水平内边距 16dp，Row 垂直居中。
   左：24dp 返回箭头。中：标题 18sp SemiBold #111111，左内边距 24dp。
   右：Spacer 撑开，24dp 刷新图标、间隔 24dp、24dp 加号图标。图标 #1F1F1F。

2) 列表：LazyColumn，水平内边距 16dp，卡片间距 12dp，顶部间距 8dp。

3) 单个卡片（composable ItemCard）：
   白底，1dp 描边 #E3E3E3，圆角 12dp，内边距 14dp，Row 布局：
   - 左：20dp 圆形选择指示器。未选中=空心圆 1.5dp #9E9E9E 描边；
     选中=实心 #1A73E8 + 内嵌 8dp 白点。与卡片顶部对齐，右间距 12dp
   - 右：Column（weight 1f）
     a) 标题行：Row，标题 15sp Medium #111111 + Spacer + 最右 24dp ⋮ 图标
     b) 标签行：[标签] 11sp #757575，背景 #F0F0F0，圆角 4dp，内边距 2dp/6dp
     c) 双栏信息行：Row，左[左信息] 12sp #757575，右[右信息] 12sp #757575，右内边距 32dp
     d) 进度条：LinearProgressIndicator，高 2dp，全圆角，轨道 #DADADA，进度 #1A73E8
     e) 时间行：[时间/说明] 12sp #9E9E9E
     各行垂直间距 6dp

4) 交互：点卡片切换选中（单选）；点 ⋮ 弹 DropdownMenu。顶栏加号跳转新建页；刷新触发列表更新。
```

### 3.3 提示词（English · 图像生成）

```
Mobile app UI mockup, second-level list screen, pure white background, flat design.
Top bar: back arrow on the left, bold title, a refresh icon and a plus icon on the right.
Two rounded white cards with thin light-gray borders and 12px radius. Each card contains:
a circular radio indicator on the left (first hollow gray = unselected, second filled blue =
selected), a bold item name, a small gray tag chip, a row with left info text and right info text,
a 2px progress bar, and a timestamp line. A vertical three-dot overflow menu at top-right of
each card. Minimal, monochrome with one blue accent, generous spacing, no shadows.
```

---

## 4. 骨架三：设置页

### 4.1 结构拆解

```
[顶栏]  ← 返回    [页面标题]
────────────────────────────────────
  ([图标])  [分组项 A]
  ([图标])  [分组项 B]
  ([图标])  [分组项 C]
  ([图标])  [分组项 D]        ← 可选：最后一项用品牌 logo 作图标
```

**关键细节**
- 列表**无卡片、无分割线**，图标与文字平铺在白底。
- 行高比首页更大（约 64dp），图标 24dp（最后一项可用 28dp 品牌 logo），图标与文字间距 24dp。
- 顶栏同列表页（返回 + 标题），无右侧操作图标。

### 4.2 提示词（中文 · 代码生成）

```
用 Jetpack Compose 实现设置页。所有文案为占位符。

1) 顶栏：返回箭头 24dp + 标题 18sp SemiBold #111111，高度 56dp，水平内边距 16dp，无右侧图标。

2) 列表：Column，水平内边距 16dp，顶部间距 16dp，N 项（示例 4 项）：
   每项 Row 高 64dp，垂直居中，图标 24dp（最后一项 28dp）#1F1F1F，与文字间距 24dp，
   文字 15sp Medium #111111。无卡片、无分割线、无背景填充。点击进入子页面。
```

### 4.3 提示词（English · 图像生成）

```
Mobile app UI mockup, settings screen, pure white background, flat minimal design.
Top bar with a back arrow and bold title. Below, a plain vertical menu list with rows,
each a simple black line icon on the left and label text on the right.
Tall row height, generous whitespace, no dividers, no cards, no shadows.
```

---

## 5. 一次性生成三屏的合并提示词

```
你是一名资深 Android UI 工程师。请用 Jetpack Compose + Material 3 实现一个 App 的三个界面，
风格完全一致：纯白背景、无阴影（全部 elevation = 0）、卡片用 1dp #E3E3E3 描边区隔、
文字用 #111111 / #757575 两级灰阶、唯一强调色 #1A73E8（仅用于选中态和进度条）。
所有文字均为占位符，请替换为实际业务内容。

设计 Tokens：水平边距 16dp；卡片圆角 12dp、内边距 14dp、间距 12dp；列表行高 56–64dp；
图标 24dp；字阶 20sp Bold（页面大标题）/ 18sp SemiBold（顶栏标题）/ 15sp Medium（列表主文字）
/ 12sp Regular（辅助信息）。

骨架一 - 主界面：
顶部 Row 为可选 28dp logo + [App 名称]（20sp Bold）。下方一个背景 #5F5F5F 的圆角 12dp、高 72dp
状态按钮，内含圆形图标、白色主文案（16sp）与 #D8D8D8 副文案（12sp）；点击切换蓝 #1A73E8 激活态。
再下方一个白底描边卡片（高 76dp），图标 + 主文案/副文案。最后是 N 项无卡片入口列表（行高 56dp）。

骨架二 - 列表页：
顶栏 = 返回箭头 + [页面标题] + 右侧刷新与加号图标。
LazyColumn 列出复合卡片：左侧单选圆点（未选中空心灰、选中实心 #1A73E8）、
右侧 Column 含 ① 标题 15sp Medium + 最右 ⋮ ② 小灰标签 chip ③ 左/右双栏信息 ④ 2dp 进度条
⑤ 时间/说明行。点卡片切换选中（单选），点 ⋮ 弹菜单。

骨架三 - 设置页：
顶栏 = 返回箭头 + [页面标题]。下面 N 项无卡片无分割线的入口（行高 64dp，图标文字间距 24dp）。

要求：用 MaterialTheme 定义 colorScheme 与 typography，抽出可复用的
StatusButton、ItemCard、SettingsRow 三个 composable，代码可直接编译运行。
```

---

## 6. 组件级提示词（按需单独生成）

| 组件 | 提示词要点 |
|---|---|
| 状态主按钮 | 全宽 72dp 灰块 `#5F5F5F`，左侧 32dp 圆形轮廓图标，右侧两行文字（主 16sp 白 / 副 12sp `#D8D8D8`），圆角 12dp，两态切换（灰 ↔ 蓝 `#1A73E8`） |
| 复合条目卡片 | 白底描边卡，左侧 20dp 单选圆点，右侧五行：标题+⋮ / 标签 chip / 双栏信息 / 2dp 进度条 / 时间，圆角 12dp，内边距 14dp |
| 标签 chip | 文字 11sp `#757575`，背景 `#F0F0F0`，圆角 4dp，内边距 2dp×6dp |
| 设置行 | 高 64dp，左 24dp 图标 `#1F1F1F`，间距 24dp，文字 15sp Medium `#111111`，无背景无分割线 |
| 顶栏 | 高 56dp，左 24dp 返回箭头，间距 24dp 标题 18sp SemiBold，右侧可有 0–2 个 24dp 操作图标，间距 24dp |

---

## 7. 图标指引（通用占位）

| 位置 | 图标类型（可替换） |
|---|---|
| 首页品牌 logo | 可选，自定义 SVG，可替换为你的图标 |
| 状态块图标 | 圆形轮廓图标（语义由业务决定） |
| 入口卡片图标 | 通用列表图标 |
| 入口列表图标 | 黑色线性图标（语义自定） |
| 顶栏返回 / 刷新 / 新建 | 箭头 / 刷新 / 加号 |
| 卡片溢出菜单 | `⋮`（三个点） |
| 设置页图标 | 通用线性图标，最后一项可用品牌 logo |

---

## 8. 状态与交互备注

- 状态块两态：未激活（灰 `#5F5F5F`）/ 激活（蓝 `#1A73E8`），切换图标与文案。
- 复合卡片为**单选**语义，整列表最多一个蓝色实心圆点。
- 顶部状态栏为系统栏，不必实现；需处理 `systemBarsPadding()` / `WindowInsets`。
- 底部横条为 Android 手势导航条，属系统 UI。
- 无深色模式时 `colorScheme` 固定 light；扩展深色：背景 `#121212`、卡片描边 `#2E2E2E`、灰块 `#3A3A3A`。
