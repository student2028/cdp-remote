<div align="center">
  <h1>✨ CDP Remote</h1>
  <p><strong>将本地 Electron IDE 装进口袋，随时随地开启硬核编码。</strong></p>

  [![npm](https://img.shields.io/badge/npm-install-blue.svg)](https://github.com/student2028/cdp-remote)
  [![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
  [![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://github.com/student2028/cdp-remote/releases)
</div>

---

**CDP Remote** 是一个专为极客开发者打造的远程操控解决方案。它通过 Chrome DevTools Protocol (CDP)，将你电脑上运行的 **Cursor**, **Windsurf**, 或 **Codex** 的画面实时无损地串流到安卓手机上，并提供深度定制的触控与键盘输入层。

去哪儿都能改 Bug，不在电脑旁也能写代码！

## 🌟 核心特性

- **🕹 沉浸式触控**：支持直接触控（点哪打哪）和虚拟光标模式（鼠标悬停与右键）。
- **⌨️ 智能键盘避让**：原生键盘弹出时，IDE 画面自动进行 `Y轴` 智能平移，光标永远不被遮挡，字体不缩水！
- **🪟 零死角工具栏**：悬浮在画面外部的高密度紧凑工具栏，不遮挡任何一行代码。
- **🚀 丝滑串流**：基于 WebSocket 的图像差异传输，支持动态调整画质与帧率，在弱网环境下也能流畅使用。
- **🔄 无缝多实例**：自动扫描电脑上所有开启的 CDP 端口（比如同时开着三个 Cursor 窗口），一键切换。

---

## 🚀 快速开始 (安装指南)

只需两步，立刻开启远程办公模式：

### 1️⃣ 服务端安装 (你的电脑)

不需要注册任何账号，直接通过 GitHub 全局安装中继服务（需要 [Node.js](https://nodejs.org/) >= 16）：

```bash
npm install -g github:student2028/cdp-remote
```

```bash
cdp-relay
```
> 注意：直接执行 `cdp-relay` 会在前台运行，如果你关闭了终端，服务就会停止。服务启动后，会自动扫描你正在运行的 IDE 实例。

**🔥 进阶建议：后台静默运行（防断连、开机自启）**
为了极致体验，强烈推荐使用 PM2 来守护进程，这样就算电脑重启或终端关闭，你的手机也随时能连上电脑：

```bash
npm install -g pm2
pm2 start cdp-relay --name "cdp-server"
pm2 save && pm2 startup
```

### 2️⃣ 客户端安装 (你的安卓手机)

#### 方案 A：命令行极客安装 (推荐)
如果你电脑连着手机（且开启了 USB 调试），直接在终端执行：

```bash
curl -L -o /tmp/cdp-remote.apk https://github.com/student2028/cdp-remote/releases/download/v1.0.0/CdpRemote-debug.apk && adb install -r /tmp/cdp-remote.apk
```

#### 方案 B：直接下载
用手机浏览器访问 [GitHub Releases 页面](https://github.com/student2028/cdp-remote/releases/tag/v1.0.0)，点击下载 **`CdpRemote-debug.apk`** 并安装。

---

## 🎮 使用说明

1. 确保手机和电脑在**同一个局域网**内（或使用 Tailscale 等内网穿透工具）。
2. 在手机端 APP 的首页，输入电脑上 `cdp-relay` 显示的 IP 地址和端口（例如 `192.168.1.100:19336`）。
3. 选择你想操控的 IDE 窗口，进入沉浸式开发界面：
   - **LIVE**：实时查看模式。点击它可切换到 **CTRL** (操控模式)。
   - **左/全/右**：一键调整焦点区域，单手操作更舒适。
   - **👆 / 🖱**：切换直接触控或虚拟鼠标。
   - **⌨**：呼出输入法，支持中文拼音与删除键无缝对接。

---

## 🛠 架构原理

本项目由两部分组成：
1. **Relay Server (`cdp_relay.js`)**: 作为一个中间件，与本地的 Electron App 建立 CDP WebSocket 连接，截取屏幕并通过 HTTP/WS 暴露给移动端。
2. **Android 客户端**: 基于 Jetpack Compose 构建的高性能渲染层，实时展示图片流，并将手机上的触控手势和按键事件（虚拟按键隐射）翻译成 CDP 命令回传给电脑。

## 📄 License

[MIT](LICENSE) © student2028
