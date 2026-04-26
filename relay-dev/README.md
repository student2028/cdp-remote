# CDP Relay Server

将本地 Electron IDE（Cursor / Windsurf / Codex）通过 WebSocket 中继转发到安卓手机，实现远程查看和操控。

## 🚀 快速安装

### 方式一：npx 直接运行（无需安装）

```bash
npx cdp-relay-server
```

### 方式二：全局安装

```bash
npm install -g cdp-relay-server

# 然后随时启动
cdp-relay
```

### 方式三：从源码运行

```bash
git clone https://github.com/student2028/cdp-remote.git
cd cdp-remote/relay-dev
npm install
node cdp_relay.js
```

## 📱 配合安卓客户端

1. 在电脑上启动 Relay 服务
2. 在安卓手机上安装 CdpRemote APP
3. 手机和电脑在同一局域网下，输入电脑 IP 地址连接即可

## 📋 系统要求

- Node.js >= 16
- 电脑上已安装并启动 Cursor / Windsurf / Codex 等支持 CDP 协议的 IDE
