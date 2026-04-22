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
    val devtoolsFrontendUrl: String = ""
) {
    val isWorkbench: Boolean
        get() = type == "page" && "workbench.html" in url && "jetski" !in url

    val cdpPort: Int?
        get() {
            val relayMatch = Regex("/cdp/(\\d+)/").find(webSocketDebuggerUrl)
            if (relayMatch != null) return relayMatch.groupValues[1].toIntOrNull()
            val directMatch = Regex("://[^:/]+:(\\d+)/").find(webSocketDebuggerUrl)
            return directMatch?.groupValues?.get(1)?.toIntOrNull()
        }

    val appType: ElectronAppType
        get() = when {
            title.contains("Antigravity", ignoreCase = true) || url.contains("Antigravity.app", ignoreCase = true) -> ElectronAppType.ANTIGRAVITY
            title.contains("Cursor", ignoreCase = true) || url.contains("Cursor.app", ignoreCase = true) -> ElectronAppType.CURSOR
            title.contains("Windsurf", ignoreCase = true) || url.contains("Windsurf.app", ignoreCase = true) -> ElectronAppType.WINDSURF
            title.contains("Codex", ignoreCase = true) -> ElectronAppType.CODEX
            "workbench.html" in url -> ElectronAppType.VSCODE_LIKE
            else -> ElectronAppType.UNKNOWN
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

fun parsePages(json: String): List<CdpPage> {
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
                devtoolsFrontendUrl = obj.get("devtoolsFrontendUrl")?.asString ?: ""
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
