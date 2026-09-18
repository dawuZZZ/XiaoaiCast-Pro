# XiaoaiCast Free · 开发说明

> 本文记录这个项目**为什么做、怎么做、做成了什么样**：背景与动机、上游 fork 关系与许可策略、
> 需求决策记录（含被砍掉的）、架构与关键实现、UI 设计落地、构建与真机验证过程、踩坑清单。
>
> 面向读者：项目维护者 / 想二次开发的人 / 想评估许可合规的人。
> 快速上手请看 [README](../README.md)。

- 项目版本：**0.2.0**
- 文档日期：2026-09-18
- 应用名：XiaoaiCast Free
- 许可：MIT

---

## 1. 背景：为什么会有这个项目

### 1.1 要解决的现实问题

想把手机音乐 App（网易云音乐 / QQ 音乐）里的歌，投到**小爱音箱**上放。

难点在于：小爱音箱**不是标准 DLNA 设备**。音乐 App 的「投屏 / QPlay」走的是 DLNA/UPnP 协议，
而小爱音箱只认**小米云下发的播放指令**（`player_play_music` / `player_play_url`）。
两者协议不通，直接投是被忽略的。

### 1.2 已有方案与它们的门槛

| 方案 | 做法 | 门槛 |
|---|---|---|
| XiaoMusic / miair-next | 在 NAS / 服务器上跑一个服务，冒充 DLNA 设备并转译指令 → 小米云 | **需要一台常开的 NAS / 服务器 + Docker** |
| 各类「小爱音箱接入 xxx」教程 | 同上，或魔改音箱 | 同上，且部分要刷机 |

对"家里没有 NAS、只有手机和音箱"的人来说，这些方案的第一步就卡住了。

### 1.3 关键洞察（xiaoai-cast 的出发点）

DLNA 投屏里，音乐 App 需要找的是一个**局域网内的 DLNA 渲染器（Renderer）**：
它只要能被 SSDP 发现、能回应 UPnP 的 SOAP 请求、能提供一个可拉的音频地址就够了。
**这个角色完全可以由手机自己扮演** —— 手机本来就在同一个局域网里、本来就装着音乐 App、
本来就能访问那些音频 CDN。

于是就有了 **xiaoai-cast**：一个纯手机端的 App，把自己伪装成 DLNA 设备，
把收到的投屏指令翻译成小爱音箱听得懂的小米云指令。

### 1.4 本仓库（XiaoaiCast Free）的由来

xiaoai-cast 把主链路跑通了，但还比较"薄"：没有音频缓冲、机型适配靠手勾开关、
登录没有限速、没有扫码登录、日志导出会带出凭据、改配置必须重启 App。

而 **miair-next** 在这些工程细节上积累了很多实战经验。于是本仓库的目标定为：

> **在保持"纯手机端、零额外硬件"这个定位不变的前提下，
> 把 miair-next 中与手机端形态相容的工程能力移植进来。**

关键约束：**任何需要额外服务器组件、或依赖 GPL 代码的能力，一律不做或改为独立重写。**

---

## 2. 上游与 fork 关系

