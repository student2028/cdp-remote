package com.cdp.remote.data.cdp

import com.google.gson.JsonObject
import com.google.gson.JsonParser

// ─── Enums ──────────────────────────────────────────────────────────

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

enum class MessageRole { USER, ASSISTANT, SYSTEM }

object AutoApprovePolicy {
    fun shouldAttemptCdpAutoAccept(connectionState: ConnectionState): Boolean {
        return connectionState == ConnectionState.CONNECTED
    }
}

enum class ElectronAppType(val displayName: String, val emoji: String) {
    ANTIGRAVITY("Antigravity", "🚀"),
    CURSOR("Cursor", "🖱️"),
    WINDSURF("Windsurf", "🏄"),
    CODEX("Codex", "📦"),
    VSCODE_LIKE("VS Code", "💻"),
    UNKNOWN("Unknown", "❓");

    companion object {
        /**
         * 把权威来源给的 IDE 名字（中继 `/targets` 的 `appName` 字段、或用户在
         * `LaunchIdeDialog` 选中的项）映射到枚举。
         *
         * **这是项目里唯一允许「字符串 → IDE 类型」转换的地方**。其它地方一律
         * 直接拿 [ElectronAppType] 用 `when` 分发，禁止再写 `name.contains("cursor")`
         * 这种启发式 —— 那种写法在 2026-04 的 9444 事故里已经栽过一次
         * （把 Windsurf 里打开的 `CursorPresetModelsTest.kt` 误识别成 Cursor）。
         *
         * 不区分大小写，首尾空白会被去除；不认识的名字（包括 null/空串）一律落到 [UNKNOWN]。
         */
        fun fromAppName(name: String?): ElectronAppType {
            val n = name?.trim().orEmpty()
            if (n.isEmpty()) return UNKNOWN
            // 优先精确匹配
            val exact = values().firstOrNull { it.displayName.equals(n, ignoreCase = true) }
            if (exact != null) return exact
            // 降级：包含匹配（appName 可能带端口后缀如 "Windsurf (9443)"）
            val nl = n.lowercase()
            return when {
                nl.contains("codex") -> CODEX
                nl.contains("cursor") -> CURSOR
                nl.contains("windsurf") -> WINDSURF
                nl.contains("antigravity") -> ANTIGRAVITY
                else -> UNKNOWN
            }
        }
    }
}

// ─── Result Sealed Class ────────────────────────────────────────────

sealed class CdpResult<out T> {
    data class Success<T>(val data: T) : CdpResult<T>()
    data class Error(val message: String) : CdpResult<Nothing>()

    val isSuccess: Boolean get() = this is Success

    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Error -> throw RuntimeException(message)
    }

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }
}

data class CdpError(
    val code: Int,
    val message: String
)

data class CdpResponse(
    val id: Int,
    val result: JsonObject? = null,
    val error: CdpError? = null
) {
    val isSuccess: Boolean get() = error == null
}

// ─── Data Classes ───────────────────────────────────────────────────

data class CdpPage(
    val id: String,
    val type: String,
    val title: String,
    val url: String,
    val webSocketDebuggerUrl: String,
    val devtoolsFrontendUrl: String = "",
    /**
     * IDE 身份。**由数据入口（解析中继 `/targets`、或用户在 `LaunchIdeDialog` 选中之处）
     * 一次性钉死**，CdpPage 自身不做任何推断 —— 没有 getter 计算、没有 title.contains、
     * 没有 URL 关键字嗅探。
     *
     * 入口转换走唯一通道 [ElectronAppType.fromAppName]。上游没告诉我们就老老实实是
     * [ElectronAppType.UNKNOWN]，绝不"猜"。
     *
     * 历史上这里是个 computed property，先看 title.contains 再看 URL 里的 .app
     * bundle —— 结果是用户在 Windsurf 里打开 `CursorPresetModelsTest.kt` 就被误判成
     * Cursor（2026-04 的 9444 事故）。教训：身份是上游告诉我们的事实，不是从 page 的
     * url/title 里反推的猜测。
     */
    val appType: ElectronAppType = ElectronAppType.UNKNOWN
) {
    val isWorkbench: Boolean
        get() = type == "page" && "jetski" !in url && (
            "workbench.html" in url || url.startsWith("app://")  // Codex 用 app://-/index.html
        )

    val cdpPort: Int?
        get() {
            val relayMatch = Regex("/cdp/(\\d+)/").find(webSocketDebuggerUrl)
            if (relayMatch != null) return relayMatch.groupValues[1].toIntOrNull()
            val directMatch = Regex("://[^:/]+:(\\d+)/").find(webSocketDebuggerUrl)
            return directMatch?.groupValues?.get(1)?.toIntOrNull()
        }
}

/** 中继上 **仅** 提供 GET /version、/download_apk（OTA）的 HTTP 端口，与 CDP 连接端口可不同 */
const val RELAY_OTA_HTTP_PORT = 19336

data class HostInfo(
    val ip: String,
    val port: Int,
    val name: String = "",
    val lastConnected: Long = 0L
) {
    val address: String get() = "$ip:$port"
    /** CDP / 扫描用，与用户填的 port 一致 */
    val httpUrl: String get() = "http://$ip:$port"
    /** 仅此地址用于 OTA，**固定 :$RELAY_OTA_HTTP_PORT** */
    val otaHttpBaseUrl: String get() = "http://$ip:$RELAY_OTA_HTTP_PORT"
    val displayName: String get() = name.ifEmpty { address }
}

data class ChatMessage(
    val id: String = "",
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val images: List<String> = emptyList()
)

// ─── Folder Browser Models ──────────────────────────────────────────
// Note: DirItem and FolderBrowserState are defined in their original file
// (HostListViewModel.kt package) per the APK structure

// ─── Utility Functions ──────────────────────────────────────────────

/**
 * 解析 CDP `/json` 返回的 pages 数组。
 *
 * @param appType 若调用方已知本次请求针对的 IDE（例如启动时用户在 `LaunchIdeDialog`
 *  已选好），传入对应枚举；不传则视为 [ElectronAppType.UNKNOWN]。这里**不做**任何
 *  从 URL/title 反推 IDE 的逻辑，要靠 IDE 身份分发的下游一律读 [CdpPage.appType]。
 */
fun parsePages(
    json: String,
    appType: ElectronAppType = ElectronAppType.UNKNOWN
): List<CdpPage> {
    return try {
        val array = JsonParser.parseString(json).asJsonArray!!
        array.map { elem ->
            val obj = elem.asJsonObject
            CdpPage(
                id = obj.get("id")?.asString ?: "",
                type = obj.get("type")?.asString ?: "",
                title = obj.get("title")?.asString ?: "",
                url = obj.get("url")?.asString ?: "",
                webSocketDebuggerUrl = obj.get("webSocketDebuggerUrl")?.asString ?: "",
                devtoolsFrontendUrl = obj.get("devtoolsFrontendUrl")?.asString ?: "",
                appType = appType
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
