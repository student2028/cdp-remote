package com.cdp.remote.presentation.screen.chat

import com.cdp.remote.data.cdp.ChatMessage
import com.cdp.remote.data.cdp.ConnectionState

data class PendingImage(
    val id: Long = pendingImageIdCounter++,
    val base64: String,
    val mimeType: String,
    val thumbnailBytes: ByteArray? = null  // compressed small preview
)

private var pendingImageIdCounter = 1L

data class ChatUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val appName: String = "Antigravity",
    val currentModel: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
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
    val pendingImageMimeType: String? = null
)