| 项目 | 与本仓库的关系 | 许可 | 说明 |
|---|---|---|---|
| [`xiaoai-cast`](https://github.com/lyx838661446-hue/xiaoai-cast) | **直接 fork 基线** | MIT | 本仓库从它的源码起步，包名 `com.tangren.xiaoairc` 保持不变以便原地升级 |
| [`deerwan/miair-next`](https://github.com/deerwan/miair-next) | **能力参考（仅思路）** | GPL-3.0（其 FairPlay 组件 GPL-2.0） | 阅读其行为与实测结论后**独立重写**，未复制任何源码 |
| [`KiriChen-Wind/MiAir`](https://github.com/KiriChen-Wind/MiAir)、[`yihong0618/miservice`](https://github.com/yihong0618/miservice)、[`hanxi/xiaomusic`](https://github.com/hanxi/xiaomusic) | DLNA / 小米云调用方式参考 | MIT | 协议层做法同源 |
| NanoHTTPD 2.3.1 | **vendored 并打补丁** | BSD-3-Clause | 源码放在 `app/src/main/java/fi/iki/elonen/NanoHTTPD.java` |

### 2.1 vendored NanoHTTPD 的补丁

原版 NanoHTTPD 2.3.1 的 `Method` 枚举**没有** `SUBSCRIBE` / `UNSUBSCRIBE` / `NOTIFY`，
这三个动词会在进入 `serve()` 之前就被库直接 400 掉，导致 UPnP 的 **GENA 事件订阅**失效 ——
QQ 音乐 / QPlay 这类依赖事件订阅的投屏端会连不上。

补丁只做一件事：**给 `Method` 枚举补齐这三个动词**，其余与原版一致。

### 2.2 许可策略（重要）

本仓库**保持 MIT**，因此对所有上游代码采取分级处理：

| 处理方式 | 适用对象 | 理由 |
|---|---|---|
| 直接复用 | xiaoai-cast 源码（MIT） | 同许可，兼容 |
| 事实性数据 | 机型白名单（`DeviceCatalog`） | 机型 ↔ 接口要求是**客观事实**，不构成表达性代码的复制 |
| 参考后独立重写 | miair-next 的工程能力（缓冲、限速、自愈、扫码…） | 避免 GPL-3.0 传染 |
| **整体不做** | AirPlay 接收器 | miair-next 的 AirPlay 引擎含 **GPL-2.0 的 FairPlay 组件**，并入 MIT 项目会触发 copyleft；且其体量约 1 万+ 行，手机端还要实时解密+转码 |

> 结论：**仓库内不含任何 GPL 代码**。所有涉及 miair-next 的部分都是"读行为、自己实现"。
> 若将来要引入 AirPlay 或直接挪用 miair-next 源码，**必须先把本仓库整体改为 GPL-3.0**。

---

## 3. 需求决策记录

| # | 需求 | 决策 | 原因 / 说明 |
|---|---|---|---|
| 1 | DLNA 音频缓冲 | ✅ 做 | 源站断流/重定向更抗造；源站不支持 Range 时也能 seek；总长确切后进度条与 seek 才准 |
| 2 | 机型自动分派播放接口 | ✅ 做 | 内置白名单按 `hardware` 自动选 `music` / `url`，不再让用户手勾 |
| 3 | 登录失败限速 | ✅ 做 | 反复失败会触发小米风控，越试越被拦；加窗口限速 + 持久化计数 |
| 4 | 连续失败自愈 | ✅ 做 | 连续失败到阈值自动热重启投屏链路（对齐 miair-next 的 auto restart 思路） |
| 5 | 扫码登录 | ✅ 做 | 不触发验证码、不用去浏览器翻 Cookie，手机端体验最好的一条路 |
| 6 | 子服务热重启 | ✅ 做 | 改端口/配置后不必退出 App |
| 7 | 日志脱敏 | ✅ 做 | 导出分享日志时会把 passToken 一起发出去，是真实隐患 |
| 8 | UI 重写为 Compose | ✅ 做 | 按设计稿三屏骨架（主界面 / 复合卡片列表 / 设置页）实现 |
| 9 | **AirPlay 接收** | ❌ 不做 | 许可（GPL）+ 体量（1 万+ 行）+ 手机端实时解密转码成本；见 §2.2 |
| 10 | **带屏封面·歌词（audioID）** | ❌ 不做 | **用户明确砍掉** |
| 11 | **通知推送（飞书 / WxPusher）** | ❌ 不做 | **用户明确砍掉**；手机端本身有系统通知，外推属增益项 |
| 12 | Web 管理后台 / Docker / 多架构 | ❌ 不做 | 与"跑在手机里"的形态不相容 |

---

## 4. 架构与开发逻辑

### 4.1 数据流

```
┌─────────────┐  DLNA/QPlay   ┌────────────────────────────────┐  小米云 API  ┌──────────┐
│  音乐 App    │ ────────────> │        XiaoaiCast Free          │ ──────────> │ 小爱音箱  │
│ (网易云/QQ)  │  ①发现设备     │  ┌──────────┐  ┌─────────────┐  │ ③下发播放指令 │ L05B/L05C│
└─────────────┘  ②发播放URL    │  │SSDP应答器 │  │ DLNA 渲染器  │  │             └──────────┘
                               │  └──────────┘  │ (UPnP 状态机)│  │                    ▲
                               │  ┌──────────────────────────┐  │                    │
                               │  │ UpnpHttpServer           │  │                    │
                               │  │  设备描述 / SOAP / GENA   │  │                    │
                               │  │  /media/<token> 音频代理  │  │                    │
                               │  └──────────────────────────┘  │                    │
                               └────────────────────────────────┘                    │
                                              └──── ④本地音频供给（代理/缓冲）─────────┘
```

关键点：**音箱去拉音频时，拉的是手机上的短链接**（可选缓冲模式，先把整条音频落到本地文件）。

### 4.2 模块职责

| 分层 | 模块 | 文件 | 职责 |
|---|---|---|---|
| 协议 | SSDP 应答器 | `dlna/SsdpResponder.kt` | 监听 `239.255.255.250:1900`，回复 M-SEARCH |
| 协议 | UPnP HTTP 服务 | `dlna/UpnpHttpServer.kt` | 设备描述 / SOAP / GENA 事件订阅 / 音频流代理 |
| 协议 | DLNA 渲染器 | `dlna/UpnpRenderer.kt` | 渲染器状态机，把 UPnP 指令翻译成小米云调用 |
| 音频 | 音频代理 | `dlna/MediaProxy.kt` | 双模式：**直连**（边取边吐）/ **缓冲**（先落地再供给）；Range 与 seek |
| 音频 | 本地缓冲 | `dlna/MediaBuffer.kt` | 后台线程整条下载到文件，带大小上限，支持按需等待与精确偏移 |
| 云端 | 小米账号 | `xiaomi/MiAccount.kt` | 三种登录（密码 / passToken / 扫码）+ serviceToken 换发与缓存 |
| 云端 | 云指令 | `xiaomi/MiNaClient.kt` | 设备列表、播放控制、TTS、音量 |
| 云端 | 登录保护 | `xiaomi/LoginGuard.kt` | 失败限速（窗口 5 次 / 300s）+ 连续失败自愈计数 |
| 云端 | 型号目录 | `xiaomi/DeviceCatalog.kt` | 机型 → 播放接口的自动分派 |
| 云端 | 扫码登录 | `xiaomi/QRLogin.kt` | 二维码获取 + 长轮询 + 凭据落地 |
| 应用 | 前台服务 | `CastService.kt` | 编排链路、WakeLock/MulticastLock 保活、热重启、自愈看门狗 |
| 应用 | 音箱控制 | `SpeakerClient.kt` | 面向"音箱"的操作封装（含 music/url 降级） |
| 应用 | 配置 | `Prefs.kt` | SharedPreferences 配置项 |
| 安全 | 脱敏 | `util/Masking.kt` | 导出/复制日志前清洗凭据 |
| UI | 主题 | `ui/theme/Theme.kt` | 设计 tokens（`Xc` 对象）与字阶 |
| UI | 组件 | `ui/Components.kt` | `StatusButton` / `EntryCard` / `ItemCard` / `SettingsRow`… |
| UI | 状态与动作 | `ui/AppState.kt` | 等价 ViewModel：路由栈 + 可观察状态 + 业务动作 |
| UI | 页面 | `ui/screens/*.kt` | 首页 / 音箱列表 / 账号 / 日志 / 设置 / 帮助 |

### 4.3 关键实现说明

#### （1）音频缓冲：为什么落地文件而不是内存

miair-next 的 `media_buffer.py` 把整条音频放进 Python `bytearray`。手机端不能这么做 ——
一首 flac 可能几十 MB，几首就能把 App 撑爆。

本项目的做法：**下载到 `cacheDir/buffers/` 下的临时文件**，通过 `RandomAccessFile` 顺序写入，
读取时用 `FileInputStream` + `channel.position(start)` 定位。并加了：
- **硬上限 200MB**：媒体端点是无鉴权的，远端 `Content-Length` 不可信，必须防撑爆磁盘；
- **阻塞式实现**（后台线程 + `synchronized` 条件等待），以便直接嵌进 NanoHTTPD 的同步请求处理路径；
- **精确 seek**：拿到确切总长度后 `offset = pos / duration * total`，而不是按比例估算。

#### （2）双模式与自动回退

`MediaProxy` 支持两种模式，由 `bufferFactory` 是否为空决定：

- **直连模式**（默认）：手机边从源站取、边吐给音箱，起播快。
- **缓冲模式**：先缓冲再供给，抗断流、支持本地 Range、seek 更准，代价是起播略慢。

**回退保护**：缓冲未就绪（下载超时/失败）时，**本次请求自动退回直连**，保证音箱不断流。
开区间请求（`bytes=N-`）会等待整条下载完成，避免读流过早 EOF。

#### （3）机型自动分派

原来是用户手动勾「使用 music 接口」。现在 `DeviceCatalog.shouldUseMusicApi(hardware, forceMusicApi, compatibilityMode)`：
1. `hardware` 命中白名单 → 走 `player_play_music`；
2. 用户开了"兼容模式" → 强制走 `player_play_url`（排查用）；
3. 否则按用户开关。

另外 `SpeakerClient` 保留了**运行期降级**：本次会话里 music 接口一旦失败，后续自动改用 url 接口。

#### （4）登录保护闭环

```
登录失败 → LoginGuard.recordFailure() → 写入 Prefs（窗口时间戳列表 + 连续计数）
                │
                ├─ 窗口内 >= 5 次 → 直接拒绝登录，提示还需等待 Ns
                │
                └─ 连续计数 >= 6 → CastService 看门狗（60s 一次）consumeSelfHeal()
                                     → 热重启投屏链路 + 弹出"已自愈"提示
登录成功 → recordSuccess() → 清空窗口与计数
```

计数持久化在 Prefs，**重启 App 不清零**，避免"重启一遍绕过限速"。

#### （5）扫码登录流程

```
1. GET  account.xiaomi.com/pass/serviceLogin?sid=mijia&_json=true   → _sign / qs / callback
2. GET  account.xiaomi.com/longPolling/loginUrl?_qrsize=640&...     → qr(图 URL) / loginUrl / lp(长轮询地址)
3. GET  lp（服务端挂起约 30s）                                       → 米家 App 确认后返回 passToken + userId
4. 上层把 userId + passToken 落库并切到 passToken 模式登录
```

细节：自带 `MemoryCookieJar`（流程需跨请求保留 Cookie）；读超时设 45s 以覆盖 30s 长轮询；
`_qrsize` 取 640 以便大屏显示清晰。

#### （6）脱敏接在导出路径上

`Masking.scrubLog(text, knownSecrets)` 做两件事：
1. **精确替换**已知真实凭据（长度 ≥ 8 才处理，避免误伤短字符串）；
2. **正则兜底** `(passToken|serviceToken|ssecurity|password)([=:]\s*["']?)([^\s"',;&]{4,})`。

日志页的「复制」与「导出分享」都先过这层，避免用户求助时把凭据一起发出去。

#### （7）UI 分层与路由

- **tokens 一层**：`ui/theme/Theme.kt` 的 `Xc` 对象是所有颜色的唯一来源；
- **组件一层**：`ui/Components.kt` 全部无状态，只吃参数与回调；
- **屏幕一层**：`ui/screens/*.kt` 只声明 UI，不直接发网络请求；
- **状态一层**：`ui/AppState.kt` 持有可观察状态（`mutableStateOf`）与业务动作，等价 ViewModel；
  业务实现仍复用既有类（`MiAccount` / `MiNaClient` / `CastService`…）。
- **路由**：单 Activity + 轻量返回栈（`stack: List<Screen>`），系统返回键交给 `BackHandler`。
  没有引入 navigation-compose，为了少一个依赖。

### 4.4 与 xiaoai-cast 的代码差异总览

| 类型 | 内容 |
|---|---|
| 新增 | `dlna/MediaBuffer.kt`、`xiaomi/DeviceCatalog.kt`、`xiaomi/LoginGuard.kt`、`xiaomi/QRLogin.kt`、`util/Masking.kt`、`ui/theme/Theme.kt`、`ui/Components.kt`、`ui/AppState.kt`、`ui/screens/*`（6 个页面） |
| 修改 | `dlna/MediaProxy.kt`（双模式）、`dlna/UpnpRenderer.kt`（换歌释放缓冲）、`xiaomi/MiAccount.kt`（接入限速）、`SpeakerClient.kt`（型号分派）、`Prefs.kt`（新增配置）、`CastService.kt`（热重启 + 看门狗 + 缓冲接线）、`MainActivity.kt`（改 Compose 宿主）、`app/build.gradle.kts`（Compose） |
| 删除 | 旧 XML 布局 `activity_main.xml` / `item_step.xml` / `dialog_qr.xml`、`bg_step_badge.xml` |
| 修正 | `UpnpHttpServer.kt` 一处与代码矛盾的注释（原文说 SUBSCRIBE 分支"到不了"，实际已打补丁） |

---

## 5. UI 设计落地

设计来源：`UI生成提示词.md`（设计 tokens + 三屏骨架）+ 灰度参考图（Clash Meta 风格）。

### 5.1 设计语言

- 纯白底、**无阴影**（全部 elevation = 0）、卡片靠 1dp `#E3E3E3` 描边区隔；
- 主操作用**灰阶** `#5F5F5F`，唯一强调色 `#1A73E8` **只用于选中态与进度条**；
- 水平边距 16dp；卡片圆角 12dp / 内边距 14dp / 间距 12dp；行高 56dp（首页）/ 64dp（设置）；
- 字阶：20 Bold（页面大标题）/ 18 SemiBold（顶栏）/ 15 Medium（列表主文字）/ 12 / 11。

### 5.2 三屏骨架 → 落地页面

| 设计骨架 | 落地页面 | 说明 |
|---|---|---|
| 主界面（标题 + 状态块 + 入口卡 + 平铺列表） | `Home.kt` | 状态块灰/蓝两态 = 服务停止/运行中，点击启停 |
| 二级列表（顶栏 + 复合卡片单选） | `Devices.kt` | 每台音箱一张卡：单选圆点 + 状态 + 音量进度条 + `⋮` 菜单 |
| 设置页（顶栏 + 分组入口） | `Settings.kt` | 开关即时生效；参数（端口/设备名/音量/type）卡片内输入 + 保存 |
| （扩展） | `Account.kt` | 扫码 / 密码 / passToken 三路；两态选择块 + 输入卡 |
| （扩展） | `Log.kt` | 一键自检 + 实时日志 + 复制（脱敏）/ 导出 / 清空 |
| （扩展） | `Help.kt` | 9 步使用步骤（实时标记）+ 投屏指引 + 说明 |

### 5.3 与设计稿的偏差（有意为之）

1. **"新建 +" 改成"手动添加 deviceId"** —— 音箱不像代理订阅有"新建"语义，
   这里的真实需求是"自动发现失败时手填兜底"。
2. **设置页增加了参数输入卡片** —— 设计骨架只有分组入口行，但端口/设备名/音量/type 必须可编辑，
   于是按同一套语言做成"卡片内输入框 + 保存块按钮"。
3. **状态块激活态用 `Close` 图标** —— 表示"点击停止"。

---

## 6. 构建与真机验证

### 6.1 本机工具链（隔离目录，不污染系统）

| 组件 | 版本 | 来源 |
|---|---|---|
| JDK | OpenJDK 17.0.2 | 华为云镜像 `mirrors.huaweicloud.com/openjdk/...` |
| Android SDK | platform-34 / build-tools 34.0.0 / platform-tools | `dl.google.com` cmdline-tools + `sdkmanager` |
| Gradle | 8.11.1 | 华为云镜像 `mirrors.huaweicloud.com/gradle/...` |
| GRADLE_USER_HOME | `~/.workbuddy/binaries/gradle-home` | 隔离，不动用户默认 `~/.gradle` |

一键构建：`bash scripts/local-build.sh [release]`

### 6.2 编译结果

```
:app:assembleDebug    BUILD SUCCESSFUL   0 error / 0 warning
:app:assembleRelease  BUILD SUCCESSFUL   0 error / 0 warning
```

### 6.3 真机验证（小米 13 / fuxi / 2211133C）

| 项目 | 结果 |
|---|---|
| 安装 | `adb install -r` → Success |
| 启动 | 进程存活，logcat 无 `FATAL` / `AndroidRuntime` |
| 页面渲染 | 首页 / 音箱列表 / 账号 / 设置 均与设计稿一致，截图见 `docs/screenshots/` |
| 桌面图标 | 名称与 logo 已更新（被 MIUI 截断显示为「XiaoaiCast F…」） |
| 扫码登录 | 二维码铺满对话框宽度，边缘锐利可扫 |
| 尚未验证 | **投屏主链路未端到端实测**（需真机 + 音箱 + 音乐 App 一起试） |

---

## 7. 踩坑清单（汇总）

### 7.1 构建环境

1. **Gradle 配公司代理 → `Connection refused`，报错却是"插件找不到"**
   `http_proxy=127.0.0.1:<port>` 这类代理往往**只对本会话的 curl 可用**，
   Gradle 守护进程连它会 `Connect to 127.0.0.1:<port> failed: Connection refused`，
   而 Gradle 把它包装成 `Plugin [id: 'com.android.application'] was not found` —— 极具误导性。
   诊断：`gradle --debug ... | grep -iE "Performing HTTP|Connection refused"`；
   对策：先测 `curl --noproxy '*'`，直连可达就让 Gradle 走直连。
2. **`services.gradle.org` 走不通**：它 307 跳到 `github.com/.../releases/download/...`，
   经代理返回 `Empty reply from server`（http 000）。换华为云 / 腾讯云镜像即可。
3. **Kotlin 插件 marker 不在 Google Maven**（404 正常），在 Maven Central；
   `pluginManagement` 必须包含 `mavenCentral()`。
4. 镜像可用性实测：**tuna 的 Adoptium 目录 403、aliyun 的 adoptium 路径 404**，华为云最稳。

### 7.2 Compose 编码

5. **可组合函数的 `onClick` 必须是最后一个参数**
   否则尾随 lambda 会绑到最后一个参数，报 `No value passed for parameter 'onClick'`。
   本项目为此调整了 `BlockButton` / `PlainRow` 的参数顺序。
6. **`Icons.Filled.ArrowBack` / `List` / `Send` 已弃用** → 用 `Icons.AutoMirrored.Filled.*`。
7. **`LinearProgressIndicator(progress = 0.5f)` 已弃用** → 用 lambda 重载 `progress = { 0.5f }`。
8. **`Image(bitmap = b)` 不给 modifier 时会"按位图 px 当 dp 用"**
   640px 的图在 440dpi 屏上只显示成 ~233dp，看着很小。
   要铺满：`Modifier.fillMaxSize()` + `contentScale = ContentScale.Fit`；
   二维码 / 线条图再加 `filterQuality = FilterQuality.None`（最近邻）保持边缘锐利。

### 7.3 adb 真机操作

9. **别用 `adb exec-out screencap -p > x.png`**：Git Bash 会污染二进制流，PNG 打不开；
   统一 `screencap` 到 `/sdcard` 再 `pull`。
10. **全黑截图 = 屏幕熄了**，先 `input keyevent KEYCODE_WAKEUP`。
11. **点坐标优先用真实 bounds**：
    `adb shell "uiautomator dump /sdcard/ui.xml; grep -o 'text=\"目标\"[^>]*bounds=\"[^\"]*\"' /sdcard/ui.xml"`，
    取 bounds 中心即 tap 坐标（两者都是绝对屏幕坐标）。
12. **残留浮层会吃掉后续点击**：`DropdownMenu` / 对话框没关时，后续 tap 全部无效，
    表现为"按钮点了没反应"——`am force-stop` 重来最省事。

---

## 8. 已知限制与后续可做

### 8.1 已知限制

| 限制 | 说明 |
|---|---|
| 只支持 DLNA / QPlay | 不含 AirPlay（许可 + 体量，见 §2.2） |
| L05B / L05C 不支持 flac | 硬件限制，源建议 mp3 / aac |
| 缓冲模式起播略慢 | 开区间请求会等整条下载完成；大文件可关掉缓冲改直连 |
| 手机得活着 | 已做前台服务 + WakeLock + 电池白名单，部分国产 ROM 仍可能杀后台 |
| 投屏主链路未端到端实测 | 编译与 UI 已验证，链路需真机 + 音箱 + 音乐 App 联调 |
| 缓冲无并行下载 | 目前是单线程顺序下载；大文件可加分段并发提速 |

### 8.2 后续可做（按性价比排序）

1. **端到端联调**投屏链路（网易云 / QQ 音乐 → 音箱），这是当前唯一没验的关键路径。
2. 缓冲**分段并发下载**，缩短起播等待。
3. 带屏封面·歌词（本期按需求砍掉，技术上可行：复用 `MiNaClient` 的 ubus 通道检索曲库 audioID）。
4. 通知推送（飞书 / WxPusher）—— 本期按需求砍掉，若做需在设置页加推送渠道配置。
5. 深色模式（设计 tokens 里预留了扩展规则：背景 `#121212`、卡片描边 `#2E2E2E`、灰块 `#3A3A3A`）。

---

## 9. 变更记录

| 版本 | 变更 |
|---|---|
| **0.2.0** | 音频缓冲（文件落地 + 双模式 + 自动回退）；机型自动分派；登录限速；连续失败自愈；扫码登录；子服务热重启；日志脱敏；UI 整体重写为 Jetpack Compose（三屏骨架 + 六页面）；应用更名为 **XiaoaiCast Free** 并更换投屏 logo；修复二维码过小、首页卡片显示 deviceId 过繁 |
| 0.1.1（上游基线） | xiaoai-cast 的原始版本：SSDP + UPnP 服务 + DLNA 渲染器 + 小米云控制 + 前台服务保活 |

---

## 10. 免责与许可

- 本项目以 **MIT** 许可开源，仅供**个人学习与自用**。
- 控制音箱需向小米云提交**账号凭据**，请勿使用绑定了摄像头等敏感设备的账号；
  也不要把服务端口暴露到公网（媒体端点无鉴权）。
- 仓库内**不含任何 GPL 代码**；如需引入 miair-next 源码或 AirPlay 实现，请先将本项目整体改为 GPL-3.0。
- 商标与协议归属各自权利人，本项目与小米、网易云音乐、腾讯均无关联。
