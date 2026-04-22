package com.cdp.remote.presentation.screen.chat

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cdp.remote.data.cdp.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

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
    private var isCodex: Boolean = false
    private var isWindsurf: Boolean = false
    private var connectHost: HostInfo? = null
    private var connectWsUrl: String = ""
    private var lastReplyText: String = ""
    private var pollJob: Job? = null
    private var pollCount: Int = 0
    private var tvJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts: Int = 0

    // ─── Connection ─────────────────────────────────────────────────

    private val httpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
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

                    val root = com.google.gson.JsonParser.parseString(body).asJsonObject
                    val targetsArray = root.getAsJsonArray("targets") ?: return@withContext emptyList<CdpPage>()

                    val allPages = mutableListOf<CdpPage>()
                    for (targetElem in targetsArray) {
                        val target = targetElem.asJsonObject
                        val pagesArray = target.getAsJsonArray("pages") ?: continue
                        for (pageElem in pagesArray) {
                            val obj = pageElem.asJsonObject
                            allPages.add(CdpPage(
                                id = obj.get("id")?.asString ?: "",
                                type = obj.get("type")?.asString ?: "",
                                title = obj.get("title")?.asString ?: "",
                                url = obj.get("url")?.asString ?: "",
                                webSocketDebuggerUrl = obj.get("webSocketDebuggerUrl")?.asString ?: "",
                                devtoolsFrontendUrl = obj.get("devtoolsFrontendUrl")?.asString ?: ""
                            ))
                        }
                    }
                    allPages
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
        uiState = uiState.copy(connectionState = ConnectionState.CONNECTING, appName = appName)
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
                val n = appName.lowercase()
                isCodex = n.contains("codex")
                isWindsurf = n.contains("windsurf")
                val isCursor = n.contains("cursor")
                if (isCodex) {
                    codexCommands = CodexCommands(cdpClient)
                    commands = null
                } else if (isWindsurf) {
                    commands = WindsurfCommands(cdpClient)
                    codexCommands = null
                } else {
                    // Cursor 走 CursorCommands（继承自 AntigravityCommands，仅覆写 switchModel）；
                    // 其它 IDE 保留反重力实现，行为保持不变。
                    commands = if (isCursor) CursorCommands(cdpClient, appName)
                               else AntigravityCommands(cdpClient, appName)
                    codexCommands = null
                }
                uiState = uiState.copy(connectionState = ConnectionState.CONNECTED, error = null)
                reconnectAttempts = 0
                addSystemMessage("已连接到 $appName")
                fetchAvailableApps(host.ip, host.port)
                startBackgroundSync()
                startTvMode()
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
    }

    fun sendMessage() {
        val text = uiState.inputText.trim()
        val images = uiState.pendingImages
        if (text.isBlank() && images.isEmpty()) return

        val imgLabel = if (images.isNotEmpty()) "📷 [${images.size}张图片] " else ""
        val displayText = "$imgLabel$text".trim()
        val userMsg = ChatMessage(role = MessageRole.USER, content = displayText)
        val msgs = uiState.messages + userMsg
        uiState = uiState.copy(messages = msgs, inputText = "", isGenerating = true, error = null)

        if (!hasCommands()) { updateError("未连接"); return }

        // Snapshot and clear pending images
        val imagesToSend = images.toList()
        uiState = uiState.copy(pendingImages = emptyList(), pendingImageBase64 = null, pendingImageMimeType = null)

        viewModelScope.launch {
            // 图片+文字组合发送：先粘贴所有图片（不发送），再输入文字后一起发送
            if (imagesToSend.isNotEmpty() && text.isNotEmpty()) {
                // Step 1: Paste images only (no Enter)
                for ((index, img) in imagesToSend.withIndex()) {
                    val pasteResult = when {
                        isCodex -> codexCommands!!.pasteImage(img.base64, img.mimeType)
                        else -> commands!!.pasteImage(img.base64, img.mimeType)
                    }
                    if (pasteResult is CdpResult.Error) {
                        addSystemMessage("第${index + 1}张图片粘贴失败: ${pasteResult.message} ❌")
                    }
                    delay(500) // 等待 UI 渲染图片预览
                }

                // Step 2: Send text (types text + presses Enter, sending image+text together)
                val result = when {
                    isCodex -> codexCommands!!.sendMessage(text)
                    else -> commands!!.sendMessage(text)
                }
                if (result is CdpResult.Error) {
                    uiState = uiState.copy(error = "发送失败: ${result.message}")
                    addSystemMessage("发送失败: ${result.message} ❌")
                }
            } else if (imagesToSend.isNotEmpty()) {
                // 只有图片没有文字：用 sendImage（粘贴 + 发送）
                for ((index, img) in imagesToSend.withIndex()) {
                    val imgResult = when {
                        isCodex -> codexCommands!!.sendImage(img.base64, img.mimeType)
                        else -> commands!!.sendImage(img.base64, img.mimeType)
                    }
                    if (imgResult is CdpResult.Error) {
                        addSystemMessage("第${index + 1}张图片发送失败: ${imgResult.message} ❌")
                    }
                }
            } else if (text.isNotEmpty()) {
                // 只有文字没有图片
                val result = when {
                    isCodex -> codexCommands!!.sendMessage(text)
                    else -> commands!!.sendMessage(text)
                }
                if (result is CdpResult.Error) {
                    uiState = uiState.copy(error = "发送失败: ${result.message}")
                    addSystemMessage("发送失败: ${result.message} ❌")
                }
            }

            // Start background sync after message send
            lastReplyText = ""
            pollCount = 0
            startBackgroundSync()
        }
    }

    private fun hasCommands(): Boolean = when {
        isCodex -> codexCommands != null
        else -> commands != null
    }

    private fun startBackgroundSync() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive && uiState.isGenerating) {
                delay(1500)
                if (!hasCommands()) break
                val result = when {
                    isCodex -> codexCommands!!.getLastReply()
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
                    if (!isCodex && !isWindsurf && pollCount > 0 && pollCount % 5 == 0) {
                        val midRetry = commands!!.checkAndRetryIfBusy()
                        if (midRetry is CdpResult.Success && midRetry.data) {
                            addSystemMessage("检测到错误，已自动重试 🔄")
                            pollCount = 0
                            delay(2000)
                            continue
                        }
                    }

                    val isStillGenerating = when {
                        isCodex -> codexCommands!!.isGenerating()
                        else -> commands!!.isGenerating()
                    }

                    // 自动放行 Agent 的 Action 确认按钮（Run/Allow/Approve 等）
                    if (isStillGenerating) {
                        val accepted = when {
                            isCodex -> codexCommands!!.autoAcceptActions()
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
                        if (!isCodex && !isWindsurf) {
                            val retryResult = commands!!.checkAndRetryIfBusy()
                            if (retryResult is CdpResult.Success && retryResult.data) {
                                addSystemMessage("检测到错误，已自动重试 🔄")
                                pollCount = 0
                                delay(2000)
                                continue
                            }
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
        val img = PendingImage(
            base64 = base64Data,
            mimeType = mimeType
        )
        uiState = uiState.copy(
            pendingImages = uiState.pendingImages + img,
            // Keep legacy fields synced for backward compat
            pendingImageBase64 = base64Data,
            pendingImageMimeType = mimeType
        )
    }

    fun removeImage(imageId: Long) {
        val updated = uiState.pendingImages.filter { it.id != imageId }
        uiState = uiState.copy(
            pendingImages = updated,
            pendingImageBase64 = updated.lastOrNull()?.base64,
            pendingImageMimeType = updated.lastOrNull()?.mimeType
        )
    }

    fun clearPendingImage() {
        uiState = uiState.copy(
            pendingImages = emptyList(),
            pendingImageBase64 = null,
            pendingImageMimeType = null
        )
    }

    // ─── IDE Actions ────────────────────────────────────────────────

    fun startNewSession() {
        viewModelScope.launch {
            if (!hasCommands()) return@launch
            val result = when {
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
                isCodex -> codexCommands!!.showRecentSessions()
                else -> commands!!.showRecentSessions()
            }
            when (result) {
                is CdpResult.Success -> addSystemMessage("已打开历史会话 🕰")
                is CdpResult.Error -> addSystemMessage("打开历史失败: ${result.message}")
            }
        }
    }

    fun switchSession(isNext: Boolean) {
        viewModelScope.launch {
            if (!hasCommands()) return@launch
            val result = when {
                isCodex -> codexCommands!!.switchSession(isNext)
                else -> commands!!.switchSession(isNext)
            }
            when (result) {
                is CdpResult.Success -> addSystemMessage("已切换会话 🔄")
                is CdpResult.Error -> addSystemMessage("切换会话失败: ${result.message}")
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
                isCodex -> codexCommands!!.getRecentSessionsList()
                else -> commands!!.getRecentSessionsList()
            }
            when (result) {
                is CdpResult.Success -> {
                    uiState = uiState.copy(recentSessions = result.data, isSessionsLoading = false)
                }
                is CdpResult.Error -> {
                    uiState = uiState.copy(isSessionsLoading = false)
                    addSystemMessage("获取会话列表失败: ${result.message}")
                }
            }
        }
    }

    fun switchSessionByIndex(index: Int) {
        viewModelScope.launch {
            if (!hasCommands()) return@launch
            val result = when {
                isCodex -> codexCommands!!.switchSessionByIndex(index)
                else -> commands!!.switchSessionByIndex(index)
            }
            when (result) {
                is CdpResult.Success -> {
                    addSystemMessage("已切换到指定会话 🔄")
                    uiState = uiState.copy(recentSessions = emptyList()) // Close dialog
                }
                is CdpResult.Error -> addSystemMessage("切换会话失败: ${result.message}")
            }
        }
    }

    fun closeSessionDialog() {
        uiState = uiState.copy(recentSessions = emptyList(), isSessionsLoading = false)
    }

    fun stopGeneration() {
        pollJob?.cancel()
        uiState = uiState.copy(isGenerating = false)
        viewModelScope.launch {
            if (!hasCommands()) return@launch
            when {
                isCodex -> codexCommands!!.stopGeneration()
                else -> commands!!.stopGeneration()
            }
            addSystemMessage("已停止生成 ⏹")
        }
    }

    fun acceptAll() {
        viewModelScope.launch {
            if (!hasCommands()) return@launch
            val result = when {
                isCodex -> codexCommands!!.acceptAll()
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
                isCodex -> codexCommands!!.rejectAll()
                else -> commands!!.rejectAll()
            }
            when (result) {
                is CdpResult.Success -> addSystemMessage("已拒绝变更 ❌")
                is CdpResult.Error -> addSystemMessage("拒绝失败: ${result.message}")
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
                    isCodex -> codexCommands!!.switchModel(modelName)
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
        val deltaY = if (direction == "up") -300.0 else 300.0
        
        // 1. Move mouse to the calculated coordinates
        cdpClient.dispatchMouseEvent("mouseMoved", x, y, button = "none", clickCount = 0)
        delay(50)
        
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
                    val result = cdpClient.captureScreenshot(uiState.tvQuality)
                    if (result is CdpResult.Success) {
                        uiState = uiState.copy(
                            tvFrame = result.data,
                            tvBytesTotal = uiState.tvBytesTotal + result.data.size,
                            tvFrameCount = uiState.tvFrameCount + 1
                        )
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

    // ─── Utility ────────────────────────────────────────────────────

    fun prepareModelSwitchDialog() {
        // 标记正在加载模型列表
        uiState = uiState.copy(ideModelOptionsLoading = true)
    }

    fun onModelSwitchDialogClosed() {
        uiState = uiState.copy(ideModelOptionsLoading = false)
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
    }

    override fun onCleared() {
        super.onCleared()
        stopSync()
        cdpClient.disconnect()
    }
}
