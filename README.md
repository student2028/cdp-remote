# CDP Remote — 用手机远程操控 AI IDE

> 在 Android 手机上与电脑端的 AI 编程助手（Antigravity / Windsurf / Cursor / Codex）实时对话，随时随地写代码。

## ✨ 它是什么

CDP Remote 是一个轻量级的远程开发工具，由两部分组成：

| 组件 | 运行位置 | 说明 |
|------|---------|------|
| **Android App** (Kotlin) | 手机 | 聊天 UI，发送指令、查看回复、管理会话 |
| **Relay Server** (Node.js) | 电脑 | 桥接手机和 IDE，通过 Chrome DevTools Protocol 操控 IDE |

工作原理：

```
手机 App  ←— WebSocket —→  Relay Server  ←— CDP —→  IDE (Antigravity/Windsurf/Cursor/Codex)
```

## 📦 目录结构

```
cdp-remote/
├── app/                      # Android 源码 (Kotlin + Jetpack Compose)
│   ├── src/main/java/        #   主代码
│   └── src/test/java/        #   单元测试 (200+ 测试)
├── relay/                    # 生产环境 Relay 服务
│   ├── cdp_relay.js          #   中继服务 (Node.js)
│   └── package.json
├── relay-dev/                # 开发环境 Relay (带额外调试端口)
├── docs/                     # 文档
├── scripts/                  # 调试 & OTA 脚本
├── build.gradle.kts          # Gradle 构建配置
└── README.md
```

## 🚀 快速开始

### 前提条件

- **电脑**：macOS / Linux，已安装 [Node.js](https://nodejs.org/) (v18+)
- **手机**：Android 8.0+
- **IDE**：至少安装以下之一：
  - [Antigravity](https://www.antigravity.dev/)（Google 出品）
  - [Windsurf](https://www.windsurf.com/)
  - [Cursor](https://www.cursor.com/)
  - [Codex](https://openai.com/codex/)
- **JDK 17**（构建 APK 需要）
- **Android SDK**（API 34+）
- 手机与电脑在**同一局域网**下

### 第一步：构建 APK

```bash
# 设置 Android SDK 路径
echo "sdk.dir=/path/to/your/Android/sdk" > local.properties

# 构建 debug APK
./gradlew assembleDebug

# APK 输出在: app/build/outputs/apk/debug/CdpRemote-debug.apk
```

将生成的 APK 传到 Android 手机上安装。

### 第二步：启动 Relay（电脑端）

```bash
cd relay
npm install      # 首次运行，安装依赖（仅 ws 库）
node cdp_relay.js
```

启动成功后会看到：

```
CDP Relay 服务已启动
WebSocket: ws://0.0.0.0:19335
HTTP API: http://0.0.0.0:19335
```

Relay 会自动检测并启动你已安装的 IDE（Antigravity / Windsurf / Cursor），无需手动打开。

### 第三步：连接

1. 打开手机上的 **CdpRemote** App
2. 输入电脑的局域网 IP 和端口，例如：`192.168.1.100:19335`
3. 点击连接

连接成功后，你就可以在手机上和 AI 编程助手对话了！

### 运行测试

```bash
./gradlew test
```

## 📱 App 功能

- **实时聊天** — 发送消息、查看 AI 回复，支持 Markdown 渲染
- **图片发送** — 拍照或选择图片发送给 AI（支持多图）
- **模型切换** — 直接在手机上切换 AI 模型（Claude / GPT / Gemini / Kimi / SWE 等 46+ 模型）
- **会话管理** — 浏览和切换历史会话
- **屏幕预览** — 实时查看电脑 IDE 的屏幕内容（TV 模式）
- **IDE 窗口切换** — 同时打开多个 IDE 窗口时，一键切换
- **OTA 更新** — 支持通过 Relay 自动推送 App 更新

## 🔌 支持的 IDE

| IDE | 聊天 | 发图 | 模型切换 | 会话管理 |
|-----|------|------|---------|---------|
| Antigravity | ✅ | ✅ | ✅ | ✅ |
| Windsurf | ✅ | ✅ | ✅ (46+ 模型) | ✅ |
| Cursor | ✅ | ✅ | ✅ | ✅ |
| Codex | ✅ | — | — | ✅ |

## ⚙️ 配置说明

### 端口修改

如需修改默认端口 `19335`，编辑 `relay/cdp_relay.js` 顶部的配置：

```js
const PORT = 19335;  // 改成你想要的端口
```

### 多 IDE 支持

Relay 启动时会自动检测已安装的 IDE 并启动。检测顺序：

1. Antigravity
2. Windsurf
3. Cursor

你也可以先手动打开 IDE，Relay 会自动发现已运行的实例。

### 防火墙

确保电脑防火墙允许 19335 端口的入站连接（TCP）。

## 🔧 故障排查

| 问题 | 解决方案 |
|------|---------| 
| 手机连不上 | 检查 IP 和端口，确认同一 WiFi，检查防火墙 |
| 连上但看不到消息 | Relay 可能还没启动好 IDE，等几秒再试 |
| IDE 没有自动打开 | 手动打开 IDE，Relay 会自动检测 |
| 发送图片失败 | 确认 IDE 的 AI 面板已打开（侧边栏可见） |
| 模型切换失败 | 确保下拉菜单中有该模型，检查名称拼写 |

## 📋 技术细节

- **通信协议**：WebSocket + Chrome DevTools Protocol (CDP)
- **App 框架**：Kotlin + Jetpack Compose + Material 3
- **Relay**：Node.js，仅依赖 `ws` 库（零框架）
- **IDE 交互**：通过 CDP 注入 JavaScript 操控 IDE 的 Web UI
- **测试**：JUnit 5 + Robolectric，200+ 单元/集成测试
- **OTA**：App 自动检测 Relay 端新版本并下载更新

## 📄 License

MIT
