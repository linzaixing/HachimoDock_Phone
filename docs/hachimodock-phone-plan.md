# HachimoDock Phone：用 Z Flip5 外屏替代 ESP32-P4 设备

> 分支：`feature/HachimoDock_Phone`（基线 `beef363`）
> 起始日期：2026-11-14
> 参考项目：[HachimoDock](https://github.com/ai-maker-lab/HachimoDock)（非商业源码可见，**只借鉴协议与思路，不复用代码**）

## 1. 目标

把 HachimoDock 的「设备端」换成一台三星 Galaxy Z Flip5：

- **显示**：外屏（cover display，逻辑 display 1，748×720 @60Hz，340dpi）显示 Agent 状态宠物、状态气泡、会话信息
- **交互**：外屏触摸 + 手机麦克风（语音输入）+ 手机扬声器（提示音）
- **传输**：Wi-Fi（WebSocket）连 PC；USB-C 走 OTG 扩展坞，同时充电并接 ESP32-C3 插件
- **PC 端**：自研 Bridge（Node + TypeScript 风格，纯 JS 起步），采集 Codex / Claude Code 等 Agent 状态并下发

非目标（本阶段）：复刻 HachimoDock 的 Tauri 桌面端、PetUI 组件生态、固件 OTA。

## 2. 已定决策

| # | 决策 | 结论 |
|---|---|---|
| 1 | PC Bridge 技术栈 | Node + WS，先纯 ESM JS（零依赖），稳定后再考虑 TS 构建 |
| 2 | 代码归属 | 本仓库新分支 `feature/HachimoDock_Phone`，手机端为**独立 App 模块**，不复用 OpenPano 包名 |
| 3 | 首版显示 | 状态宠物 + 气泡文字；任务卡片后置 |
| 4 | 语音路线 | 手机录音 → 上传 PC → PC 侧 ASR → 回填目标输入框（确认后发送） |
| 5 | ESP32-C3 | 第一版不做；第二版做按键/摇杆/电量（BLE 上报 + USB 供电） |
| 6 | 通信协议 | JSON over WebSocket，字段固定后再评估 protobuf |
| 7 | OTG 供电 | 用支持 PD 直通的 OTG 扩展坞：边充边通，C3 从 Hub 取电 |
| 8 | 外屏常驻 | **最终方案：不需要 Shizuku**。前台 Service + 开机自启 + 合盖时 `ActivityOptions.setLaunchDisplayId(<外屏id>)`；后台拉起依赖「显示在其他应用上层」权限豁免 |

## 3. 架构

```
Agent (Codex / Claude Code / …)
        │  状态采集（hooks / 日志 / 进程）
        ▼
PC Bridge  (node bridge/src/server.mjs)
        │  WebSocket  JSON  (局域网，默认 :8765)
        ▼
手机 App  HachimoPhone（独立模块）
  ├─ 前台 Service：常驻连接、心跳、状态缓存
  ├─ 外屏 Activity（display 1）：宠物动画 + 气泡 + 触摸
  └─ 可选：AppWidget 常驻外屏小组件
        │  OTG（USB Host）
        ▼
ESP32-C3 插件（按键 / 摇杆 / 电量，可选）
```

## 4. 协议草案 v0

服务端 → 客户端：

| type | 说明 |
|---|---|
| `hello` | 握手回执，含协议版本、sessionToken |
| `state` | Agent 状态：`agent`、`session_id`、`state`（idle/thinking/tool/waiting/done/error）、`label`、`ts` |
| `sessions` | 会话列表（id / title / state），用于外屏摇杆或触摸切换 |
| `pong` | 心跳回执 |

客户端 → 服务端：

| type | 说明 |
|---|---|
| `hello` | 上报设备信息（型号、外屏分辨率）与协议版本 |
| `ping` | 心跳，5s 一次 |
| `confirm` | 用户在外屏确认当前等待项 |
| `voice` | 录音数据（base64 + 采样率），后续接入 |

## 5. 里程碑

| 阶段 | 内容 | 验收 |
|---|---|---|
| **M0（完成）** | 计划文档 + PC Bridge 骨架 + 协议 v0 + 调试页 | `node bridge/src/server.mjs` 起服务，浏览器打开调试页可注入/查看状态；测试客户端能收到 `state` |
| **M1（骨架完成，待装机验证）** | 手机端骨架：前台 Service + WS 客户端 + 内屏调试页 | 内屏能看到 Bridge 推来的状态实时变化 |
| **M2** | 外屏渲染：宠物状态 + 气泡（display 1） | 合盖后外屏可见并跟随状态 |
| **M3** | 常驻与自启：BOOT_COMPLETED、电池白名单引导、Shizuku 自动拉起 | 重启后服务自启；合盖自动上外屏 |
| **M4** | 真实 Agent 适配：Claude Code / Codex | 真实任务的状态变化能映射到外屏 |
| **M5** | 语音链路：录音 → PC ASR → 回填输入框 | 按住录音、松开确认后发送 |

## 6. 技术验证清单

| # | 验证项 | 判定标准 | 状态 |
|---|---|---|---|
| 1 | 前台 Service + BOOT_COMPLETED 自启 | 重启后服务自动运行 | ✅ 已验证：重启后 1s 内 `boot receiver` → `ws open` |
| 2 | MultiStar 托盘启动后合盖/展开 | 界面存活、WS 不断 | ✅ 已能自动上外屏（合盖触发），托盘路径仍可作备用入口 |
| 3 | Shizuku `am start --display 1` | 无需人工点击即上外屏 | ✅ 无需 Shizuku：App 自身可 `setLaunchDisplayId(1)` |
| 4 | AppWidget 被「外屏小组件」收录 | MultiStar 列表出现本应用组件 | ✅ 已验证：外屏可添加并绑定成功（cover host） |
| 5 | OTG Hub 边充边通 + C3 枚举 | 充电 ≥1A 且串口稳定 30 分钟 | 待硬件 |

## 7. 风险与对策

| 风险 | 说明 | 对策 |
|---|---|---|
| 外屏启动不可自动化 | 第三方拿不到副屏 Display，无 `setLaunchDisplayId(1)` 权限 | L1 手动点一次托盘；L2 Shizuku；L3 不建议 root |
| 三星后台清理 | 前台 Service 仍可能被回收 | 引导进「不受限制」白名单，Service 用 `connectedDevice`/`dataSync` 类型 |
| OTG 模式充电功率低 | 常见 5V/1.5A | 按 10W 估续航；Hub 选 PD 直通款 |
| USB 重新枚举 | Hub 插拔、C3 复位会重连 | 状态机 + 指数退避重连，不依赖固定端口 |
| OLED 烧屏 | 常亮静态画面 | 像素偏移、自动低亮度、定时息屏 |
| 许可证 | 上游非商业源码可见 | 不复用其代码，仅协议兼容 |

## 8. 快速开始（复现）

> 目标是「换一台电脑 + 另一台折叠屏手机」也能按下面步骤跑通，不依赖本机路径/IP。

### 8.1 环境要求

| 项 | 要求 |
|---|---|
| PC | Node ≥ 18（Bridge 本体只用到内置 `http` / `crypto` / `dgram` / `fs`，**无 npm 依赖**；`bridge/test/client.mjs` 用了全局 WebSocket，需要 Node ≥ 22）；启动脚本 / hooks 是 Windows 写法（`start-bridge.bat`、绝对路径） |
| 手机 | Android 8.0+（`minSdk 26`）；折叠屏/外屏机型最佳，普通手机只能单屏用 |
| 构建 | Android SDK 34 + JDK 17 + Gradle 8.7；`cd android && gradle :hachimo:assembleDebug`。**`android/` 没有自带 gradle wrapper**，用 Android Studio 打开工程或本机装 Gradle；仓库里的 `android/build_app.bat` 是旧 PanoCam 工程的（写死别的目录），**不要用** |
| 网络 | 手机与 PC 同一 Wi-Fi（路由器关闭 AP 隔离）；PC 防火墙放行 `node.exe` 的 TCP **8765** 入站与 UDP **8767** 广播，首次弹窗别点取消 |
| 配对 | PC 首次启动生成 `bridge/token.txt` 并打印令牌，手机端必须填同一个值；换机器会生成新令牌 |

### 8.2 三步启动

```bash
# 1) 一条命令起全部（Claude hooks 刷新 + Bridge + Codex 监听，单窗口、统一日志）
node bridge/start-all.mjs
# 控制台会打印「配对令牌」和调试页地址，例如：
#   [bridge] 配对令牌（手机端填这个）：<32 位十六进制>
#   [bridge] 调试页：http://127.0.0.1:8765/?token=<令牌>

# 等价于手动分开跑：
#   node bridge/hooks/install-claude-hooks.mjs   # 幂等，仓库路径变了会自动替换旧路径
#   node bridge/src/server.mjs                   # 8765 + UDP 8767 广播
#   node bridge/hooks/codex-watch.mjs            # 默认只跟新增事件，CODEX_REPLAY=1 才回放历史
```

> 令牌也写在 `bridge/token.txt`（不入库）。所有 HTTP/WS 接口都要带 `x-bridge-token` 或 `?token=`，否则返回 `401` / `unauthorized`；hooks、`report.mjs`、`test/client.mjs` 会自动读这个文件。

Windows 直接双击 `bridge/start-bridge.bat`（桌面快捷方式也是指向它）：单窗口、子进程崩溃 3s 自动重启、Ctrl+C 一起退出；8765 已占用时复用现有 Bridge，不重复启动。

也可以双击仓库根目录的 **`HachimoPhone.exe`**（C# 启动器，带图标，双击即开一个控制台窗口）：

- 自动定位仓库：`HACHIMO_HOME` 环境变量 → exe 所在目录（含上两级）→ 编译时写入的默认路径
- 内部就是执行 `node bridge/start-all.mjs`，日志留在窗口里；找不到 node 或脚本会提示并停住
- 重新编译：`powershell -ExecutionPolicy Bypass -File bridge/launcher/build-exe.ps1`（用系统自带 .NET Framework 的 csc，无需额外安装；源码 `bridge/launcher/HachimoPhoneLauncher.cs`）
- exe 与 ico 属于本机产物，已加入 `.gitignore`，不随仓库分发

> 重复双击是安全的：Bridge 端口被占则复用，Codex/Trae 监听用守卫端口探测到已在运行就跳过；此时启动器提示「已在运行」并在 5 秒后自动关窗，不会再出现子进程反复启动退出的循环。

```bash
# 2) 手机安装
adb install -r android/hachimo/build/outputs/apk/debug/hachimo-debug.apk
```

### 8.3 手机首次配置

1. 打开 `HachimoPhone` → **填「配对令牌」**（PC 上 `bridge/token.txt` 的内容，只需填一次）
2. 地址**留空**即自动发现，或手填 `ws://<PC-IP>:8765/ws`（手填必须带 `ws://`，否则提示格式错误、不会崩）
3. 授予「通知」权限（Android 13+ 会弹窗，前台服务通知需要）
4. 授予「显示在其他应用上层（悬浮窗）」——**合盖自动上外屏的关键**，没有它后台拉起会被系统静默拦截
5. 需要时改「外屏 display id」（Z Flip5 = 1；其它折叠屏可能不同）
6. 合盖，外屏应出现小狗状态页；`waiting` 时点屏幕会回传 `confirm`（只是回传信号，真正的权限批准仍要在 PC 上做）
7. 点过「断开」后 App 会记住，重开/重启手机都不再自动连，需要重新点「连接」

### 8.4 可配置项

| 变量 | 默认 | 说明 |
|---|---|---|
| `PORT` | 8765 | Bridge HTTP/WS 端口（watchers / hooks 默认跟随它） |
| `BEACON_PORT` | 8767 | UDP 发现广播端口（需与 App 内常量一致） |
| `MOCK` | 关 | 设 `1` 开内置假状态轮播，用于没有 Agent 时联调 |
| `BRIDGE_TOKEN` | 自动生成 | 覆盖 `bridge/token.txt` 里的配对令牌 |
| `CLAUDE_BIN` | 自动探测 | 覆盖手机下发指令用的 `claude.exe` 路径（非 npm 安装方式时需要） |
| `BRIDGE` | 跟随 `PORT` | 供 codex-watch / trae-watch 指向别的 Bridge |
| `CODEX_HOME` | `~/.codex` | Codex 会话日志根目录 |
| `WATCH_GUARD_PORT` | Bridge 端口 +1 | codex-watch 单实例守卫端口 |
| `BRIDGE_PORT` | 8765 | claude-hook 上报端口 |

### 8.5 复现判定

```bash
# 令牌在 bridge/token.txt；/health 现在也要鉴权
curl -H "x-bridge-token: <令牌>" http://<PC-IP>:8765/health
# 期望：ok:true 且 clients:1（手机在线）
```

- Claude Code 里说一句话 → 外屏 `WORKING · 思考中` → 调工具时 `执行中` → 结束 `DONE`（紧跟着的 `SessionEnd` 不会再把 `DONE` 冲掉）
- `waiting` 时点外屏 → Bridge 日志 `confirm <session>`，外屏显示「已收到确认信号」
- 外屏桌面可添加「HachimoPhone 状态」小组件并随状态刷新
- 多个 Agent 同时在线时，主位按 `waiting > error > tool > thinking > done > idle` 选，次行显示其它 Agent

### 8.6 已知环境差异

- **display id** 不是所有折叠屏都是 1，改不了就先用「打开展示页」+ 手动拖到外屏
- **三星 One UI** 的后台策略因版本而异，必要时把 App 设为「不受限制」
- 手机提示「Bridge 拒绝连接：unauthorized」= 配对令牌没填或填错（见 `bridge/token.txt`）
- 外屏一直「待机」但 Claude 明明在跑 = hooks 没装/指向旧路径，重跑 `install-claude-hooks.mjs`
- 外屏出现 `error: Claude Code doesn't recognize the model ...` = Claude 自身的模型配置问题，按提示修 `~/.claude/settings.json`
- 自定义逗狗音效：把 wav 放到 `Android/data/com.example.hachimophone/files/sfx_touch.wav` 即可覆盖内置，无需重装
- 宠物素材来自上游 HachimoDock（非商业许可），商用需替换（见 §8.7）

### 8.7 素材许可

`android/hachimo/src/main/assets/terrier/` 下的 mp4/wav 与 `res/drawable-nodpi/terrier_*.jpg` 来自 HachimoDock 的 `terrier-clips`，遵循其非商业源码可见许可，原件许可文本已放在 `assets/terrier/UPSTREAM-LICENSE.txt`。**发布/商用请替换成自有素材。**

### 8.8 语音输入与实时对话

对应上游 HachimoDock 的「按键语音 / 实时对话」，手机端去掉 ESP32，用系统能力闭环。

| 能力 | 操作 | 链路 |
|---|---|---|
| 语音输入 | 外屏**点一下麦克风**，说完自动结束 | 手机录音 → PC 识别（**千问 `qwen3-asr-flash`**，无 Key 时退回 Windows 本机引擎）→ 草稿（可改）→ 点「发送」→ Bridge `prompt` → `claude --resume -p` |
| 实时对话 | 外屏**长按麦克风**进入 / 退出（60 秒无语音自动退出） | 系统 ASR → WS `chat` → 模型 → **千问 `qwen3-tts-flash`**（无 Key 时用豆包，再没有就让手机系统 TTS 念）→ 扬声器 → 继续听 |

- 需要 `RECORD_AUDIO` 权限（首次点麦克风会申请），以及手机自带可用的语音识别服务；没有则外屏提示「没有可用的语音识别服务」。
- **识别的两条路**：默认先试手机自带识别；如果它报网络错误 / 缺中文离线包（国内连不上 Google 的典型表现），App 会自动永久切到 **PC 识别**——手机只录音（16k 单声道 PCM，静音 0.65 秒或最长 20 秒自动结束），录音发给 Bridge 后优先用 **千问 `qwen3-asr-flash`**，没配 Key 才退回 Windows 自带中文听写引擎。
  - PC 识别的兜底依赖：Windows 已安装「中文(简体)」语音识别引擎（设置 → 时间和语言 → 语音），并优先用 PowerShell 7（`pwsh`，Windows PowerShell 5.1 上 System.Speech 识别不出中文）
  - 识别脚本：`bridge/asr/recognize.ps1`；想手动验证：`pwsh -File bridge/asr/recognize.ps1 -Path 某个16k单声道.wav`
  - Windows 引擎准确率一般，只做离线兜底；千问 ASR 实测同一段话完全正确
- 实时对话期间会暂停 Agent 状态刷新和逗狗音效；播放回复时不收音，避免自问自答（手机没有硬件 AEC）。

配置 `bridge/config.json`（不入库，模板见 `bridge/config.example.json`）：

| 字段 | 说明 |
|---|---|
| `chat.provider` | `auto`（默认，有 Key 走 OpenAI 兼容接口，没有就走本机 claude）/ `openai` / `claude` |
| `chat.apiKey` / `chat.baseUrl` / `chat.model` | DeepSeek、豆包方舟、MiMo 等 OpenAI 兼容服务。**`apiKey` 可以留空**：Bridge 会依次读系统环境变量 `DEEPSEEK_API_KEY` / `ANTHROPIC_AUTH_TOKEN` / `OPENAI_API_KEY`（读不到进程环境时再查 Windows 用户环境变量），所以「终端里 claude 能登录」就意味着这里能用，不需要把密钥写进任何文件 |
| `chat.persona` | 宠物人设 system prompt |
| `tts.apiKey` | 豆包语音（TTS 2.0）API Key；不填则手机用系统 TTS |
| `tts.speaker` / `tts.resourceId` | 音色与资源 ID，默认 `zh_female_vv_uranus_bigtts` / `seed-tts-2.0` |

自测（需要令牌）：

```bash
curl -H "x-bridge-token: <令牌>" -H "content-type: application/json" \
     -d '{"text":"你好呀"}' http://127.0.0.1:8765/chat
curl -H "x-bridge-token: <令牌>" -H "content-type: application/json" \
     -d '{"text":"你好，我是哈基米"}' http://127.0.0.1:8765/tts
```

限制：播放回复时不收音（手机没有硬件 AEC），不支持播放中插话；豆包 TTS 需要账号已开通语音服务，失败会自动回落到手机系统发音。

时延（实测，本机，千问 ASR/TTS + DeepSeek）：

- 录音结束判定 0.65s；说完停顿 0.3s 时先发一份**预识别**（probe），结束时若没再说话就直接复用这次结果，相当于把识别耗时和停顿判定重叠掉
- 千问 ASR ≈ 0.35–0.5s（连接热了之后 0.35s）
- DeepSeek 流式首字 ≈ 0.5–0.9s（随服务端波动），首句一凑齐就开始合成
- 分句 TTS 流水线首音 ≈ 0.3–0.4s，之后边合成边用 AudioTrack 播
- 端到端：**说完 → 出声 ≈ 1.5–1.9s**；进入对话后 Bridge 收到录音就先预热对话接口，首轮不再多花 TLS 握手时间
- 文字会先推给手机显示（`chat_text`），不用等音频；对话保留最近 5 轮上下文（`chat_reset` 清空）

## 9. 进展日志

### 2026-11-14

- 读完 GitHub README 与 OSHWHub 项目页，梳理能力映射与差异
- 确定 8 条决策、架构与协议草案
- 创建分支 `feature/HachimoDock_Phone`（基线 `beef363`）
- 本文件建立
- **M0 完成**：`bridge/src/server.mjs`（零依赖 WS 服务）+ `/health`、`/inject`、调试页 + mock Agent（4s 轮播状态）
  - 自检通过：`node bridge/test/client.mjs` 收到 5 条 state，`hello` / `pong` / `confirm` 往返正常
  - PC 局域网地址 `192.168.1.6:8765`
- **M1 骨架**：新增 Android 模块 `android/hachimo`（`com.example.hachimophone`）
  - `BridgeService`：前台 Service（connectedDevice）+ OkHttp WS + 5s 心跳 + 指数退避重连
  - `BootReceiver`：BOOT_COMPLETED / MY_PACKAGE_REPLACED 拉起服务（开关默认开）
  - `AppState`：主线程回调的状态中心
  - `MainActivity`：内屏调试页（地址输入、连接/断开、实时状态）
  - `DeviceActivity`：外屏展示页（状态 emoji + 气泡 + 连接指示，点按可回传 `confirm`）
  - `:hachimo:assembleDebug` 离线构建通过，产物 277 KB
  - 待办：手机 adb 掉线，尚未安装验证（安装命令见下）

```
adb install -r android/hachimo/build/outputs/apk/debug/hachimo-debug.apk
```

### 2026-11-14 晚 · M1 装机联调（已通过）

链路打通：手机 `192.168.1.5` ⇄ PC `192.168.1.6:8765`，`/health` 显示 `clients: 1`，外屏页与调试页都能实时跟随 mock 状态。

**踩坑与修复（都已落到代码/系统配置）**：

| 现象 | 原因 | 处理 |
|---|---|---|
| 服务一起就崩 `IllegalArgumentException: foregroundServiceType` | `connectedDevice` 类型需要前提权限 | manifest 增加 `CHANGE_NETWORK_STATE` |
| `ForegroundServiceStartNotAllowedException` | 后台启动前台服务受限 | `startForegroundCompat()` 改为失败返回 false + `stopSelf()`，不 crash |
| `CLEARTEXT communication not permitted` | targetSdk 34 默认禁明文 | `android:usesCleartextTraffic="true"`（局域网 ws://） |
| 手机能 ping 通但 TCP 8765 连不上 | Windows 防火墙对 `node.exe` 有 **Block Public** 规则，且当前 Wi-Fi 被判定为「公用网络」 | 新增入站规则 `HachimoPhone Bridge 8765`（TCP 8765，Private+Public）；并禁用两条 node 的 Block-Public 规则 |
| `am start --display 1` 报 `not exported` | shell 无权启动非导出组件 | `DeviceActivity` 改为 `exported="true"`（页面只读状态） |
| manifest 解析失败 | XML 注释里出现 `--`（`am start --display 1`） | 注释去掉双连字符 |

**已验证**：
- `am start --display 1 -n com.example.hachimophone/.DeviceActivity` 成功，`dumpsys window` 显示该窗口 `mDisplayId=1`
- 此时外屏 `state OFF`（手机展开态），界面已就位但面板不亮；需要实际合盖才能目视确认

**外屏环境实测值**：748×720，density 320（dpr=2），60Hz，`FLAG_PRESENTATION` + `FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS`。

### 2026-11-14 晚 · M2 外屏状态页（已通过）

- 重写 `DeviceActivity`：顶部状态 chip（会话 + 中文状态 + 状态色）、宠物区、气泡（最多 3 行）、底部连接地址
- 分状态动效：思考慢呼吸 / 执行快呼吸 / 完成弹一次 / 出错抖一次 / 待机轻微摇摆
- 防烧屏：每 45s 整块内容随机漂移 ±3dp；待机超过 3 分钟自动降到 55% 透明度
- 全屏沉浸：隐藏系统状态栏与导航栏（外屏只有 748×720，导航栏占掉一条）
- 待确认时点屏幕即回传 `confirm`

**验证**：合盖后外屏显示 `S1 · 执行中` / 🔧 / `执行：写入 bridge/src/server.mjs` / `● ws://192.168.1.6:8765/ws`；
外屏点一下 → Bridge 立刻推 `thinking / 已确认，继续执行`，确认链路闭环。

**遇到的问题**：`hideSystemBars()` 在 `onCreate` 里调 `getInsetsController()` 会 NPE（decor 未挂载），改为 `setContentView` 后 `post` + try/catch；`onWindowFocusChanged` 需 `public` 才能覆写。

### 2026-11-14 晚 · M3 合盖自动上外屏（已通过）

**结论：不需要 Shizuku。** 两条实测事实：

1. **App 自己可以 `setLaunchDisplayId(1)`** —— 用 `CoverProbeActivity` 探针验证：`probe start ok`，窗口 `mDisplayId=1`，无 SecurityException。之前"第三方拿不到副屏"的结论只适用于「枚举 Display」，不等于「不能指定 id 启动」。
2. **但后台启动 Activity 会被静默拦截** —— 服务里调 `startActivity` 只留下 `cover launch requested`，窗口没建起来，也不抛异常。
3. **持有 `SYSTEM_ALERT_WINDOW` 是后台启动的豁免情形** —— 授予悬浮窗权限后，合盖时服务直接拉起外屏页成功（`mDisplayId=1`，外屏显示 `S1 · 待机 / 😴 / 待机中`）。

**实现**：
- `BridgeService` 注册 `TYPE_HINGE_ANGLE`（type 36）：角度 ≤60° 判定合盖 → `launchCover()`；≥120° 复位；5s 去抖
- `launchCover()`：`ActivityOptions.setLaunchDisplayId(1)` + `FLAG_ACTIVITY_NEW_TASK|SINGLE_TOP`
- manifest 声明 `SYSTEM_ALERT_WINDOW`，调试页加「悬浮窗权限」入口（引导用户手动授权）
- 同时修掉重复连接：`connectGeneration` 计数，旧连接回调不再触发重连

**待办**：真机首次使用需手动授予「显示在其他应用上层」；若要避免这个权限，可退回「预置方案」——App 在前台时先于 display 1 预置页面，合盖后直接显形。

### 2026-11-14 深夜 · 真实 Agent 接入（Claude / Codex）+ 外屏小组件

**Claude Code（用户级 hooks，全局生效）**
- `bridge/hooks/claude-hook.mjs`：从 stdin 读 hook JSON，补 `agent=claude` 后 POST `/hook`；任何异常 `exit 0`，绝不阻塞 Claude
- `bridge/hooks/install-claude-hooks.mjs`：幂等写入 `~/.claude/settings.json`（先备份 `.bak`），已注册 7 个事件：
  `UserPromptSubmit` / `PreToolUse` / `PostToolUse` / `Notification` / `Stop` / `SubagentStop` / `SessionStart`
- `bridge/src/server.mjs` 新增 `POST /hook`（按事件映射状态）与 `POST /agent`（直接收 `{agent,state,label}`）
- 实测：`PreToolUse` + `{"tool_name":"Read","tool_input":{"file_path":"src/main.js"}}` → `/health` 返回 `agent:claude / state:tool / label:执行：Read src/main.js`

**Codex（无 hook，改读 rollout 日志）**
- Codex 只有 `notify`（每轮结束一次，且已被 computer-use 占用），拿不到过程态；`~/.codex/sessions/**/rollout-*.jsonl` 里是实时的
- `bridge/hooks/codex-watch.mjs`：轮询最新 rollout 文件，增量解析新增行并映射：
  `task_started`→thinking、`user_message`→thinking（带指令摘要）、`agent_reasoning`→thinking、
  `exec_command_begin`→tool（带命令）、`exec_command_end`→thinking / `error`（非 0 退出）、
  `function_call`→tool（工具名+参数）、`task_complete`→done、`turn_aborted`→error
- 实测：读到历史 rollout 立刻推送 `codex / done / 任务完成`，`/health` 显示 `clients: 1`（手机在线）

**TRAE**
- Trae 无公开 hook / 事件回调（既没有 Claude 那种 settings hooks，也没有 Codex 的 notify），只能旁路：
  1. `POST /agent` 由脚本/规则主动上报（现有能力，零改动可用）；
  2. 在项目规则里要求 Agent 每步上报（靠模型自觉，不保证）；
  3. 读 Trae 自身会话日志（内部格式，升级即失效，不建议）
- 结论：可「配合使用」，不能「自动接入」。

**外屏 AppWidget（验证清单 #4）**
- 新增 `CoverWidgetProvider` + `res/layout/widget_cover.xml` + `res/xml/cover_widget_info.xml` + `drawable/widget_bg.xml`
- 尺寸 150×110dp（外屏按 2×2 格），展示 `agent · 会话 · 状态` + 宠物 emoji + 一行标签；`updatePeriodMillis=0`，由 `BridgeService` 在收到 state / 连上 / 断开时 `refresh()` 主动刷新
- 点击小组件 → 打开外屏展示页
- 实测：重装后 `dumpsys appwidget` 已列出 provider（`cmp=...CoverWidgetProvider`），且外屏宿主（SystemUI，uid 10048）已多次 `getInstalledProvidersForProfiles` 枚举到本应用
- **用户已在外屏添加上组件**：`dumpsys appwidget` 显示实例绑定在 `com.sec.android.app.launcher` 的 cover host 上（`views=RemoteViews@...`），说明第三方 AppWidget 可以被外屏收录
- 组件内容已换成小狗缩略图（`widget_cover.xml` 用 ImageView，按状态切换 `terrier_*.jpg`）

**外屏黑屏问题（复用旧结论）**
- 原因：装更新 → 进程被杀 → 外屏回落到 SubHomeActivity（表现为黑）
- 修复：`KEY_FOLDED` 持久化折叠态 + 服务启动 1.5s 后补一次 `launchCover()`；`DeviceActivity` 加 `setShowWhenLocked` / `setTurnScreenOn`
- 现状：`dumpsys` 确认外屏窗口是 `mHoldScreenWindow`（`FLAG_KEEP_SCREEN_ON` 生效），状态栏/导航栏均 `WINDOW_STATE_HIDDEN` → **常亮且沉浸**

### 2026-11-14 深夜 · 外屏 UI 对齐 HachimoDock 设备端

**素材复用（约 5.8 MB）**
- 从上游 `pc/public/terrier-clips/` 复制 13 个 mp4（854×480 / 24fps，无音轨）+ 3 个提示音 wav 到 `android/hachimo/src/main/assets/terrier/`
- 复制 6 张缩略图到 `res/drawable-nodpi/terrier_*.jpg`，给外屏小组件用
- **许可**：上游为「非商业源码可见」许可，允许非商业复制/修改，但再分发需附许可证；已在素材目录放 `UPSTREAM-LICENSE.txt`。若以后要商用或发布，需替换成自有素材

**版式（照设备端截图 1:1 山寨）**
- 纯黑底 + 全屏宠物视频；左上角 `WORKING · 执行中` 小号大写状态；左下角 `CLAUDE · A1B2C3D4` + 一行大字状态文案；`waiting` 时多一行青色「点一下屏幕确认」
- 上下加渐隐遮罩（白毛犬背景亮，白字压上去需要压暗）
- 视频按屏高铺满、左右裁切（素材 16:9，外屏 748×720）

**行为（对齐上游 `pet_p4_behavior.c`）**
- `idle` 轮播 7 个 idle 变体；`thinking` 轮播 working.thinking/typing/browsing；`tool` 轮播 typing/browsing
- `waiting_user` 定住循环；`done` / `error` 播一遍后回 idle；进入这三个状态播对应 wav 提示音
- 启动播 `welcome` 一遍
- 点屏幕：`waiting` 时回传 confirm + 播 done + 文字改「已确认，继续执行」；其它状态播 touch.lick / touch.what 逗一下再回当前状态

**踩坑**
| 现象 | 原因 | 处理 |
|---|---|---|
| `MediaPlayer what=1 extra=0`，黑屏无画面 | `VideoView.setVideoURI("file:///android_asset/...")` 在 Android 14 上打不开 | 改成 `SurfaceView + MediaPlayer`，用 `getAssets().openFd()` 的 `AssetFileDescriptor` 作数据源 |
| 装更新后外屏显示「PC Bridge 未连接」且服务没起 | 手动 force-stop 后 App 处于 stopped 状态，收不到 `MY_PACKAGE_REPLACED` | `MainActivity.onCreate` 末尾补 `BridgeService.start()`，打开 App 即拉起服务 |

**验证（外屏截图，物理 display id `4630947181303254916` 才能截）**
- `screencap -p -d 4630947181303254916`：`IDLE · 待机` / 小狗吃粮片段 / `CODEX · 0F3A9C21` / `待机中`
- 推 `waiting`：`WAITING · 等你确认` / 小狗坐姿 / `需要你确认：是否安装依赖` / 青色确认提示
- `input -d 1 tap` 点外屏 → bridge 收到 confirm，状态回到 `thinking / 已确认，继续执行`
- `dumpsys SurfaceFlinger --latency` 确认视频层持续出帧（≈60fps）

**已知取舍**
- 视频解码 + 常亮功耗高于静态页；外屏整页显示时约多耗 3%–5%/小时
- 小组件是静态 RemoteViews，不能播视频，只能换缩略图

### 2026-11-14 深夜 · Claude 安装与 Trae 旁路接入

**「Claude 打不开」的原因：本机根本没装**
- 排查结果：`claude` 不在 PATH；`~/.local/bin` 只有 uv；npm 全局无 `@anthropic-ai/claude-code`；无桌面版安装目录、无开始菜单快捷方式、注册表无卸载项；`~/.claude` 里只有我们写的 `settings.json`，没有 `~/.claude.json`（说明从未登录过）
- 处理：`npm install -g @anthropic-ai/claude-code` → 已装 `2.1.325`，`claude --version` 通过；用户 PATH 已包含 `%APPDATA%\npm`
- 首次使用需 `claude` 里执行 `/login`（或设 `ANTHROPIC_API_KEY`），之后 hooks 自动生效

**Trae 接入：项目规则上报（半自动）**
- 新增 `.trae/rules/hachimo-bridge.md`：约定在 thinking / tool / waiting / done / error 五个时机 POST `/agent`，`agent=trae`、`session_id=general_app`，失败静默不阻塞
- 实测：按规则推送后外屏显示 `WORKING · 执行中` / `TRAE · GENERAL_APP` / `执行：npm install claude-code`
- 局限：Trae 没有系统级 hook，规则靠模型遵守，做不到 Claude/Codex 那种 100% 自动

### 2026-11-14 深夜 · 多 agent 并行展示

- Bridge 改为**按 agent 存最近状态**（`agentStates` Map），不再互相覆盖；`/health` 多返回 `states` 数组；新客户端连上时补发每个 agent 的最后状态
- 外屏顶部 chip 改为 `AGENT · 状态`（如 `CLAUDE · 执行中`），会话号下沉到底部 `SESSION XXX`
- 多 agent 同时运行时，底部多一行 `同时：TRAE 待机 · CODEX 等你确认`（最多 2 个，且只显示 10 分钟内有更新的，避免历史残留占版面）
- 外屏小组件 chip 同样带 agent 名，多 agent 时前缀 `+N`
- 实测：claude/codex/trae 三个 agent 状态并存，外屏显示主状态 + 同时列表，`/health` 里 `states` 三条、`sessions` 四条
- 排查记录：清空 App 数据（`shared_prefs`）会丢折叠状态，外屏会回到表盘；重新合盖一次即可恢复，正常安装更新不受影响

### 2026-11-14 深夜 · 状态过期与一致性

- 问题：手动/历史状态会永久挂在外屏（Claude 显示「执行中」、Codex 显示「等你确认」，实际都没在跑）
- 对策：
  - 状态 **10 分钟 TTL**（`STATE_TTL_MS` 可调），过期自动删除，主状态回退到剩余最新一条
  - `POST /reset` 一键清空全部状态与会话
  - 新增 `states` 全量快照包：清理/重置后下发，手机端整体替换本地多 agent 缓存（否则手机内存里仍留着旧条目）
  - Claude hooks 增加 `SessionEnd → idle`，会话退出后显示「待机」而不是卡在 done/waiting
- 验证：reset 后外屏只剩 `TRAE · 执行中`，「同时」一行消失

### 2026-11-14 深夜 · Trae 自动兜底

- 规则上报「靠模型自觉」不可靠（对话一多就漏），补了一个真实信号源：`bridge/hooks/trae-watch.mjs`
- 原理：Trae 每次对话/工具调用都会写 `%APPDATA%\Trae CN\ModularData\ai-agent\database.db-wal`，监听其 mtime 即可判断「在交互 / 已空闲」（SQLite 文件本身被 Trae 独占锁定，读不了内部数据）
- 行为：检测到写入 → 推 `trae / thinking / Trae 交互中`；25s 无写入 → 推 `trae / idle / 待机`；若 45s 内已有规则上报的详细状态则跳过，避免粗粒度文案盖掉细节
- 已并入 `start-all.mjs`，和 Bridge/Codex 监听一起起
- 实测：外屏自动从「TRAE · 执行中」变为「TRAE · 待机」，全程无人工推送

### 2026-11-14 深夜 · 外屏倒立跟随

- 需求：手机倒过来拿时，外屏内容（含小狗视频）也要跟着转 180°
- 视频层从 `SurfaceView` 换成 `TextureView`（SurfaceView 不支持整体旋转，TextureView 支持，代价是一次纹理拷贝）
- 检测：加速度计低通滤波（0.85 权重），取重力在屏幕平面的投影角；**以页面启动时的握姿为基准**，相对基准偏离 >135° 判定倒立（不同折叠屏外屏朝向不同，用绝对角会误判）
- 去抖 800ms，旋转动画 220ms；平放（平面分量 <3）不判断，避免误翻
- 调试开关：`adb shell am start --display 1 -n com.example.hachimophone/.DeviceActivity --ez force_flip true` 强制倒立渲染（此时关闭传感器），已截图验证文字与视频整体翻转
- 待实机确认：合盖状态下物理翻转手机时的触发方向

### 2026-11-14 深夜 · 修正 Claude 状态误报

- 现象：Claude 实际空闲，外屏显示「思考中」；退出后显示「待机」
- 根因（从 supervisor 日志定位）：`21:22:16 state claude/thinking 子任务结束` —— `SubagentStop` 被映射成 thinking，子任务在主任务结束后才报，把状态永久改回思考中；同理 `PostToolUse` 也无条件改 thinking，会覆盖 done/waiting
- 修复：
  - `SubagentStop` 不再改状态（交给 `Stop` / `SessionEnd`）
  - `PostToolUse` 只在原本就是 thinking/tool 时才生效
  - 新增活跃状态超时降级：thinking/tool 超过 3 分钟无事件自动转「待机」（`ACTIVE_STALE_MS`）
  - 新增 `GET /events`（最近 50 条状态事件），便于排查「为什么显示这个」
  - 顺带修手机端重连未关闭旧 socket，导致 `clients` 一度累积到 3
- 验证：隔离端口按 `UserPromptSubmit → PreToolUse → PostToolUse → Stop → SubagentStop → PostToolUse` 顺序发事件，最终稳定在 `done`；超时降级在 4s 测试参数下生效

### 2026-11-15 · 手机下发指令 + 分动作音效

**手机 → Claude（已打通）**
- Bridge 新增 `POST /prompt` 与 WebSocket `{"type":"prompt","text":"..."}`：把文本通过 stdin 交给 `claude --resume <会话> -p`，结果由 hooks 回流外屏
- 会话号取 `agentStates.claude.session_id`（没有则开新会话）；同一时间只允许一条，忙时返回 `claude busy`
- 失败兜底：捕获 stderr，命中登录问题时推 `error / Claude 未登录（在终端跑一次 claude /login）`
- 手机端：内屏调试页新增输入框 + 「发送给 Claude」按钮（`BridgeService.sendPrompt`）
- 实测：`POST /prompt {"text":"只回复 ok"}` → 外屏依次出现 `收到手机指令 → 会话开始 → 收到指令 → 任务完成 → 会话结束`
- 限制：Trae 没有 CLI/接口，无法注入；Claude 为「后台一次一轮」，不是给正在交互的会话打字

**音效（每个动作一条）**
- 用 ffmpeg 生成 `sfx_thinking / sfx_tool / sfx_touch / sfx_welcome`（正弦音 + 淡入淡出，22kHz 单声道，几 KB）
- `done / error / waiting_user` 沿用上游 wav
- 映射：thinking→sfx_thinking，tool→sfx_tool，waiting→waiting_user，**done→sfx_done（真实狗吠，1.9s）**，error→error，点屏幕→sfx_touch，启动→sfx_welcome
- 狗吠素材来自 Wikimedia Commons「Barking of a dog.ogg」（作者 Amada44，CC BY-SA 3.0），做去静音 + loudnorm + 22.05kHz 单声道转换；来源与许可写在 `assets/terrier/SFX-CREDIT.txt`
- 顺带修连接静默失效：手机端 5s 一次 ping，若 20s 收不到 pong 就主动重连（之前 socket 断掉后可能一直不重连，表现为外屏状态不再更新）
- 触摸音改为用户自录的「啾啾啾」（来源：微信录音 m4a，截取 0.2–3.9s，+9dB 增益，22.05kHz 单声道，3.7s）
- 坑：首版用 `loudnorm` 处理这条「长静音 + 低峰值」的素材，输出几乎静音（mean -91dB / max -65dB，根本听不见）；改为简单 atrim + volume 后峰值到 -1.2dB 才正常
- 音效支持外部覆盖：`/sdcard/Android/data/com.example.hachimophone/files/<name>.wav` 优先于 assets，放进去即可替换（`sfx_touch / sfx_thinking / sfx_tool / sfx_done / sfx_welcome`），无需重装
- 开关：外屏长按切换（提示「音效已开/关」），内屏调试页有「音效：开/关」按钮，配置存 SharedPreferences

### 2026-11-14 深夜 · DeepSeek 接入与 PC 端启动入口

- `~/.claude/settings.json` 增加 `env`：`ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic`，`ANTHROPIC_MODEL` 与三个 DEFAULT/小模型别名均为 `deepseek-flash`（已用 `/v1/messages` 实测：返回 `model=deepseek-flash`，带 thinking 块）
- API Key 不入库、不写文件：由用户在终端用 `[Environment]::SetEnvironmentVariable('ANTHROPIC_AUTH_TOKEN', ..., 'User')` 自行设置
- 新增 PC 端一键启动：`bridge/start-bridge.bat`（`MOCK=0` 起 Bridge + 起 Codex 监听），快捷方式 `bridge/HachimoPhone Bridge.lnk`；本机 Desktop 目录对进程不可写（ACL 拒绝），需用户手动拖到桌面




