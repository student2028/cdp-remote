package com.cdp.remote.presentation.screen.chat

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cdp.remote.data.cdp.*
import com.cdp.remote.data.UittyNewTabHistoryStore
import com.cdp.remote.data.UittyNewTabRecent
import com.cdp.remote.presentation.screen.hosts.CwdHistoryItem
import com.cdp.remote.presentation.screen.hosts.DirItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

private data class ChatDraft(
    val text: String = "",
    val images: List<PendingImage> = emptyList()
)

private object ChatDraftStore {
    private val drafts = mutableMapOf<String, ChatDraft>()

    @Synchronized
    fun save(key: String, text: String, images: List<PendingImage>) {
        if (key.isBlank()) return
        if (text.isBlank() && images.isEmpty()) drafts.remove(key)
        else {
            // 去掉 rawBytes 避免 static Map 内存膨胀；文件附件保留 cachePath。
            val lightweight = images.map { it.copy(rawBytes = null) }
            drafts[key] = ChatDraft(text, lightweight)
        }
    }

    @Synchronized
    fun load(key: String): ChatDraft = drafts[key] ?: ChatDraft()

    @Synchronized
    fun clear(key: String) {
        if (key.isNotBlank()) drafts.remove(key)
    }
}

class ChatViewModel(
    private val cdpClient: ICdpClient = CdpClient()
) : ViewModel() {

    companion object {
        private const val TAG = "ChatVM"
    }

    var uiState by mutableStateOf(ChatUiState())
        private set

    private var commands: AntigravityCommands? = null
    private var codexCommands: CodexCommands? = null
    private var claudeCodeCommands: ClaudeCodeCommands? = null
    private var uittyCommands: UittyCommands? = null
    private var isCodex: Boolean = false
    private var isAntigravity: Boolean = false
    private var isClaudeCode: Boolean = false
    private var isWindsurf: Boolean = false
    private var isUitty: Boolean = false
    private var connectHost: HostInfo? = null
    private var connectWsUrl: String = ""
    private var draftKey: String = ""
    private var lastReplyText: String = ""
    private var pollJob: Job? = null
    private var pollCount: Int = 0
    private var tvJob: Job? = null
    private var reconnectJob: Job? = null
    private var errorWatchdogJob: Job? = null
    private var reconnectAttempts: Int = 0
    private val remoteInputMutex = Mutex()

    // ─── Connection ─────────────────────────────────────────────────

    private val httpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun fetchAvailableApps(hostIp: String, hostPort: Int) {
        viewModelScope.launch {
            try {
                val pages = withContext(Dispatchers.IO) {
                    val request = okhttp3.Request.Builder()
                        .url("http://$hostIp:$hostPort/targets")
                        .build()
                    val response = httpClient.newCall(request).execute()
                    val body = response.body?.string() ?: return@withContext emptyList<CdpPage>()
                    IdeTargetsParser.parsePages(body)
                }
                
                if (pages.isNotEmpty()) {
                    uiState = uiState.copy(availableApps = pages)
                } else {
                    val result = cdpClient.discoverPages(HostInfo(hostIp, hostPort))
                    if (result is CdpResult.Success) {
                        uiState = uiState.copy(availableApps = result.data)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取可用应用失败", e)
            }
        }
    }

    fun connect(hostIp: String, hostPort: Int, wsUrl: String, appName: String) {
        val host = HostInfo(hostIp, hostPort)
        saveCurrentDraft()
        draftKey = buildDraftKey(hostIp, hostPort, wsUrl, appName)
        val draft = ChatDraftStore.load(draftKey)
        uiState = uiState.copy(
            connectionState = ConnectionState.CONNECTING,
            appName = appName,
            inputText = draft.text,
            pendingImages = draft.images,
            pendingImageBase64 = draft.images.lastOrNull()?.base64,
            pendingImageMimeType = draft.images.lastOrNull()?.mimeType
        )
        connectHost = host
        connectWsUrl = wsUrl

        viewModelScope.launch { doConnect(host, wsUrl, appName) }
        viewModelScope.launch {
            cdpClient.connectionState.collectLatest { state ->
                uiState = uiState.copy(connectionState = state)
                if (state == ConnectionState.ERROR || state == ConnectionState.DISCONNECTED) {
                    scheduleReconnect(host, wsUrl, appName)
                }
            }
        }
    }

    private fun buildDraftKey(hostIp: String, hostPort: Int, wsUrl: String, appName: String): String {
        return listOf(hostIp.trim(), hostPort.toString(), wsUrl.trim(), appName.trim()).joinToString("|")
    }

    private fun saveCurrentDraft() {
        if (uiState.isGenerating && uiState.inputText.isBlank() && uiState.pendingImages.isEmpty()) return
        ChatDraftStore.save(draftKey, uiState.inputText, uiState.pendingImages)
    }

    private fun saveDraft(text: String = uiState.inputText, images: List<PendingImage> = uiState.pendingImages) {
        ChatDraftStore.save(draftKey, text, images)
    }

    private fun clearDraft() {
        ChatDraftStore.clear(draftKey)
    }

    private fun restoreDraft(text: String, images: List<PendingImage>) {
        restoreDraftForKey(draftKey, text, images)
    }

    private fun restoreDraftForKey(key: String, text: String, images: List<PendingImage>) {
        ChatDraftStore.save(key, text, images)
        if (key != draftKey) {
            uiState = uiState.copy(isSendingMessage = false, isGenerating = false)
            return
        }
        uiState = uiState.copy(
            inputText = text,
            pendingImages = images,
            pendingImageBase64 = images.lastOrNull()?.base64,
            pendingImageMimeType = images.lastOrNull()?.mimeType,
            isSendingMessage = false,
            isGenerating = false
        )
    }

    private suspend fun doConnect(host: HostInfo, wsUrl: String, appName: String) {
        val result = if (wsUrl.isNotBlank()) {
            Log.d(TAG, "使用 wsUrl 直连: $wsUrl")
            cdpClient.connectDirect(wsUrl)
        } else {
            Log.d(TAG, "回退到 connectToWorkbench")
            cdpClient.connectToWorkbench(host).let { r ->
                if (r is CdpResult.Error) CdpResult.Error(r.message)
                else CdpResult.Success(Unit)
            }
        }

        when (result) {
            is CdpResult.Success -> {
                val appType = ElectronAppType.fromAppName(appName)
                isCodex = appType == ElectronAppType.CODEX
                isAntigravity = appType == ElectronAppType.ANTIGRAVITY
                isClaudeCode = appType == ElectronAppType.CLAUDE_CODE
                isWindsurf = appType == ElectronAppType.WINDSURF
                isUitty = appType == ElectronAppType.UITTY
                if (isClaudeCode) {
                    claudeCodeCommands = ClaudeCodeCommands(cdpClient)
                    commands = null
                    codexCommands = null
                    uittyCommands = null
                } else if (isCodex) {
                    codexCommands = CodexCommands(cdpClient)
                    commands = null
                    claudeCodeCommands = null
                    uittyCommands = null
                } else if (isWindsurf) {
                    commands = WindsurfCommands(cdpClient)
                    codexCommands = null
                    claudeCodeCommands = null
                    uittyCommands = null
                } else if (isUitty) {
                    uittyCommands = UittyCommands(cdpClient)
                    commands = null
                    codexCommands = null
                    claudeCodeCommands = null
                } else {
                    // Cursor 走 CursorCommands（继承自 AntigravityCommands，仅覆写 switchModel）；
                    // 其它 IDE 保留反重力实现，行为保持不变。
                    commands = if (appType == ElectronAppType.CURSOR) CursorCommands(cdpClient, appName)
                               else AntigravityCommands(cdpClient, appName)
                    codexCommands = if (isAntigravity) CodexCommands(cdpClient) else null
                    claudeCodeCommands = null
                    uittyCommands = null
                }
                uiState = uiState.copy(connectionState = ConnectionState.CONNECTED, error = null, isWindsurf = isWindsurf, isUitty = isUitty)
                reconnectAttempts = 0
                addSystemMessage("已连接到 $appName")
                fetchAvailableApps(host.ip, host.port)
                startBackgroundSync()
                startTvMode()
                startErrorWatchdog()
            }
            is CdpResult.Error -> {
                uiState = uiState.copy(connectionState = ConnectionState.ERROR, error = result.message)
                addSystemMessage("连接失败: ${result.message} ❌")
            }
        }
    }

    fun scheduleReconnect(host: HostInfo, wsUrl: String, appName: String) {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            uiState = uiState.copy(connectionState = ConnectionState.RECONNECTING)
            val delayMs = 3000L * (reconnectAttempts + 1).coerceAtMost(10)
            Log.d(TAG, "重连等待 ${delayMs}ms (第${reconnectAttempts + 1}次)")
            delay(delayMs)
            reconnectAttempts++
            Log.d(TAG, "尝试自动重连...")
            addSystemMessage("正在重新连接... 🔄")
            doConnect(host, wsUrl, appName)
        }
    }

    // ─── Messaging ──────────────────────────────────────────────────

    fun updateInputText(text: String) {
        uiState = uiState.copy(inputText = text)
        saveDraft(text = text)
    }

    fun sendMessage() {
        if (uiState.isSendingMessage) return
        val text = uiState.inputText.trim()
        val images = uiState.pendingImages
        if (text.isBlank() && images.isEmpty()) return
        saveDraft(text = text, images = images)
        val sendDraftKey = draftKey

        val imageCount = images.count { it.mimeType.startsWith("image/") }
        val videoCount = images.count { it.mimeType.startsWith("video/") }
        val fileCount = images.size - imageCount - videoCount
        val labelParts = mutableListOf<String>()
        if (imageCount > 0) labelParts.add("📷 ${imageCount}张图片")
        if (videoCount > 0) labelParts.add("🎥 ${videoCount}个视频")
        if (fileCount > 0) labelParts.add("📎 ${fileCount}个文件")
        val mediaLabel = if (labelParts.isNotEmpty()) "[${labelParts.joinToString("+")}] " else ""
        val displayText = "$mediaLabel$text".trim()
        val userMsg = ChatMessage(role = MessageRole.USER, content = displayText)
        val msgs = uiState.messages + userMsg
        uiState = uiState.copy(
            messages = msgs,
            inputText = "",
            pendingImages = emptyList(),
            pendingImageBase64 = null,
            pendingImageMimeType = null,
            isSendingMessage = true,
            isGenerating = true,
            error = null
        )

        if (!hasCommands()) {
            updateError("未连接")
            restoreDraftForKey(sendDraftKey, text, images)
            uiState = uiState.copy(isSendingMessage = false)
            return
        }

        // Snapshot draft. Do not clear it until CDP confirms the whole send path succeeded.
        val imagesToSend = images.toList()

        viewModelScope.launch {
            var sendSucceeded = true
            // 构建 relay 基础 URL（用于 HTTP 直传媒体文件）
            val relayBase = connectHost?.let { "http://${it.ip}:${it.port}" }

            // 分离图片和普通文件。终端类 uitty 没有浏览器图片粘贴容器，图片也按附件 URL 发送。
            val imageItems = if (isUitty) emptyList() else imagesToSend.filter { it.mimeType.startsWith("image/") }
            val fileItems = if (isUitty) imagesToSend else imagesToSend.filter { !it.mimeType.startsWith("image/") }

            // ── Step A: 上传非图片附件到 Relay 并收集 URL ──
            val uploadedFiles = mutableListOf<Pair<PendingImage, String>>()
            for ((index, attachment) in fileItems.withIndex()) {
                if (relayBase == null || (attachment.cachePath == null && attachment.rawBytes == null)) {
                    addSystemMessage("第${index + 1}个附件无法上传: 无 Relay 连接或缓存数据 ❌")
                    sendSucceeded = false
                    break
                }
                try {
                    val file = attachment.cachePath?.let(::File)
                    val rawBytes = attachment.rawBytes
                    val attachmentSize = file?.takeIf { it.exists() }?.length() ?: rawBytes?.size?.toLong() ?: 0L
                    if (file != null && !file.exists()) {
                        addSystemMessage("第${index + 1}个附件缓存不存在: ${attachment.fileName} ❌")
                        sendSucceeded = false
                        break
                    }
                    if (attachmentSize > 100L * 1024L * 1024L) {
                        addSystemMessage("第${index + 1}个附件超过 100MB 上限: ${attachment.fileName} ❌")
                        sendSucceeded = false
                        break
                    }
                    val uploadFileName = attachment.fileName.ifBlank { "attachment_${System.currentTimeMillis()}" }
                    val url = "$relayBase/upload?filename=${java.net.URLEncoder.encode(uploadFileName, "UTF-8")}&mime=${java.net.URLEncoder.encode(attachment.mimeType, "UTF-8")}"
                    val requestBody = if (file != null) {
                        file.asRequestBody(attachment.mimeType.toMediaTypeOrNull())
                    } else {
                        rawBytes!!.toRequestBody(attachment.mimeType.toMediaTypeOrNull())
                    }
                    val request = okhttp3.Request.Builder().url(url).post(requestBody).build()
                    val uploadClient = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val response = withContext(Dispatchers.IO) { uploadClient.newCall(request).execute() }
                    val body = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        addSystemMessage("第${index + 1}个附件上传失败: HTTP ${response.code} ❌")
                        sendSucceeded = false
                        break
                    }
                    val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                    val fileUrl = json.get("url")?.asString
                    if (fileUrl != null) {
                        // 转为 IDE 本地可访问的 127.0.0.1 地址
                        val localUrl = fileUrl.replace(Regex("http://[^:/]+"), "http://127.0.0.1")
                        uploadedFiles.add(attachment to localUrl)
                        Log.d(TAG, "附件已上传到 Relay: $localUrl ($attachmentSize bytes, ${attachment.mimeType})")
                        addSystemMessage("附件${index + 1}已上传 ✅ (${attachmentSize / 1024}KB)")
                    } else {
                        addSystemMessage("第${index + 1}个附件上传返回无 URL ❌")
                        sendSucceeded = false
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "附件上传异常", e)
                    addSystemMessage("第${index + 1}个附件上传异常: ${e.message} ❌")
                    sendSucceeded = false
                    break
                }
            }

            if (!sendSucceeded) {
                val userStartedNextDraft = uiState.inputText.isNotBlank() || uiState.pendingImages.isNotEmpty()
                if (!userStartedNextDraft) restoreDraftForKey(sendDraftKey, text, imagesToSend)
                else saveCurrentDraft()
                uiState = uiState.copy(isSendingMessage = false, isGenerating = false)
                return@launch
            }

            // ── Step B: 构建增强文本（附件 URL 嵌入） ──
            val enhancedText = buildString {
                if (text.isNotEmpty()) append(text)
                if (uploadedFiles.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append("[附件文件，请根据需要读取或分析以下文件]\n")
                    uploadedFiles.forEachIndexed { i, (attachment, url) ->
                        val name = attachment.fileName.ifBlank { "attachment_${i + 1}" }
                        val sizeKb = (attachment.sizeBytes.takeIf { it > 0 } ?: File(attachment.cachePath ?: "").length()) / 1024
                        append("${i + 1}. $name (${attachment.mimeType}, ${sizeKb}KB): $url\n")
                    }
                }
            }.trim()

            // ── Step C: 粘贴图片 + 发送 ──
            if (imageItems.isNotEmpty() && enhancedText.isNotEmpty()) {
                // 先粘贴所有图片（不发送）
                for ((index, img) in imageItems.withIndex()) {
                    var pasteResult: CdpResult<Boolean> = when {
                        isClaudeCode -> claudeCodeCommands!!.pasteImage(img.base64, img.mimeType)
                        isCodex -> codexCommands!!.pasteImage(img.base64, img.mimeType)
                        img.rawBytes != null && relayBase != null ->
                            commands!!.pasteImageViaRelay(img.rawBytes, img.mimeType, relayBaseUrl = relayBase)
                        else -> commands!!.pasteImage(img.base64, img.mimeType)
                    }
                    if (pasteResult is CdpResult.Error && img.rawBytes != null && relayBase != null
                        && !isClaudeCode && !isCodex) {
                        Log.w("ChatVM", "Relay 直传失败，降级到 base64 分块: ${pasteResult.message}")
                        pasteResult = commands!!.pasteImage(img.base64, img.mimeType)
                    }
                    if (pasteResult is CdpResult.Error) {
                        sendSucceeded = false
                        addSystemMessage("第${index + 1}张图片粘贴失败: ${pasteResult.message} ❌")
                        break
                    }
                    delay(500)
                }
                // 输入文字并发送
                if (sendSucceeded) {
                    val result = when {
                        isClaudeCode -> claudeCodeCommands!!.sendMessage(enhancedText)
                        isCodex -> codexCommands!!.sendMessage(enhancedText)
                        isUitty -> uittySendEnhanced(enhancedText, uploadedFiles.isNotEmpty())
                        else -> commands!!.sendMessage(enhancedText)
                    }
                    if (result is CdpResult.Error) {
                        sendSucceeded = false
                        uiState = uiState.copy(error = "发送失败: ${result.message}")
                        addSystemMessage("发送失败: ${result.message} ❌")
                    }
                }
            } else if (imageItems.isNotEmpty()) {
                // 只有图片没有文字
                for ((index, img) in imageItems.withIndex()) {
                    val imgResult = when {
                        isClaudeCode -> claudeCodeCommands!!.sendImage(img.base64, img.mimeType)
                        isCodex -> codexCommands!!.sendImage(img.base64, img.mimeType)
                        img.rawBytes != null && relayBase != null ->
                            commands!!.sendImage(img.base64, img.mimeType, relayBaseUrl = relayBase, rawBytes = img.rawBytes)
                        else -> commands!!.sendImage(img.base64, img.mimeType)
                    }
                    if (imgResult is CdpResult.Error) {
                        sendSucceeded = false
                        addSystemMessage("第${index + 1}张图片发送失败: ${imgResult.message} ❌")
                        break
                    }
                }
            } else if (enhancedText.isNotEmpty()) {
                // 只有文字（可能包含附件 URL）
                val result = when {
                    isClaudeCode -> claudeCodeCommands!!.sendMessage(enhancedText)
                    isCodex -> codexCommands!!.sendMessage(enhancedText)
                    isUitty -> uittySendEnhanced(enhancedText, uploadedFiles.isNotEmpty())
                    else -> commands!!.sendMessage(enhancedText)
                }
                if (result is CdpResult.Error) {
                    sendSucceeded = false
                    uiState = uiState.copy(error = "发送失败: ${result.message}")
                    addSystemMessage("发送失败: ${result.message} ❌")
                }
            }

            if (sendSucceeded) {
                imagesToSend.mapNotNull { it.cachePath }.forEach { path ->
                    runCatching { File(path).delete() }
                }
                val currentDraft = ChatDraftStore.load(sendDraftKey)
                val stillSameDraft = currentDraft.text.trim() == text &&
                    currentDraft.images.map { it.id } == imagesToSend.map { it.id }
                if (stillSameDraft) {
                    ChatDraftStore.clear(sendDraftKey)
                    uiState = uiState.copy(
                        inputText = "",
                        pendingImages = emptyList(),
                        pendingImageBase64 = null,
                        pendingImageMimeType = null,
                        isSendingMessage = false
                    )
                } else {
                    uiState = uiState.copy(isSendingMessage = false)
                    saveCurrentDraft()
                }
            } else {
                val userStartedNextDraft = uiState.inputText.isNotBlank() || uiState.pendingImages.isNotEmpty()
                if (!userStartedNextDraft) restoreDraftForKey(sendDraftKey, text, imagesToSend)
                else saveCurrentDraft()
                uiState = uiState.copy(isSendingMessage = false, isGenerating = false)
            }

            // Start background sync after message send
            if (sendSucceeded) {
                lastReplyText = ""
                pollCount = 0
                startBackgroundSync()
            }
        }
    }

    /**
     * 向任意端口的 IDE 发送消息（Orchestra 跨 IDE 协作）。
     *
     * 流程：
     * 1. 通过 relay `/cdp/{port}/json` 获取目标 IDE 的 workbench 页面；
     * 2. 用临时 CdpClient 直连该页面的 webSocketDebuggerUrl；
     * 3. 复用 AntigravityCommands.sendMessage 输入并提交消息；
     * 4. finally 中断开临时连接，防止泄漏。
     */
    fun sendToIde(port: Int, message: String) {
        viewModelScope.launch {
            addSystemMessage("正在向 IDE(:$port) 发送消息...")
            val host = connectHost
            if (host == null) {
                addSystemMessage("发送失败: 当前未连接 relay ❌")
                return@launch
            }
            var tempClient: ICdpClient? = null
            try {
                // 1. 通过 relay 发现目标 IDE 的 workbench 页面
                val wsUrl = withContext(Dispatchers.IO) {
                    val request = okhttp3.Request.Builder()
                        .url("http://${host.ip}:${host.port}/cdp/$port/json")
                        .build()
                    val response = httpClient.newCall(request).execute()
                    val body = response.body?.string()
                        ?: throw IllegalStateException("relay 返回空响应")
                    val parsed = com.google.gson.JsonParser.parseString(body)
                    val pagesArray = when {
                        parsed.isJsonArray -> parsed.asJsonArray
                        parsed.isJsonObject && parsed.asJsonObject.has("pages") ->
                            parsed.asJsonObject.getAsJsonArray("pages")
                        else -> throw IllegalStateException("relay 返回格式异常")
                    }
                    var found: String? = null
                    for (elem in pagesArray) {
                        val obj = elem.asJsonObject
                        val type = obj.get("type")?.asString ?: ""
                        val url = obj.get("url")?.asString ?: ""
                        if (type == "page" && url.contains("workbench")) {
                            found = obj.get("webSocketDebuggerUrl")?.asString
                            if (!found.isNullOrBlank()) break
                        }
                    }
                    found ?: throw IllegalStateException("未找到 workbench 页面")
                }

                // 2. 创建临时 CdpClient 连接
                val client = CdpClient()
                tempClient = client
                val connectResult = client.connectDirect(wsUrl)
                if (connectResult is CdpResult.Error) {
                    throw IllegalStateException("连接目标 IDE 失败: ${connectResult.message}")
                }

                // 3. 使用 AntigravityCommands 发送消息
                val tempCommands = AntigravityCommands(client, "remote")
                val sendResult = tempCommands.sendMessage(message)
                if (sendResult is CdpResult.Error) {
                    throw IllegalStateException("发送消息失败: ${sendResult.message}")
                }

                // 4. 等待片刻让目标 IDE 处理
                delay(1000)

                // 5. UI 状态
                addSystemMessage("已向 IDE(:$port) 发送消息 ✅")
            } catch (e: Exception) {
                Log.e(TAG, "sendToIde 失败", e)
                addSystemMessage("向 IDE(:$port) 发送失败: ${e.message} ❌")
            } finally {
                try { tempClient?.disconnect() } catch (e: Exception) {
                    Log.w(TAG, "断开临时连接异常: ${e.message}")
                }
            }
        }
    }

    private fun hasCommands(): Boolean = when {
        isClaudeCode -> claudeCodeCommands != null
        isCodex -> codexCommands != null
        isUitty -> uittyCommands != null
        else -> commands != null
    }

    private fun startBackgroundSync() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive && uiState.isGenerating) {
                delay(1500)
                if (!hasCommands()) break
                val result = when {
                    isClaudeCode -> claudeCodeCommands!!.getLastReply()
                    isCodex -> codexCommands!!.getLastReply()
                    isUitty -> uittyCommands!!.getLastReply()
                    else -> commands!!.getLastReply()
                }
                if (result is CdpResult.Success) {
                    val reply = result.data
                    if (reply != null && reply != lastReplyText) {
                        lastReplyText = reply
                        pollCount = 0
                        val existingMsgs = uiState.messages.toMutableList()
                        val lastMsg = existingMsgs.lastOrNull()
                        if (lastMsg?.role == MessageRole.ASSISTANT) {
                            existingMsgs[existingMsgs.lastIndex] = lastMsg.copy(content = reply, isStreaming = true)
                        } else {
                            existingMsgs.add(ChatMessage(role = MessageRole.ASSISTANT, content = reply, isStreaming = true))
                        }
                        uiState = uiState.copy(messages = existingMsgs)
                    } else {
                        pollCount++
                    }

                    // Antigravity 独有：每 5 次轮询主动检查服务器错误并自动重试
                    if (!isCodex && !isWindsurf && !isClaudeCode && !isUitty && pollCount > 0 && pollCount % 5 == 0) {
                        val midRetry = commands!!.checkAndRetryIfBusy()
                        if (midRetry is CdpResult.Success && midRetry.data) {
                            addSystemMessage("检测到错误，已自动重试 🔄")
                            pollCount = 0
                            delay(2000)
                            continue
                        }
                    }

                    val isStillGenerating = when {
                        isClaudeCode -> claudeCodeCommands!!.isGenerating()
                        isCodex -> codexCommands!!.isGenerating()
                        isUitty -> uittyCommands!!.isGenerating()
                        else -> commands!!.isGenerating()
                    }

                    // 自动放行 Agent 的 Action 确认按钮（Run/Allow/Approve 等）
                    if (isStillGenerating) {
                        val accepted = when {
                            isClaudeCode -> claudeCodeCommands!!.autoAcceptActions()
                            isCodex -> codexCommands!!.autoAcceptActions()
                            isUitty -> uittyCommands!!.autoAcceptActions()
                            else -> commands!!.autoAcceptActions()
                        }
                        if (accepted) {
                            Log.d(TAG, "自动放行了一个 Action 按钮 ✅")
                            pollCount = 0
                            delay(500)
                            continue
                        }
                    }

                    if (!isStillGenerating) {
                        // Antigravity 独有：生成结束后最终错误检查
                        // 多次尝试，因为 Retry 按钮可能还没渲染出来
                        if (!isCodex && !isWindsurf && !isClaudeCode && !isUitty) {
                            var retried = false
                            for (retryAttempt in 1..3) {
                                val retryResult = commands!!.checkAndRetryIfBusy()
                                if (retryResult is CdpResult.Success && retryResult.data) {
                                    addSystemMessage("检测到错误，已自动重试 🔄 (第${retryAttempt}次检测)")
                                    pollCount = 0
                                    retried = true
                                    delay(2000)
                                    break
                                }
                                delay(1000) // 等待 Retry 按钮渲染
                            }
                            if (retried) continue
                        }

                        val finalMsgs = uiState.messages.toMutableList()
                        val last = finalMsgs.lastOrNull()
                        if (last?.role == MessageRole.ASSISTANT) {
                            finalMsgs[finalMsgs.lastIndex] = last.copy(isStreaming = false)
                        }
                        uiState = uiState.copy(messages = finalMsgs, isGenerating = false)
                        break
                    }
                }
                if (pollCount > 60) {
                    uiState = uiState.copy(isGenerating = false)
                    break
                }
            }
        }
    }

    // ─── Image Attachment ───────────────────────────────────────────

    fun attachImage(base64Data: String, mimeType: String) {
        attachImage(base64Data, mimeType, null)
    }

    fun attachImage(base64Data: String, mimeType: String, rawBytes: ByteArray?) {
        val img = PendingImage(
            base64 = base64Data,
            mimeType = mimeType,
            fileName = "image_${System.currentTimeMillis()}.jpg",
            sizeBytes = rawBytes?.size?.toLong() ?: 0L,
            rawBytes = rawBytes
        )
        uiState = uiState.copy(
            pendingImages = uiState.pendingImages + img,
            // Keep legacy fields synced for backward compat
            pendingImageBase64 = base64Data,
            pendingImageMimeType = mimeType
        )
        saveDraft()
    }

    fun attachFile(fileName: String, mimeType: String, cachePath: String, sizeBytes: Long) {
        val attachment = PendingImage(
            mimeType = mimeType,
            fileName = fileName,
            cachePath = cachePath,
            sizeBytes = sizeBytes
        )
        uiState = uiState.copy(
            pendingImages = uiState.pendingImages + attachment,
            pendingImageBase64 = uiState.pendingImageBase64,
            pendingImageMimeType = uiState.pendingImageMimeType
        )
        saveDraft()
    }

    fun removeImage(imageId: Long) {
        uiState.pendingImages.firstOrNull { it.id == imageId }?.cachePath?.let { path ->
            runCatching { File(path).delete() }
        }
        val updated = uiState.pendingImages.filter { it.id != imageId }
        uiState = uiState.copy(
            pendingImages = updated,
            pendingImageBase64 = updated.lastOrNull()?.base64,
            pendingImageMimeType = updated.lastOrNull()?.mimeType
        )
        saveDraft(images = updated)
    }

    fun clearPendingImage() {
        uiState.pendingImages.mapNotNull { it.cachePath }.forEach { path ->
            runCatching { File(path).delete() }
        }
        uiState = uiState.copy(
            pendingImages = emptyList(),
            pendingImageBase64 = null,
            pendingImageMimeType = null
        )
        saveDraft(images = emptyList())
    }

    // ─── IDE Actions ────────────────────────────────────────────────

    fun startNewSession(appContext: Context? = null) {
        if (isUitty) {
            openUittyNewTabWizard(appContext)
            return
        }
        viewModelScope.launch {
            if (!hasCommands()) return@launch
            val result = when {
                isClaudeCode -> claudeCodeCommands!!.startNewSession()
                isCodex -> codexCommands!!.startNewSession()
                else -> commands!!.startNewSession()
            }
            when (result) {
                is CdpResult.Success -> addSystemMessage("已新建会话 🆕")
                is CdpResult.Error -> addSystemMessage("新会话失败: ${result.message}")
            }
        }
    }

    fun showRecentSessions() {
        viewModelScope.launch {
            if (!hasCommands()) return@launch
            val result = when {
                isClaudeCode -> claudeCodeCommands!!.showRecentSessions()
                isCodex -> codexCommands!!.showRecentSessions()
                isUitty -> uittyCommands!!.showRecentSessions()
                else -> commands!!.showRecentSessions()
            }
            when (result) {
                is CdpResult.Success -> addSystemMessage(
                    if (isUitty) {
                        "uitty：远端「会话」即当前浏览器页里的标签页，请点底部「Tab」列出并切换 🗂️"
                    } else {
                        "已打开历史会话 🕰"
                    }
                )
                is CdpResult.Error -> addSystemMessage("打开历史失败: ${result.message}")
            }
        }
    }

    fun closeUittyCurrentTab() {
        if (!isUitty) return
        viewModelScope.launch {
            if (!hasCommands()) return@launch
            val result = uittyCommands!!.closeCurrentTab()
            when (result) {
                is CdpResult.Success -> addSystemMessage("已关闭当前 uitty 标签页 🗂️")
                is CdpResult.Error -> addSystemMessage("关闭 uitty 标签页失败：${result.message}")
            }
        }
    }

    fun switchSession(isNext: Boolean) {
        viewModelScope.launch {
            if (!hasCommands()) return@launch
            val result = when {
                isClaudeCode -> claudeCodeCommands!!.switchSession(isNext)
                isCodex -> codexCommands!!.switchSession(isNext)
                isUitty -> uittyCommands!!.switchSession(isNext)
                else -> commands!!.switchSession(isNext)
            }
            when (result) {
                is CdpResult.Success -> addSystemMessage(
                    if (isUitty) "已在 uitty 当前窗口内切换到相邻标签页 🔄"
                    else "已切换会话 🔄"
                )
                is CdpResult.Error ->
                    addSystemMessage(
                        if (isUitty) "切换 uitty 标签页失败：${result.message}"
                        else "切换会话失败: ${result.message}"
                    )
            }
        }
    }

    fun fetchRecentSessionsList() {
        uiState = uiState.copy(isSessionsLoading = true)
        viewModelScope.launch {
            if (!hasCommands()) {
                uiState = uiState.copy(isSessionsLoading = false)
                return@launch
            }
            val result = when {
                isClaudeCode -> claudeCodeCommands!!.getRecentSessionsList()
                isCodex -> codexCommands!!.getRecentSessionsList()
                isUitty -> uittyCommands!!.getRecentSessionsList()
                else -> commands!!.getRecentSessionsList()
            }
            when (result) {
                is CdpResult.Success -> {
                    uiState = uiState.copy(recentSessions = result.data, isSessionsLoading = false)
                }
                is CdpResult.Error -> {
                    uiState = uiState.copy(isSessionsLoading = false)
                    addSystemMessage(
                        if (isUitty) "获取 uitty 标签页列表失败：${result.message}"
                        else "获取会话列表失败: ${result.message}"
                    )
                }
            }
        }
    }

    fun switchSessionByIndex(index: Int) {
        viewModelScope.launch {
            if (!hasCommands()) return@launch
            val result = when {
                isClaudeCode -> claudeCodeCommands!!.switchSessionByIndex(index)
                isCodex -> codexCommands!!.switchSessionByIndex(index)
                isUitty -> uittyCommands!!.switchSessionByIndex(index)
                else -> commands!!.switchSessionByIndex(index)
            }
            when (result) {
                is CdpResult.Success -> {
                    addSystemMessage(
                        if (isUitty) "已切换到所选 uitty 标签页 🔄"
                        else "已切换到指定会话 🔄"
                    )
                    uiState = uiState.copy(recentSessions = emptyList()) // Close dialog
                }
                is CdpResult.Error ->
                    addSystemMessage(
                        if (isUitty) "切换 uitty 标签页失败：${result.message}"
                        else "切换会话失败: ${result.message}"
                    )
            }
        }
    }

    fun closeSessionDialog() {
        uiState = uiState.copy(recentSessions = emptyList(), isSessionsLoading = false)
    }

    // ─── uitty：手机端 Relay 目录 + CLI（与 Codex `/dirs`、`RemoteFolderBrowserDialog` 同源）───

    private fun uittyBasename(path: String): String =
        path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { "/" }

    fun openUittyNewTabWizard(appContext: Context? = null) {
        if (!isUitty) return
        val hostUrl = connectHost?.httpUrl ?: run {
            addSystemMessage("新建 uitty Tab：请先连接 Relay")
            return
        }
        val recents = appContext?.let { UittyNewTabHistoryStore.list(it) } ?: emptyList()
        uiState = uiState.copy(
            uittyCliPickerVisible = false,
            uittyCliPickerWorkingDir = "",
            uittyLaunchRecents = recents,
            uittyWorkspaceBrowserState = uiState.uittyWorkspaceBrowserState.copy(
                isOpen = true,
                hostUrl = hostUrl,
                currentPath = "",
                error = null,
                dirs = emptyList(),
                parentPath = ""
            )
        )
        loadUittyWorkspaceDirectory(hostUrl, "")
        fetchUittyRelayCwdHistory()
    }

    fun closeUittyWorkspaceBrowser() {
        uiState = uiState.copy(
            uittyWorkspaceBrowserState = uiState.uittyWorkspaceBrowserState.copy(isOpen = false)
        )
    }

    fun loadUittyWorkspaceDirectory(hostUrl: String, path: String) {
        uiState = uiState.copy(
            uittyWorkspaceBrowserState = uiState.uittyWorkspaceBrowserState.copy(
                isLoading = true,
                currentPath = path,
                error = null
            )
        )
        viewModelScope.launch {
            try {
                val (isSuccess, body, code) = withContext(Dispatchers.IO) {
                    val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
                    val request = okhttp3.Request.Builder()
                        .url("$hostUrl/dirs?path=$encodedPath")
                        .build()
                    val res = httpClient.newCall(request).execute()
                    val b = res.body?.string()
                    val s = res.isSuccessful
                    val c = res.code
                    res.close()
                    Triple(s, b, c)
                }
                if (body != null && isSuccess) {
                    val jsonObj = com.google.gson.JsonParser.parseString(body).asJsonObject
                    val dirsArray = jsonObj.getAsJsonArray("dirs")
                    val currentElement = jsonObj.get("current")
                    val parentElement = jsonObj.get("parent")
                    val dirs = (dirsArray?.toList() ?: emptyList())
                        .map { it.asJsonObject }
                        .map { obj ->
                            DirItem(
                                name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "",
                                path = obj.get("path")?.takeIf { !it.isJsonNull }?.asString ?: ""
                            )
                        }
                    uiState = uiState.copy(
                        uittyWorkspaceBrowserState = uiState.uittyWorkspaceBrowserState.copy(
                            isLoading = false,
                            currentPath = currentElement?.takeIf { !it.isJsonNull }?.asString ?: "",
                            parentPath = parentElement?.takeIf { !it.isJsonNull }?.asString ?: "",
                            dirs = dirs
                        )
                    )
                } else {
                    uiState = uiState.copy(
                        uittyWorkspaceBrowserState = uiState.uittyWorkspaceBrowserState.copy(
                            isLoading = false,
                            error = "加载失败: 服务器响应异常 (Code: $code)"
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "uitty workspace directory load failed", e)
                uiState = uiState.copy(
                    uittyWorkspaceBrowserState = uiState.uittyWorkspaceBrowserState.copy(
                        isLoading = false,
                        error = e.message ?: "未知错误"
                    )
                )
            }
        }
    }

    fun createUittyWorkspaceDirectory(hostUrl: String, parentPath: String, folderName: String) {
        viewModelScope.launch {
            try {
                val cleanParent = if (parentPath.endsWith("/")) parentPath.dropLast(1) else parentPath
                val newDirPath = "$cleanParent/$folderName"
                val encodedPath = java.net.URLEncoder.encode(newDirPath, "UTF-8")
                withContext(Dispatchers.IO) {
                    val request = okhttp3.Request.Builder()
                        .url("$hostUrl/mkdir?path=$encodedPath")
                        .build()
                    httpClient.newCall(request).execute().close()
                }
                loadUittyWorkspaceDirectory(hostUrl, parentPath)
            } catch (e: Exception) {
                uiState = uiState.copy(
                    uittyWorkspaceBrowserState = uiState.uittyWorkspaceBrowserState.copy(
                        error = "创建文件夹失败: ${e.message}"
                    )
                )
            }
        }
    }

    /** 目录浏览器里选定路径后弹出 CLI 选项 */
    fun onUittyWorkspacePathChosen(path: String, appContext: Context? = null) {
        val recents = appContext?.let { UittyNewTabHistoryStore.list(it) }
            ?: uiState.uittyLaunchRecents
        uiState = uiState.copy(
            uittyWorkspaceBrowserState = uiState.uittyWorkspaceBrowserState.copy(isOpen = false),
            uittyCliPickerVisible = true,
            uittyCliPickerWorkingDir = path.trim(),
            uittyLaunchRecents = recents
        )
    }

    fun dismissUittyCliPicker() {
        uiState = uiState.copy(uittyCliPickerVisible = false, uittyCliPickerWorkingDir = "")
    }

    fun confirmUittyCliPreset(preset: UittyCliLaunchPreset, appContext: Context? = null) {
        if (!hasCommands() || uittyCommands == null) return
        val dir = uiState.uittyCliPickerWorkingDir.trim()
        if (dir.isEmpty()) {
            addSystemMessage("工作目录为空")
            return
        }
        dismissUittyCliPicker()
        viewModelScope.launch {
            val result = uittyRunPresetLaunch(dir, preset)
            when (result) {
                is CdpResult.Success -> {
                    val shortName = uittyBasename(dir)
                    val tabTitle = "${preset.displayLabel} · $shortName"
                    addSystemMessage("已在 uitty 新建 Tab：$tabTitle ")
                    appContext?.let {
                        UittyNewTabHistoryStore.recordPreset(it, dir, preset)
                        refreshUittyLaunchRecentsInState(it)
                    }
                    postUittyCwdToRelay(dir)
                }
                is CdpResult.Error ->
                    addSystemMessage("新建 uitty Tab 失败：${result.message}")
            }
        }
    }

    /** @param cliLine `cd` 之后的一整段命令（脚本、带参数均可） */
    fun confirmUittyCliCustom(cliLine: String, appContext: Context? = null) {
        if (!hasCommands() || uittyCommands == null) return
        val dir = uiState.uittyCliPickerWorkingDir.trim()
        val line = cliLine.trim()
        if (dir.isEmpty() || line.isEmpty()) {
            addSystemMessage("请填写自定义命令（不含 cd）")
            return
        }
        dismissUittyCliPicker()
        viewModelScope.launch {
            val result = uittyRunCustomLaunch(dir, line)
            when (result) {
                is CdpResult.Success -> {
                    addSystemMessage("已在 uitty 新建 Tab（自定义）")
                    appContext?.let {
                        UittyNewTabHistoryStore.recordCustom(it, dir, line)
                        refreshUittyLaunchRecentsInState(it)
                    }
                    postUittyCwdToRelay(dir)
                }
                is CdpResult.Error ->
                    addSystemMessage("新建 uitty Tab 失败：${result.message}")
            }
        }
    }

    private fun refreshUittyLaunchRecentsInState(appContext: Context) {
        uiState = uiState.copy(uittyLaunchRecents = UittyNewTabHistoryStore.list(appContext))
    }

    /** 一键复用「最近 CLI + 目录」 */
    fun replayUittyRecent(appContext: Context, recent: UittyNewTabRecent) {
        if (!hasCommands() || uittyCommands == null) return
        dismissUittyCliPicker()
        viewModelScope.launch {
            val path = recent.path.trim()
            if (path.isEmpty()) return@launch
            val preset = recent.preset()
            val result = when {
                preset != null -> uittyRunPresetLaunch(path, preset)
                recent.customCommand.isNotBlank() -> uittyRunCustomLaunch(path, recent.customCommand.trim())
                else -> return@launch
            }
            when (result) {
                is CdpResult.Success -> {
                    if (preset != null) {
                        val tabTitle = "${preset.displayLabel} · ${uittyBasename(path)}"
                        addSystemMessage("已在 uitty 新建 Tab：$tabTitle ")
                        UittyNewTabHistoryStore.recordPreset(appContext, path, preset)
                    } else {
                        addSystemMessage("已在 uitty 新建 Tab（自定义）")
                        UittyNewTabHistoryStore.recordCustom(appContext, path, recent.customCommand.trim())
                    }
                    refreshUittyLaunchRecentsInState(appContext)
                    postUittyCwdToRelay(path)
                }
                is CdpResult.Error -> addSystemMessage("新建 uitty Tab 失败：${result.message}")
            }
        }
    }

    fun removeUittyLaunchRecent(appContext: Context, recent: UittyNewTabRecent) {
        UittyNewTabHistoryStore.remove(appContext, recent)
        refreshUittyLaunchRecentsInState(appContext)
    }

    private fun fetchUittyRelayCwdHistory() {
        val hostUrl = connectHost?.httpUrl ?: return
        viewModelScope.launch {
            try {
                val body = withContext(Dispatchers.IO) {
                    val request = okhttp3.Request.Builder()
                        .url("$hostUrl/cwd_history")
                        .build()
                    httpClient.newCall(request).execute().use { res ->
                        if (!res.isSuccessful) return@use null
                        res.body?.string()
                    }
                } ?: return@launch
                val root = com.google.gson.JsonParser.parseString(body).asJsonObject
                val historyArray = root.getAsJsonArray("history") ?: return@launch
                val items = historyArray.map { elem ->
                    val obj = elem.asJsonObject
                    CwdHistoryItem(
                        path = obj.get("path")?.asString ?: "",
                        app = obj.get("app")?.asString ?: "",
                        time = obj.get("time")?.asString ?: ""
                    )
                }
                uiState = uiState.copy(uittyCwdHistory = items)
            } catch (e: Exception) {
                Log.d(TAG, "uitty relay cwd history: ${e.message}")
            }
        }
    }

    fun removeUittyRelayCwdHistoryPath(path: String) {
        val hostUrl = connectHost?.httpUrl ?: return
        viewModelScope.launch {
            try {
                val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
                withContext(Dispatchers.IO) {
                    val request = okhttp3.Request.Builder()
                        .url("$hostUrl/cwd_history?path=$encodedPath")
                        .delete()
                        .build()
                    httpClient.newCall(request).execute().close()
                }
                uiState = uiState.copy(
                    uittyCwdHistory = uiState.uittyCwdHistory.filter { it.path != path }
                )
            } catch (e: Exception) {
                Log.d(TAG, "uitty relay cwd history delete: ${e.message}")
            }
        }
    }

    private fun postUittyCwdToRelay(dir: String) {
        val hostUrl = connectHost?.httpUrl ?: return
        viewModelScope.launch {
            try {
                val json = JSONObject().apply {
                    put("path", dir.trim())
                    put("app", "uitty")
                }.toString()
                withContext(Dispatchers.IO) {
                    val request = okhttp3.Request.Builder()
                        .url("$hostUrl/cwd_history")
                        .post(json.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                        .build()
                    httpClient.newCall(request).execute().close()
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * uitty + Claude Code：纯文本 [UittyCommands.sendMessage] 末尾一次 Enter 即可提交。
     * 图片/视频走 Relay 上传后拼进 [enhancedText] 为多行 + URL，TUI 常需再多一次 Enter 才提交整段
     * （否则停在输入缓冲，需用户手动点工具栏 Enter）。
     */
    private suspend fun uittySendEnhanced(enhancedText: String, relayAttachmentsPresent: Boolean): CdpResult<Unit> {
        val u = uittyCommands ?: return CdpResult.Error("uitty 未就绪")
        when (val first = u.sendMessage(enhancedText)) {
            is CdpResult.Error -> return first
            is CdpResult.Success -> Unit
        }
        if (relayAttachmentsPresent) {
            delay(120)
            when (val second = u.acceptAll()) {
                is CdpResult.Error ->
                    Log.w(TAG, "uitty 附件消息后追加 Enter 失败: ${second.message}")
                else -> Unit
            }
        }
        return CdpResult.Success(Unit)
    }

    private suspend fun uittyRunPresetLaunch(dir: String, preset: UittyCliLaunchPreset): CdpResult<Unit> {
        val shortName = uittyBasename(dir)
        val tabTitle = "${preset.displayLabel} · $shortName"
        return uittyCommands!!.launchCliInWorkspace(dir, preset.shellCommand, tabTitle, preset.emoji)
    }

    private suspend fun uittyRunCustomLaunch(dir: String, line: String): CdpResult<Unit> {
        val shortName = uittyBasename(dir)
        val tabTitle = "⚙ · $shortName"
        return uittyCommands!!.launchCliInWorkspace(dir, line, tabTitle, "⚙️")
    }

    // ─── Codex 项目管理 ─────────────────────────────────────────────

    fun openCodexProjectFolderBrowser() {
        if (!isCodex && !isAntigravity) return
        val hostUrl = connectHost?.httpUrl
        if (hostUrl == null) {
            addSystemMessage("添加项目失败: 未连接 Relay")
            return
        }
        uiState = uiState.copy(
            codexWorkspaceBrowserState = uiState.codexWorkspaceBrowserState.copy(
                isOpen = true,
                hostUrl = hostUrl,
                currentPath = ""
            )
        )
        loadCodexWorkspaceDirectory(hostUrl, "")
    }

    fun closeCodexProjectFolderBrowser() {
        uiState = uiState.copy(
            codexWorkspaceBrowserState = uiState.codexWorkspaceBrowserState.copy(isOpen = false)
        )
    }

    fun loadCodexWorkspaceDirectory(hostUrl: String, path: String) {
        uiState = uiState.copy(
            codexWorkspaceBrowserState = uiState.codexWorkspaceBrowserState.copy(
                isLoading = true,
                currentPath = path,
                error = null
            )
        )
        viewModelScope.launch {
            try {
                val (isSuccess, body, code) = withContext(Dispatchers.IO) {
                    val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
                    val request = okhttp3.Request.Builder()
                        .url("$hostUrl/dirs?path=$encodedPath")
                        .build()
                    val res = httpClient.newCall(request).execute()
                    val b = res.body?.string()
                    val s = res.isSuccessful
                    val c = res.code
                    res.close()
                    Triple(s, b, c)
                }
                if (body != null && isSuccess) {
                    val jsonObj = com.google.gson.JsonParser.parseString(body).asJsonObject
                    val dirsArray = jsonObj.getAsJsonArray("dirs")
                    val currentElement = jsonObj.get("current")
                    val parentElement = jsonObj.get("parent")
                    val dirs = (dirsArray?.toList() ?: emptyList())
                        .map { it.asJsonObject }
                        .map { obj ->
                            DirItem(
                                name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "",
                                path = obj.get("path")?.takeIf { !it.isJsonNull }?.asString ?: ""
                            )
                        }
                    uiState = uiState.copy(
                        codexWorkspaceBrowserState = uiState.codexWorkspaceBrowserState.copy(
                            isLoading = false,
                            currentPath = currentElement?.takeIf { !it.isJsonNull }?.asString ?: "",
                            parentPath = parentElement?.takeIf { !it.isJsonNull }?.asString ?: "",
                            dirs = dirs
                        )
                    )
                } else {
                    uiState = uiState.copy(
                        codexWorkspaceBrowserState = uiState.codexWorkspaceBrowserState.copy(
                            isLoading = false,
                            error = "加载失败: 服务器响应异常 (Code: $code)"
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Codex workspace directory load failed", e)
                uiState = uiState.copy(
                    codexWorkspaceBrowserState = uiState.codexWorkspaceBrowserState.copy(
                        isLoading = false,
                        error = e.message ?: "未知错误"
                    )
                )
            }
        }
    }

    fun createCodexWorkspaceDirectory(hostUrl: String, parentPath: String, folderName: String) {
        viewModelScope.launch {
            try {
                val cleanParent = if (parentPath.endsWith("/")) parentPath.dropLast(1) else parentPath
                val newDirPath = "$cleanParent/$folderName"
                val encodedPath = java.net.URLEncoder.encode(newDirPath, "UTF-8")
                withContext(Dispatchers.IO) {
                    val request = okhttp3.Request.Builder()
                        .url("$hostUrl/mkdir?path=$encodedPath")
                        .build()
                    httpClient.newCall(request).execute().close()
                }
                loadCodexWorkspaceDirectory(hostUrl, parentPath)
            } catch (e: Exception) {
                uiState = uiState.copy(
                    codexWorkspaceBrowserState = uiState.codexWorkspaceBrowserState.copy(
                        error = "创建文件夹失败: ${e.message}"
                    )
                )
            }
        }
    }

    fun fetchCodexProjects() {
        if ((!isCodex && !isAntigravity) || codexCommands == null) return
        uiState = uiState.copy(codexProjectsLoading = true)
        viewModelScope.launch {
            val result = codexCommands!!.listProjects()
            when (result) {
                is CdpResult.Success -> {
                    val current = result.data.firstOrNull { it.isCurrent }?.name ?: ""
                    uiState = uiState.copy(
                        codexProjects = result.data,
                        codexProjectsLoading = false,
                        codexCurrentProject = current
                    )
                }
                is CdpResult.Error -> {
                    uiState = uiState.copy(codexProjectsLoading = false)
                    addSystemMessage("获取项目列表失败: ${result.message}")
                }
            }
        }
    }

    fun switchCodexProject(projectName: String) {
        if ((!isCodex && !isAntigravity) || codexCommands == null) return
        viewModelScope.launch {
            val result = codexCommands!!.switchProject(projectName)
            when (result) {
                is CdpResult.Success -> {
                    uiState = uiState.copy(codexCurrentProject = projectName)
                    addSystemMessage("已切换到项目: $projectName 📁")
                }
                is CdpResult.Error -> addSystemMessage("切换项目失败: ${result.message}")
            }
        }
    }

    fun startNewChatInProject(projectName: String) {
        if ((!isCodex && !isAntigravity) || codexCommands == null) return
        viewModelScope.launch {
            val result = codexCommands!!.startNewChatInProject(projectName)
            when (result) {
                is CdpResult.Success -> addSystemMessage("已在 $projectName 中新建聊天 🆕")
                is CdpResult.Error -> addSystemMessage("新建聊天失败: ${result.message}")
            }
        }
    }

    fun addCodexProject(path: String) {
        if (!isCodex && !isAntigravity) return
        val hostUrl = connectHost?.httpUrl
        if (hostUrl == null) {
            addSystemMessage("添加项目失败: 未连接 Relay")
            return
        }
        val cleanPath = path.trim()
        if (cleanPath.isBlank()) {
            addSystemMessage("添加项目失败: 路径不能为空")
            return
        }
        viewModelScope.launch {
            uiState = uiState.copy(codexProjectAdding = true)
            try {
                val cdpPort = currentCdpPort()
                val encodedPath = java.net.URLEncoder.encode(cleanPath, "UTF-8")
                val (ok, body, code) = withContext(Dispatchers.IO) {
                    val request = okhttp3.Request.Builder()
                        .url("$hostUrl/codex/workspace/add?port=$cdpPort&cwd=$encodedPath&activate=true&reload=true")
                        .build()
                    val res = httpClient.newCall(request).execute()
                    val b = res.body?.string()
                    val success = res.isSuccessful
                    val c = res.code
                    res.close()
                    Triple(success, b, c)
                }
                if (!ok || body == null) {
                    addSystemMessage("添加项目失败: Relay HTTP $code")
                    return@launch
                }
                val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                val success = json.get("success")?.asBoolean ?: false
                if (!success) {
                    val error = json.get("error")?.asString ?: "未知错误"
                    addSystemMessage("添加项目失败: $error")
                    return@launch
                }
                val expectedNames = listOf(
                    File(cleanPath).name,
                    cleanPath
                ).filter { it.isNotBlank() }.toSet()
                var found = false
                var foundProjectName = ""
                for (attempt in 0 until 6) {
                    delay(if (attempt == 0) 700 else 900)
                    val listResult = codexCommands?.listProjects()
                    if (listResult is CdpResult.Success) {
                        val current = listResult.data.firstOrNull { it.isCurrent }?.name ?: ""
                        uiState = uiState.copy(
                            codexProjects = listResult.data,
                            codexProjectsLoading = false,
                            codexCurrentProject = current
                        )
                        foundProjectName = listResult.data.firstOrNull { it.name in expectedNames }?.name ?: ""
                        found = foundProjectName.isNotBlank()
                        if (found) break
                    }
                }
                if (foundProjectName.isNotBlank()) {
                    when (val switchResult = codexCommands?.switchProject(foundProjectName)) {
                        is CdpResult.Success -> uiState = uiState.copy(codexCurrentProject = foundProjectName)
                        is CdpResult.Error -> addSystemMessage("已添加但切换项目失败: ${switchResult.message}")
                        null -> addSystemMessage("已添加但切换项目失败: Codex 未连接")
                    }
                }
                addSystemMessage(
                    if (found) "已添加 Codex 项目: $cleanPath 📁"
                    else "Codex 已接收项目: $cleanPath；列表刷新可能稍后完成"
                )
            } catch (e: Exception) {
                addSystemMessage("添加项目失败: ${e.message}")
            } finally {
                uiState = uiState.copy(
                    codexProjectAdding = false,
                    codexWorkspaceBrowserState = uiState.codexWorkspaceBrowserState.copy(isOpen = false)
                )
            }
        }
    }

    private fun currentCdpPort(): Int {
        return Regex("/cdp/(\\d+)").find(connectWsUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex(":(\\d+)/devtools").find(connectWsUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: 9666
    }

    fun closeProjectDialog() {
        uiState = uiState.copy(codexProjects = emptyList(), codexProjectsLoading = false)
    }

    /**
     * 查看 IDE 用量面板：Codex 打开 Rate limits，Windsurf 打开 Plan Info，
     * Antigravity 打开 Settings → Models 并读取 quota target。
     */
    fun checkRateLimits() {
        if ((!isCodex || codexCommands == null) &&
            (!isWindsurf || commands !is WindsurfCommands) &&
            (!isAntigravity || commands == null)
        ) return
        viewModelScope.launch {
            val result = when {
                isCodex -> codexCommands!!.showRateLimits()
                isWindsurf -> (commands as WindsurfCommands).showUsagePanel()
                isAntigravity -> showAntigravityUsagePanel()
                else -> CdpResult.Error("当前 IDE 不支持查看用量")
            }
            when (result) {
                is CdpResult.Success -> addSystemMessage("用量面板已打开 📊 ${result.data}")
                is CdpResult.Error -> addSystemMessage("查看用量失败: ${result.message}")
            }
        }
    }

    private suspend fun showAntigravityUsagePanel(): CdpResult<String> {
        val detail = commands?.showUsagePanel()
            ?: return CdpResult.Error("Antigravity 命令未初始化")
        if (detail is CdpResult.Error) return CdpResult.Error(detail.message)

        return try {
            val jsonStr = detail.getOrNull() ?: return CdpResult.Error("无返回结果")
            val json = com.google.gson.JsonParser.parseString(jsonStr).asJsonObject
            if (json.get("ok")?.asBoolean != true) {
                return CdpResult.Error(json.get("error")?.asString ?: "未读取到 Antigravity 用量")
            }

            val parts = mutableListOf<String>()
            json.get("credits")?.asString?.takeIf { it.isNotBlank() }?.let {
                parts.add("AI 点数: $it")
            }
            val allQuotaRows = json.getAsJsonArray("rows")?.mapNotNull { elem ->
                val row = elem.asJsonObject
                val name = row.get("name")?.asString.orEmpty()
                if (name.isBlank()) return@mapNotNull null
                row
            }.orEmpty()
            val selectedRows = allQuotaRows.filter { row ->
                val normalized = row.get("name")?.asString.orEmpty()
                    .lowercase()
                    .replace(Regex("[^a-z0-9.]+"), " ")
                    .trim()
                val isGeminiHigh = normalized.contains("gemini") &&
                    (normalized.contains("3.1") || normalized.contains("3.5")) &&
                    normalized.contains("pro") &&
                    normalized.contains("high")
                val isFlashHigh = normalized.contains("gemini") &&
                    normalized.contains("3.5") &&
                    normalized.contains("flash") &&
                    normalized.contains("high")
                val isOpus = normalized.contains("claude") && normalized.contains("opus")
                val isSonnet = normalized.contains("claude") && normalized.contains("sonnet")
                isGeminiHigh || isFlashHigh || isOpus || isSonnet
            }.ifEmpty {
                allQuotaRows
            }
            val rows = selectedRows.mapNotNull { row ->
                val name = row.get("name")?.asString.orEmpty()
                if (name.isBlank()) return@mapNotNull null
                val remaining = row.get("remaining")?.asString.orEmpty()
                val reset = row.get("reset")?.asString
                    ?.replace("Refreshes in", "刷新")
                    ?.replace("hours", "小时")
                    ?.replace("hour", "小时")
                    ?.replace("minutes", "分钟")
                    ?.replace("minute", "分钟")
                    .orEmpty()
                buildString {
                    append(name)
                    if (remaining.isNotBlank()) append(" 剩余 ").append(remaining)
                    if (reset.isNotBlank()) append("，").append(reset)
                }
            }
            if (rows.isNotEmpty()) parts.add("模型额度: ${rows.joinToString("；")}")
            CdpResult.Success(parts.joinToString(" · ").ifBlank { "Antigravity Models" })
        } catch (e: Exception) {
            CdpResult.Error("读取 Antigravity 用量失败: ${e.message}")
        }
    }



    fun stopGeneration() {
        pollJob?.cancel()
        uiState = uiState.copy(isGenerating = false, isSendingMessage = false)
        viewModelScope.launch {
            if (!hasCommands()) return@launch
            when {
                isClaudeCode -> claudeCodeCommands!!.stopGeneration()
                isCodex -> codexCommands!!.stopGeneration()
                isUitty -> uittyCommands!!.stopGeneration()
                else -> commands!!.stopGeneration()
            }
            addSystemMessage("已停止生成 ⏹")
        }
    }

    /**
     * 取消 Windsurf 中正在运行的长时间任务（如 Step、Tool 执行）
     * 仅在 Windsurf IDE 中有效
     */
    fun cancelRunningTask() {
        viewModelScope.launch {
            val cmds = commands
            if (cmds == null || !isWindsurf) {
                addSystemMessage("取消任务仅在 Windsurf IDE 中可用")
                return@launch
            }
            val result = cmds.cancelRunningTask()
            when (result) {
                is CdpResult.Success -> {
                    addSystemMessage("已取消运行中的任务 ⛔")
                    // 等待 Windsurf UI 响应取消操作
                    delay(500)
                    // 取消后如果仍在生成状态，同步重置
                    if (uiState.isGenerating) {
                        pollJob?.cancel()
                        uiState = uiState.copy(isGenerating = false)
                    }
                }
                is CdpResult.Error -> addSystemMessage("取消任务失败: ${result.message}")
            }
        }
    }

    fun acceptAll() {
        viewModelScope.launch {
            if (!hasCommands()) return@launch
            val result = when {
                isClaudeCode -> claudeCodeCommands!!.acceptAll()
                isCodex -> codexCommands!!.acceptAll()
                isUitty -> uittyCommands!!.acceptAll()
                else -> commands!!.acceptAll()
            }
            when (result) {
                is CdpResult.Success -> addSystemMessage("已接收所有变更 ✅")
                is CdpResult.Error -> addSystemMessage("接收失败: ${result.message}")
            }
        }
    }

    fun rejectAll() {
        viewModelScope.launch {
            if (!hasCommands()) return@launch
            val result = when {
                isClaudeCode -> claudeCodeCommands!!.rejectAll()
                isCodex -> codexCommands!!.rejectAll()
                isUitty -> uittyCommands!!.rejectAll()
                else -> commands!!.rejectAll()
            }
            when (result) {
                is CdpResult.Success -> addSystemMessage("已拒绝变更 ❌")
                is CdpResult.Error -> addSystemMessage("拒绝失败: ${result.message}")
            }
        }
    }

    suspend fun loadUittyGlobalRulesText(kind: String): CdpResult<String> {
        if (!isUitty || uittyCommands == null) {
            return CdpResult.Error("当前不是 uitty 或未连接")
        }
        return uittyCommands!!.readGlobalRules(kind)
    }

    /**
     * 保存全局规则；**成功返回 true** 后 UI 再关弹窗，避免写入未完成时立刻重开读到空文件。
     * [uittyRulesKind] 仅 uitty：`claude` | `opencode`。
     */
    suspend fun saveGlobalAgentRuleFromDialog(text: String, uittyRulesKind: String? = null): Boolean {
        if (isCodex) {
            addSystemMessage("Codex 无侧栏全局规则，已跳过 ❌")
            return false
        }
        if (!hasCommands()) {
            addSystemMessage("未连接 IDE ❌")
            return false
        }
        if (isUitty && uittyCommands != null) {
            val kind = uittyRulesKind?.trim()?.takeIf { it.isNotEmpty() } ?: "claude"
            addSystemMessage("正在写入 uitty 全局规则（$kind）…")
            return when (val result = uittyCommands!!.writeGlobalRules(kind, text)) {
                is CdpResult.Success -> {
                    addSystemMessage("全局规则已保存（$kind）✓")
                    true
                }
                is CdpResult.Error -> {
                    addSystemMessage("全局规则失败: ${result.message} ❌")
                    false
                }
            }
        }
        addSystemMessage("正在写入全局规则（远程 CDP）…")
        return when (val result = commands!!.setGlobalAgentRule(text)) {
            is CdpResult.Success -> {
                addSystemMessage("全局规则已保存 ✓")
                true
            }
            is CdpResult.Error -> {
                addSystemMessage("全局规则失败: ${result.message} ❌")
                false
            }
        }
    }

    fun switchModel(modelName: String) {
        viewModelScope.launch {
            if (!hasCommands()) return@launch
            val maxRetries = 3
            var lastError = ""
            for (attempt in 1..maxRetries) {
                val result = when {
                    isClaudeCode -> claudeCodeCommands!!.switchModel(modelName)
                    isCodex -> codexCommands!!.switchModel(modelName)
                    isUitty -> uittyCommands!!.switchModel(modelName)
                    else -> commands!!.switchModel(modelName)
                }
                when (result) {
                    is CdpResult.Success -> {
                        uiState = uiState.copy(currentModel = modelName)
                        if (attempt > 1) {
                            addSystemMessage("已切换模型: $modelName ⚡ (第${attempt}次尝试)")
                        } else {
                            addSystemMessage("已切换模型: $modelName ⚡")
                        }
                        return@launch
                    }
                    is CdpResult.Error -> {
                        lastError = result.message
                        Log.w(TAG, "切换模型第${attempt}次失败: $lastError")
                        if (attempt < maxRetries) {
                            delay(800L * attempt)
                        }
                    }
                }
            }
            addSystemMessage("切换模型失败(已重试${maxRetries}次): $lastError")
        }
    }

    // ─── Screenshot ─────────────────────────────────────────────────

    fun takeScreenshot() {
        viewModelScope.launch {
            val result = cdpClient.captureScreenshot()
            when (result) {
                is CdpResult.Success -> {
                    uiState = uiState.copy(lastScreenshot = result.data)
                    addSystemMessage("截图完成 📸")
                }
                is CdpResult.Error -> addSystemMessage("截图失败: ${result.message}")
            }
        }
    }

    // ─── Scroll ─────────────────────────────────────────────────────

    // ─── Scroll ─────────────────────────────────────────────────────

    private suspend fun getTargetCoords(): Pair<Double, Double> {
        val script = """
            (function() {
                try {
                    function docs(d) {
                        var out = [d];
                        var ifr = d.querySelectorAll('iframe');
                        for (var i = 0; i < ifr.length; i++) {
                            try { if(ifr[i].contentDocument) out.push(ifr[i].contentDocument); } catch(e){}
                        }
                        return out;
                    }
                    var all = docs(document);
                    for (var di = 0; di < all.length; di++) {
                        var doc = all[di];
                        var p = doc.querySelector('.antigravity-agent-side-panel')
                          || doc.querySelector('.conversations')
                          || doc.querySelector('.composer-bar:not(.empty)')
                          || doc.querySelector('[class*="interactive-session"]')
                          || doc.querySelector('[class*="aichat"]')
                          || doc.querySelector('[class*="composer"]')
                          || doc.querySelector('[class*="chat-view"]')
                          || doc.querySelector('[class*="cascade-scrollbar"]')
                          || doc.querySelector('[class*="chat-client-root"]')
                          || doc.querySelector('.chat-body');
                          
                        if (p && p.getBoundingClientRect) {
                            var rect = p.getBoundingClientRect();
                            if (rect.width > 0 && rect.height > 0) {
                                return (rect.left + rect.width / 2) + ',' + (rect.top + rect.height / 2);
                            }
                        }
                    }
                    // Fallback to center-right area (typical chat panel location)
                    var w = window.innerWidth || 1920;
                    var h = window.innerHeight || 1080;
                    return (w * 0.8) + ',' + (h * 0.5);
                } catch(e) {
                    return '960,540';
                }
            })();
        """.trimIndent()
        
        val result = cdpClient.evaluate(script)
        if (result is CdpResult.Success) {
            val coordsStr = result.data ?: return Pair(960.0, 540.0)
            val parts = coordsStr.split(",")
            if (parts.size == 2) {
                return Pair(parts[0].toDoubleOrNull() ?: 960.0, parts[1].toDoubleOrNull() ?: 540.0)
            }
        }
        return Pair(960.0, 540.0)
    }

    private suspend fun scrollWithMouse(direction: String) {
        val (x, y) = getTargetCoords()
        val deltaY = if (direction == "up") -600.0 else 600.0

        // 1. Click to focus the chat area first (required for Cursor's Monaco virtual scroll)
        cdpClient.dispatchMouseEvent("mouseMoved", x, y, button = "none", clickCount = 0)
        delay(30)
        cdpClient.dispatchMouseEvent("mousePressed", x, y, button = "left", clickCount = 1)
        delay(30)
        cdpClient.dispatchMouseEvent("mouseReleased", x, y, button = "left", clickCount = 1)
        delay(80)

        // 2. Dispatch native mouse wheel event
        cdpClient.dispatchScrollEvent(deltaY, x, y)
    }

    fun scrollUp() {
        viewModelScope.launch { scrollWithMouse("up") }
    }

    fun scrollDown() {
        viewModelScope.launch { scrollWithMouse("down") }
    }

    // ─── Click ──────────────────────────────────────────────────────

    suspend fun clickAt(x: Double, y: Double) {
        cdpClient.dispatchMouseEvent("mousePressed", x, y)
        delay(50)
        cdpClient.dispatchMouseEvent("mouseReleased", x, y)
    }

    suspend fun getRightPanelCoords(): Pair<Double, Double>? {
        // This would require a JS evaluation to get panel coordinates
        // For now return center-right of screen
        return Pair(1600.0, 500.0)
    }

    // ─── TV Mode ────────────────────────────────────────────────────

    fun toggleTvMode() {
        val newMode = !uiState.tvMode
        uiState = uiState.copy(tvMode = newMode)
        if (newMode) {
            startTvMode()
        } else {
            stopTvMode()
        }
    }

    private fun startTvMode() {
        tvJob?.cancel()
        tvJob = viewModelScope.launch {
            while (isActive && uiState.tvMode) {
                try {
                    var electronScreenshot: ByteArray? = null
                    if (uiState.appName.lowercase().contains("dsme")) {
                        val electronCapture = cdpClient.evaluate("window.electronAPI && window.electronAPI.captureWindow ? window.electronAPI.captureWindow() : null", awaitPromise = true)
                        if (electronCapture is CdpResult.Success && !electronCapture.data.isNullOrBlank() && electronCapture.data != "null") {
                            val dataUrl = electronCapture.data.removeSurrounding("\"")
                            if (dataUrl.startsWith("data:image")) {
                                val base64 = dataUrl.substringAfter(",")
                                electronScreenshot = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                            }
                        }
                    }

                    if (electronScreenshot != null) {
                        uiState = uiState.copy(
                            tvFrame = electronScreenshot,
                            tvBytesTotal = uiState.tvBytesTotal + electronScreenshot.size,
                            tvFrameCount = uiState.tvFrameCount + 1
                        )
                    } else {
                        val result = cdpClient.captureScreenshot(uiState.tvQuality)
                        if (result is CdpResult.Success) {
                            uiState = uiState.copy(
                                tvFrame = result.data,
                                tvBytesTotal = uiState.tvBytesTotal + result.data.size,
                                tvFrameCount = uiState.tvFrameCount + 1
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "TV 截图失败: ${e.message}")
                }
                delay(uiState.tvIntervalMs)
            }
        }
    }

    private fun stopTvMode() {
        tvJob?.cancel()
        tvJob = null
    }

    fun setTvSettings(quality: Int, intervalMs: Long) {
        uiState = uiState.copy(tvQuality = quality, tvIntervalMs = intervalMs)
    }

    fun setTvFocusChat(focus: Boolean) {
        uiState = uiState.copy(tvFocusChat = focus)
    }

    /** 切换 TV 操控模式（观影 ↔ 操控） */
    fun toggleTvControlMode() {
        val newMode = !uiState.tvControlMode
        uiState = uiState.copy(tvControlMode = newMode)
        if (newMode) {
            // 进入操控模式时，获取远端页面尺寸用于坐标映射（带重试）
            viewModelScope.launch {
                var fetched = false
                for (attempt in 1..3) {
                    fetchPageDimensionsSync()
                    if (uiState.tvPageWidth > 0 && uiState.tvPageHeight > 0) {
                        fetched = true
                        break
                    }
                    delay(500L * attempt)
                }
                if (!fetched) {
                    addSystemMessage("⚠️ 无法获取远端页面尺寸，操控模式可能无法正常工作")
                }
            }
        }
    }

    /** 获取远端 IDE 页面的实际像素尺寸（同步版本，供 coroutine 内调用）— 多策略 fallback */
    private suspend fun fetchPageDimensionsSync() {
        // 策略 1: window.innerWidth/innerHeight
        try {
            val script = "(function(){ return JSON.stringify({w:window.innerWidth,h:window.innerHeight,dw:document.documentElement.clientWidth,dh:document.documentElement.clientHeight,sw:screen.width,sh:screen.height}); })()"
            val result = cdpClient.evaluate(script)
            if (result is CdpResult.Success && result.data != null) {
                Log.d(TAG, "页面尺寸原始: ${result.data}")
                try {
                    val json = com.google.gson.JsonParser.parseString(result.data).asJsonObject
                    // 优先用 window.innerWidth（完整视口）
                    var w = json.get("w")?.asInt ?: 0
                    var h = json.get("h")?.asInt ?: 0
                    // fallback: document.documentElement.clientWidth
                    if (w <= 0 || h <= 0) {
                        w = json.get("dw")?.asInt ?: 0
                        h = json.get("dh")?.asInt ?: 0
                    }
                    // fallback: screen 尺寸
                    if (w <= 0 || h <= 0) {
                        w = json.get("sw")?.asInt ?: 0
                        h = json.get("sh")?.asInt ?: 0
                    }
                    if (w > 0 && h > 0) {
                        uiState = uiState.copy(tvPageWidth = w, tvPageHeight = h)
                        Log.d(TAG, "远端页面尺寸: ${w}x${h}")
                        return
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "解析页面尺寸 JSON 失败: ${e.message}")
                }
            } else {
                Log.w(TAG, "evaluate 获取尺寸失败: ${(result as? CdpResult.Error)?.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "策略1异常: ${e.message}")
        }

        // 策略 2: CDP Page.getLayoutMetrics
        try {
            val metricsResult = cdpClient.call("Page.getLayoutMetrics", com.google.gson.JsonObject())
            if (metricsResult is CdpResult.Success) {
                val metrics = metricsResult.data
                val cssVisual = metrics.getAsJsonObject("cssVisualViewport")
                if (cssVisual != null) {
                    val w = cssVisual.get("clientWidth")?.asInt ?: 0
                    val h = cssVisual.get("clientHeight")?.asInt ?: 0
                    if (w > 0 && h > 0) {
                        uiState = uiState.copy(tvPageWidth = w, tvPageHeight = h)
                        Log.d(TAG, "远端页面尺寸 (LayoutMetrics): ${w}x${h}")
                        return
                    }
                }
                Log.d(TAG, "LayoutMetrics 原始: $metrics")
            }
        } catch (e: Exception) {
            Log.w(TAG, "策略2异常: ${e.message}")
        }

        Log.e(TAG, "所有策略均无法获取页面尺寸")
    }

    /** 获取远端 IDE 页面的实际像素尺寸（异步版本，用于后台自动调用） */
    private fun fetchPageDimensions() {
        viewModelScope.launch { fetchPageDimensionsSync() }
    }

    /**
     * 从 TV 画面的触摸事件派发 CDP 远程输入。
     *
     * 坐标映射逻辑：
     * Android 端触摸坐标 (touchX, touchY) 是相对于 Image 组件的归一化比例 [0..1]，
     * 乘以远端页面实际像素尺寸 (tvPageWidth, tvPageHeight) 得到 IDE 内的绝对坐标。
     *
     * @param type     CDP 鼠标事件类型: "mousePressed", "mouseReleased", "mouseMoved"
     * @param ratioX   触摸点在图片上的水平比例 [0..1]
     * @param ratioY   触摸点在图片上的垂直比例 [0..1]
     * @param button   鼠标按钮: "left", "right", "middle"
     */
    fun dispatchRemoteInput(type: String, ratioX: Float, ratioY: Float, button: String = "left") {
        viewModelScope.launch {
            remoteInputMutex.withLock {
                var pw = uiState.tvPageWidth
                var ph = uiState.tvPageHeight
                if (pw <= 0 || ph <= 0) {
                    // 尺寸未就绪，同步重新获取一次再继续
                    fetchPageDimensionsSync()
                    pw = uiState.tvPageWidth
                    ph = uiState.tvPageHeight
                    if (pw <= 0 || ph <= 0) return@withLock
                }
                val x = (ratioX.coerceIn(0f, 1f) * pw).toDouble()
                val y = (ratioY.coerceIn(0f, 1f) * ph).toDouble()
                val clickCount = if (type == "mousePressed") 1 else 0
                cdpClient.dispatchMouseEvent(type, x, y, button, clickCount)
            }
        }
    }

    /**
     * 从 TV 画面派发滚轮事件。
     *
     * @param ratioX  触摸点水平比例 [0..1]
     * @param ratioY  触摸点垂直比例 [0..1]
     * @param deltaY  滚动距离（正数向下，负数向上）
     */
    fun dispatchRemoteScroll(ratioX: Float, ratioY: Float, deltaY: Float) {
        val pw = uiState.tvPageWidth
        val ph = uiState.tvPageHeight
        if (pw <= 0 || ph <= 0) return
        val x = (ratioX.coerceIn(0f, 1f) * pw).toDouble()
        val y = (ratioY.coerceIn(0f, 1f) * ph).toDouble()

        viewModelScope.launch {
            cdpClient.dispatchScrollEvent(deltaY.toDouble(), x, y)
        }
    }

    fun dispatchRemoteText(text: String) {
        viewModelScope.launch {
            cdpClient.insertText(text)
        }
    }

    fun dispatchRemoteKey(type: String, key: String) {
        viewModelScope.launch {
            cdpClient.dispatchKeyEvent(type, key)
        }
    }

    // ─── Utility ────────────────────────────────────────────────────

    fun prepareModelSwitchDialog() {
        val host = connectHost
        val cdpPort = extractCdpPort(connectWsUrl)
        if (host == null || cdpPort <= 0) {
            uiState = uiState.copy(ideModelOptionsLoading = false, ideModelOptions = emptyList())
            return
        }

        uiState = uiState.copy(ideModelOptionsLoading = true, ideModelOptions = emptyList())
        viewModelScope.launch {
            try {
                val url = "http://${host.ip}:${host.port}/scheduler/models" +
                    "?port=$cdpPort&ide=${java.net.URLEncoder.encode(uiState.appName, "UTF-8")}"
                val models = withContext(Dispatchers.IO) {
                    val request = okhttp3.Request.Builder().url(url).build()
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext emptyList<String>()
                        parseModelOptionsResponse(response.body?.string() ?: "{}")
                    }
                }
                uiState = uiState.copy(
                    ideModelOptionsLoading = false,
                    ideModelOptions = models
                )
            } catch (e: Exception) {
                Log.w(TAG, "读取模型列表失败: ${e.message}")
                uiState = uiState.copy(ideModelOptionsLoading = false, ideModelOptions = emptyList())
            }
        }
    }

    fun onModelSwitchDialogClosed() {
        uiState = uiState.copy(ideModelOptionsLoading = false)
    }

    private fun extractCdpPort(wsUrl: String): Int {
        Regex("/cdp/(\\d+)/").find(wsUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return Regex("://[^:/]+:(\\d+)/").find(wsUrl)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun parseModelOptionsResponse(json: String): List<String> {
        return try {
            val obj = JSONObject(json)
            if (obj.has("success") && !obj.optBoolean("success", true)) return emptyList()
            val arr = obj.optJSONArray("models") ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optString(i).trim()
                if (item.isNotEmpty() && !out.contains(item)) out += item
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun dismissPendingOta() {
        uiState = uiState.copy(pendingOta = null)
    }

    fun clearError() {
        uiState = uiState.copy(error = null)
    }

    fun updateError(msg: String) {
        uiState = uiState.copy(error = msg)
    }

    fun addSystemMessage(text: String) {
        val msg = ChatMessage(role = MessageRole.SYSTEM, content = text)
        uiState = uiState.copy(messages = uiState.messages + msg)
    }

    fun stopSync() {
        pollJob?.cancel()
        tvJob?.cancel()
        reconnectJob?.cancel()
        errorWatchdogJob?.cancel()
    }

    private fun startErrorWatchdog() {
        errorWatchdogJob?.cancel()
        errorWatchdogJob = viewModelScope.launch {
            while (isActive) {
                delay(8000)
                // 只在非 Antigravity 连接中跳过
                if (isCodex || isWindsurf) continue
                // 轮询已在管的时候跳过
                if (uiState.isGenerating) continue
                // 未连接时跳过
                if (uiState.connectionState != ConnectionState.CONNECTED) continue
                if (commands == null) continue

                try {
                    val retryResult = commands!!.checkAndRetryIfBusy()
                    if (retryResult is CdpResult.Success && retryResult.data) {
                        addSystemMessage("看门狗检测到错误，已自动重试 🔄")
                        // 触发后启动轮询跟踪
                        uiState = uiState.copy(isGenerating = true)
                        lastReplyText = ""
                        pollCount = 0
                        startBackgroundSync()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "看门狗检查异常: ${e.message}")
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        saveCurrentDraft()
        stopSync()
        cdpClient.disconnect()
    }
}
