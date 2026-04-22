# OTA 更新流程

## 快速发布（一条命令）

```bash
JAVA_HOME=/Users/student2028/Library/Java/JavaVirtualMachines/graalvm-jdk-17.0.12/Contents/Home ./scripts/ota/publish_ota.sh
```

手机退出聊天页重新进入即可收到更新弹窗。

> **⚠️ 最常见的坑：**如果手机上已安装的 versionCode 比 `.ota_build_counter` 里的值更高（例如之前某次会话递增到了很高的数字），那么 `scripts/ota/publish_ota.sh` 只 +1 递增后仍然 ≤ 手机版本，OTA 不会触发。
> 此时需要手动跳号：`echo "300000" > .ota_build_counter` 然后重新 `scripts/ota/publish_ota.sh`。

---

## 完整流程

### 1. 修改代码

正常修改代码并测试。

### 2. 更新 OTA 说明（可选）

编辑仓库根目录的 `ota_update_message.txt`，写入本次更新说明，手机端弹窗会展示这段文字。

### 3. 构建并发布

```bash
# 需要 Java 17+
JAVA_HOME=/Users/student2028/Library/Java/JavaVirtualMachines/graalvm-jdk-17.0.12/Contents/Home ./scripts/ota/publish_ota.sh
```

脚本自动完成：
1. 读取 `.ota_build_counter` 并 **+1** 递增
2. 将新值写回 `.ota_build_counter`
3. 用 `-PotaVersionCode=<新值>` 调用 `./gradlew assembleDebug`
4. Gradle `writeOtaPublishMeta` task 生成 `build/ota/version.json`
5. 输出新 APK 路径：`app/build/outputs/apk/debug/CdpRemote-debug.apk`

### 4. 中继自动感知

运行中的 relay-dev（端口 **19336**）会在每次收到 `/version` 请求时**实时读取** `build/ota/version.json`，无需重启中继。

验证：
```bash
curl -s http://127.0.0.1:19336/version | python3 -m json.tool
```

应返回类似：
```json
{
    "versionCode": 200001,
    "versionName": "ota-200001",
    "updateMessage": "修复模型获取..."
}
```

### 5. 手机触发更新

手机端 OTA 检查在以下时机自动触发（**不是实时推送**）：

| 触发时机 | 代码位置 |
|---------|---------|
| 进入聊天页连接 IDE 后 800ms | `ChatViewModel.doConnect()` |
| 主机列表页加载 / 刷新 | `HostListScreen` `LaunchedEffect(state.hosts)` |
| 主机列表页点击刷新按钮 | `HostListScreen` `scope.launch { runOtaCheck() }` |

**操作步骤：** 退出当前聊天页 → 重新进入任意 IDE 应用（或在主机列表页点刷新按钮）。

弹窗显示「发现新版本」→ 点击「更新」→ DownloadManager 下载 APK → 自动调用安装。

---

## 架构图

```
Mac 端                                    手机端
┌──────────────────────┐                  ┌──────────────────┐
│ scripts/ota/         │                  │  CdpRemote APP   │
│   publish_ota.sh     │                  │                  │
│  ↓ 递增 counter                         │ 进入聊天页/刷新   │
│  ↓ gradlew assembleDebug                │  ↓                │
│  ↓ 写 build/ota/version.json            │ GET /version      │
│  ↓ 生成 APK                             │  (固定端口19336)  │
└──────┬───────────────┘                  └────────┬─────────┘
       ▼                                           │
┌──────────────────┐    HTTP (LAN/Tailscale)       │
│ relay-dev:19336  │ ◄─────────────────────────────┘
│                  │
│ /version         │ → 读 build/ota/version.json → 返回 JSON
│ /download_apk    │ → 流式返回 APK 文件
└──────────────────┘
```

---

## 关键文件

| 文件 | 作用 |
|------|------|
| `scripts/ota/publish_ota.sh` | 一键构建脚本：递增版本号 → 打包 → 生成 version.json |
| `.ota_build_counter` | 版本号计数器（自动递增，勿手动改小） |
| `scripts/ota/ota_update_message.txt` | 更新说明文字，手机弹窗展示 |
| `build/ota/version.json` | Gradle 构建后自动生成，中继读取此文件 |
| `ota_meta.js` | 中继加载版本信息的 Node 模块 |
| `app/build.gradle.kts` | `otaVersionCode()` 版本号优先级逻辑 |
| `OtaUpdateManager.kt` | 手机端：检查版本 + 下载安装 APK |
| `CdpModels.kt` | `RELAY_OTA_HTTP_PORT = 19336` 常量定义 |

---

## 版本号优先级

`app/build.gradle.kts` 中 `otaVersionCode()` 按以下顺序取值：

1. **Gradle 参数** `-PotaVersionCode=N`（`scripts/ota/publish_ota.sh` 传入）
2. **计数器文件** `.ota_build_counter`（脚本写入）
3. **Git 提交数** `10000 + git rev-list --count HEAD`（兜底）

> ⚠️ 如果用 Android Studio 直接 Run 安装，版本号走第 2 或 3 项，可能比 OTA 低导致下次 OTA 正常触发；也可能比 OTA 高导致 OTA 无法触发。建议始终用 `scripts/ota/publish_ota.sh` 构建。

---

## 排查 OTA 不触发

### 1. 确认中继返回正确版本
```bash
curl -s http://<Mac-IP>:19336/version
```

### 2. 确认中继收到手机请求
```bash
tail -f ~/Library/Logs/CdpRelay/relay-dev.log | grep "📦"
```
应看到 `📦 /version 请求来自: <手机IP>`。

### 3. 确认手机本机版本 < 远端版本

在手机主机列表页查看 OTA 状态行，显示：
- `"已是最新：本机 versionCode=X，中继 Y"` → 手机版本 >= 中继版本
- `"发现新版本"` → 正常触发

### 4. 手机版本号过高？

强制跳号：
```bash
echo "300000" > .ota_build_counter
JAVA_HOME=... ./scripts/ota/publish_ota.sh
```

### 5. 检查网络

手机和 Mac 必须在同一局域网或 Tailscale 网络内。手机通过 `http://<Mac-IP>:19336` 访问中继。

---

## 注意事项

- `.ota_build_counter` **只增不减**，勿手动改小（会导致手机已装版本更高而不触发 OTA）
- `relay-dev` 端口 **19336** 是唯一提供 OTA 的中继，硬编码在 `CdpModels.kt` 的 `RELAY_OTA_HTTP_PORT`
- `relay` 端口 **19335** 不提供 OTA（返回 `"OTA disabled on this relay"`）
- 中继**不需要重启**，每次请求实时读取 `build/ota/version.json`
- 手机需**主动触发**检查（进入聊天页或刷新主机列表），不是后台推送
