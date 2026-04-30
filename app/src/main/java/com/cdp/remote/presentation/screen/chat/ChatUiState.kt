package com.cdp.remote.presentation.screen.chat

import com.cdp.remote.data.cdp.ChatMessage
import com.cdp.remote.data.cdp.ConnectionState
import java.util.concurrent.atomic.AtomicLong

data class PendingImage(
    val id: Long = idCounter.getAndIncrement(),
    val base64: String,
    val mimeType: String,
    val thumbnailBytes: ByteArray? = null,  // compressed small preview
    val rawBytes: ByteArray? = null  // 原始二进制，用于 Relay HTTP 直传（绕开 base64 分块）
) {
    companion object {
        private val idCounter = AtomicLong(1)
    }
}

data class ChatUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val appName: String = "Antigravity",
    val currentModel: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isSendingMessage: Boolean = false,
    val isGenerating: Boolean = false,
    val error: String? = null,
    val lastScreenshot: ByteArray? = null,
    val retryCount: Int = 0,
    val tvMode: Boolean = true,
    val tvFrame: ByteArray? = null,
    val tvQuality: Int = 60,
    val tvIntervalMs: Long = 800L,
    val tvBytesTotal: Long = 0L,
    val tvFrameCount: Int = 0,
    val tvFocusChat: Boolean = true,
    val availableApps: List<com.cdp.remote.data.cdp.CdpPage> = emptyList(),
    val pendingImages: List<PendingImage> = emptyList(),
    val ideModelOptions: List<String> = emptyList(),
    val ideModelOptionsLoading: Boolean = false,
    val recentSessions: List<String> = emptyList(),
    val isSessionsLoading: Boolean = false,
    /** 连接成功后 OTA 检查发现新版本（与主机列表同源，聊天页也会弹窗） */
    val pendingOta: com.cdp.remote.data.ota.OtaUpdateManager.VersionInfo? = null,
    val pendingOtaRelayUrl: String? = null,
    // Keep legacy fields for backward compat during transition
    val pendingImageBase64: String? = null,
    val pendingImageMimeType: String? = null,
    /** 当前是否为 Windsurf IDE，用于显示专属功能（如取消任务按钮） */
    val isWindsurf: Boolean = false,
    /** TV 模式下是否处于操控模式（true=可点击操控 IDE, false=默认观影模式可缩放平移） */
    val tvControlMode: Boolean = false,
    /** 远端 IDE 页面实际像素宽度（用于触摸坐标映射） */
    val tvPageWidth: Int = 0,
    /** 远端 IDE 页面实际像素高度（用于触摸坐标映射） */
    val tvPageHeight: Int = 0,
    /** Codex 项目列表（侧边栏 Projects 区域） */
    val codexProjects: List<com.cdp.remote.data.cdp.CodexProject> = emptyList(),
    /** Codex 项目列表加载中 */
    val codexProjectsLoading: Boolean = false,
    /** 当前 Codex 项目名 */
    val codexCurrentProject: String = ""
)
