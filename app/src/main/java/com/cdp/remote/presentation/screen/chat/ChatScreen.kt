package com.cdp.remote.presentation.screen.chat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cdp.remote.data.cdp.ConnectionState
import com.cdp.remote.data.cdp.ElectronAppType
import com.cdp.remote.data.cdp.RELAY_OTA_HTTP_PORT
import com.cdp.remote.data.ota.OtaUpdateManager
import com.cdp.remote.presentation.screen.chat.components.ActionToolbar
import com.cdp.remote.presentation.screen.chat.components.InputBar
import com.cdp.remote.presentation.screen.chat.components.MessageBubble
import com.cdp.remote.presentation.screen.chat.components.TvLiveView
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    hostIp: String,
    hostPort: Int,
    wsUrl: String,
    appName: String,
    onNavigateBack: () -> Unit,
    onSwitchApp: (hostIp: String, hostPort: Int, wsUrl: String, appName: String) -> Unit,
    viewModel: ChatViewModel = viewModel()
) {
    val state = viewModel.uiState
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val localPkg = remember {
        try {
            val p = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            Pair(p.versionName ?: "", p.versionCode)
        } catch (_: Exception) {
            Pair("?", 0)
        }
    }
    var pendingOtaDownloadUrl by remember { mutableStateOf<String?>(null) }
    val otaNotifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingOtaDownloadUrl?.let { url ->
            OtaUpdateManager(context, url).downloadAndInstallUpdate(url)
            pendingOtaDownloadUrl = null
        }
    }

    var showModelDialog by remember { mutableStateOf(false) }
    var showScreenshotDialog by remember { mutableStateOf(false) }
    var showSessionDialog by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightDp = configuration.screenHeightDp.dp
    var tvHeightDp by remember { mutableStateOf(screenHeightDp * 0.65f) }
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val showTv = state.tvMode && state.tvFrame != null
    val fullScreenTv = showTv && isLandscape

    // appName 必须参与 key：从反重力切到 Cursor 等同机多实例时 wsUrl 可能不变，但须重连并刷新模型列表逻辑
    LaunchedEffect(hostIp, hostPort, wsUrl, appName) {
        viewModel.connect(hostIp, hostPort, wsUrl, appName)
    }

    LaunchedEffect(showModelDialog) {
        if (showModelDialog) viewModel.prepareModelSwitchDialog()
    }

    // Auto-scroll to bottom
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    // Image picker (multi-select)
    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        scope.launch {
            for (uri in uris) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri) ?: continue
                    val bytes = inputStream.readBytes()
                    inputStream.close()
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val mimeType = context.contentResolver.getType(uri) ?: "image/png"
                    viewModel.attachImage(base64, mimeType)
                } catch (e: Exception) {
                    viewModel.addSystemMessage("图片读取失败: ${e.message}")
                }
            }
        }
    }

    // Fullscreen TV in landscape
    if (fullScreenTv) {
        TvLiveView(
            frameData = state.tvFrame!!,
            quality = state.tvQuality,
            intervalMs = state.tvIntervalMs,
            bytesTotal = state.tvBytesTotal,
            frameCount = state.tvFrameCount,
            focusChat = state.tvFocusChat,
            appName = appName,
            onClose = { viewModel.toggleTvMode() },
            onSettingsChange = { q, i -> viewModel.setTvSettings(q, i) },
            onFocusChatChange = { viewModel.setTvFocusChat(it) }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(appName, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (state.connectionState) {
                                ConnectionState.CONNECTED -> "● 已连接"
                                ConnectionState.CONNECTING -> "◌ 连接中..."
                                ConnectionState.RECONNECTING -> "◌ 重连中..."
                                ConnectionState.DISCONNECTED -> "○ 未连接"
                                ConnectionState.ERROR -> "✕ 错误"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when (state.connectionState) {
                                ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                                ConnectionState.ERROR -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        if (state.currentModel.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = " | ${state.currentModel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    var expanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { 
                        expanded = true
                        viewModel.fetchAvailableApps(hostIp, hostPort)
                    }) {
                        Icon(Icons.Default.Apps, contentDescription = "切换应用")
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        if (state.availableApps.isEmpty()) {
                            DropdownMenuItem(text = { Text("加载中...") }, onClick = {})
                        } else {
                            state.availableApps.filter { it.isWorkbench }.forEach { page ->
                                DropdownMenuItem(
                                    text = { 
                                        val portLabel = page.cdpPort?.let { ":$it " } ?: ""
                                        Text("${page.appType.displayName} $portLabel")
                                    },
                                    onClick = {
                                        expanded = false
                                        onSwitchApp(hostIp, hostPort, page.webSocketDebuggerUrl, page.appType.displayName)
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Column {
                ActionToolbar(
                    isConnected = state.connectionState == ConnectionState.CONNECTED,
                    isGenerating = state.isGenerating,
                    tvMode = state.tvMode,
                    currentModel = state.currentModel,
                    onNewSession = { viewModel.startNewSession() },
                    onStopGeneration = { viewModel.stopGeneration() },
                    onScrollUp = { viewModel.scrollUp() },
                    onScrollDown = { viewModel.scrollDown() },
                    onAcceptAll = { viewModel.acceptAll() },
                    onRejectAll = { viewModel.rejectAll() },
                    onSwitchModel = { showModelDialog = true }
                )
                InputBar(
                    text = state.inputText,
                    onTextChange = { viewModel.updateInputText(it) },
                    onSend = { viewModel.sendMessage() },
                    onAttachImage = { imagePickerLauncher.launch("image/*") },
                    isConnected = state.connectionState == ConnectionState.CONNECTED,
                    isGenerating = state.isGenerating,
                    pendingImages = state.pendingImages,
                    onRemoveImage = { id -> viewModel.removeImage(id) }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Embedded TV panel (portrait mode, not fullscreen)
                if (showTv) {
                    TvLiveView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(tvHeightDp),
                        frameData = state.tvFrame!!,
                        quality = state.tvQuality,
                        intervalMs = state.tvIntervalMs,
                        bytesTotal = state.tvBytesTotal,
                        frameCount = state.tvFrameCount,
                        focusChat = state.tvFocusChat,
                        appName = appName,
                        onClose = { viewModel.toggleTvMode() },
                        onSettingsChange = { q, i -> viewModel.setTvSettings(q, i) },
                        onFocusChatChange = { viewModel.setTvFocusChat(it) }
                    )
                    // Drag handle to resize TV panel
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .background(MaterialTheme.colorScheme.background)
                            .pointerInput(Unit) {
                                detectVerticalDragGestures { change, dragAmount ->
                                    val dragDp = dragAmount / density.density
                                    tvHeightDp = (tvHeightDp + dragDp.dp)
                                        .coerceIn(100.dp, screenHeightDp * 0.9f)
                                    change.consume()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // Visual drag indicator
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                        )
                    }
                }

                // Chat messages
                if (state.messages.isEmpty()) {
                    EmptyState(appName)
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(state.messages) { message ->
                            MessageBubble(message)
                        }
                    }
                }
            }

            // 浮动历史会话按钮
            IconButton(
                onClick = {
                    showSessionDialog = true
                    viewModel.fetchRecentSessionsList()
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
            ) {
                Icon(
                    Icons.Default.List,
                    contentDescription = "会话列表",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 浮动上下切换按钮
            val workbenches = state.availableApps.filter { it.isWorkbench }
            if (workbenches.size > 1) {
                val currentIndex = workbenches.indexOfFirst { it.webSocketDebuggerUrl == wsUrl }
                if (currentIndex >= 0) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val prevIndex = if (currentIndex - 1 < 0) workbenches.size - 1 else currentIndex - 1
                        val nextIndex = (currentIndex + 1) % workbenches.size

                        IconButton(onClick = {
                            val target = workbenches[prevIndex]
                            onSwitchApp(hostIp, hostPort, target.webSocketDebuggerUrl, target.appType.displayName)
                        }) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = "上一个窗口",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            val target = workbenches[nextIndex]
                            onSwitchApp(hostIp, hostPort, target.webSocketDebuggerUrl, target.appType.displayName)
                        }) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "下一个窗口",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // Model Dialog
    if (showModelDialog) {
        ModelSwitchDialog(
            currentModel = state.currentModel,
            appName = appName,
            ideOptions = state.ideModelOptions,
            ideOptionsLoading = state.ideModelOptionsLoading,
            onDismiss = {
                showModelDialog = false
                viewModel.onModelSwitchDialogClosed()
            },
            onSelect = { model ->
                showModelDialog = false
                viewModel.onModelSwitchDialogClosed()
                viewModel.switchModel(model)
            }
        )
    }

    // Session Dialog
    if (showSessionDialog) {
        SessionListDialog(
            sessions = state.recentSessions,
            isLoading = state.isSessionsLoading,
            onDismiss = {
                showSessionDialog = false
                viewModel.closeSessionDialog()
            },
            onSelect = { index ->
                showSessionDialog = false
                viewModel.switchSessionByIndex(index)
            }
        )
    }

    // Screenshot Dialog
    if (showScreenshotDialog && state.lastScreenshot != null) {
        ScreenshotDialog(
            imageData = state.lastScreenshot,
            onDismiss = { showScreenshotDialog = false }
        )
    }

    // OTA：连接成功后与主机列表同源检查，新版本在聊天页内弹窗（非系统推送）
    state.pendingOta?.let { otaInfo ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissPendingOta() },
            title = { Text("发现新版本 v${otaInfo.versionName}") },
            text = {
                Text(
                    "当前已安装: v${localPkg.first} (${localPkg.second})\n" +
                        "远端版本码: ${otaInfo.versionCode}\n\n${otaInfo.updateMessage}"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val base = state.pendingOtaRelayUrl
                        ?: "http://$hostIp:$RELAY_OTA_HTTP_PORT"
                    viewModel.dismissPendingOta()
                    val needNotifPerm = Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    if (needNotifPerm) {
                        pendingOtaDownloadUrl = base
                        otaNotifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        OtaUpdateManager(context, base).downloadAndInstallUpdate(base)
                    }
                }) { Text("更新") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPendingOta() }) { Text("稍后") }
            }
        )
    }
}

@Composable
private fun EmptyState(appName: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Chat,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "已连接 $appName，输入消息开始对话",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun ModelSwitchDialog(
    currentModel: String,
    appName: String,
    ideOptions: List<String>,
    ideOptionsLoading: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val isCursor = appName.contains("Cursor", ignoreCase = true)
    val cursorFixedModels = listOf("Opus", "GPT", "Auto", "Composer 2")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换 AI 模型") },
        text = {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(scroll)
            ) {
                val isWindsurf = appName.contains("Windsurf", ignoreCase = true)
                val isCodex = appName.contains("Codex", ignoreCase = true)

                val models = when {
                    isCursor -> cursorFixedModels
                    isWindsurf -> listOf("Claude Opus 4.7", "Claude Opus 4.6", "Claude Sonnet 4.6", "GPT-5.3-Codex", "GPT-5.4", "Kimi K2.6", "SWE-1.6", "Gemini 3.1 Pro", "Adaptive")
                    isCodex -> listOf("GPT-4.1", "GPT-4o", "Claude Sonnet 4.6", "o3", "o4-mini")
                    else -> listOf(
                        "Gemini 3.1 Pro (High)", "Gemini 3.1 Pro (Low)", "Gemini 3 Flash",
                        "Claude Sonnet 4.6 (Thinking)", "Claude Opus 4.6 (Thinking)",
                        "GPT-OSS 120B (Medium)", "Claude Opus 4.7 (Thinking)"
                    )
                }

                models.forEach { model ->
                    ModelRow(model = model, currentModel = currentModel, onSelect = onSelect)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun ModelRow(model: String, currentModel: String, onSelect: (String) -> Unit) {
    val isMatch = model.equals(currentModel, ignoreCase = true) ||
        (currentModel.isNotEmpty() && (
            model.contains(currentModel, ignoreCase = true) ||
                currentModel.contains(model, ignoreCase = true)
            ))
    TextButton(
        onClick = { onSelect(model) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = if (isMatch) "✓ $model" else model,
            color = if (isMatch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun ScreenshotDialog(
    imageData: ByteArray,
    onDismiss: () -> Unit
) {
    val bitmap = remember(imageData) {
        BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "📸 屏幕截图",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("关闭")
                }
            }
        }
    }
}

@Composable
fun SessionListDialog(
    sessions: List<String>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("最近会话") },
        text = {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(scroll)
            ) {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else if (sessions.isEmpty()) {
                    Text(
                        text = "暂无会话记录或无法读取",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    sessions.forEachIndexed { index, sessionTitle ->
                        TextButton(
                            onClick = { onSelect(index) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val parts = sessionTitle.split(" · ", limit = 2)
                            val title = parts[0]
                            val time = if (parts.size > 1) parts[1] else ""
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = title,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                if (time.isNotEmpty()) {
                                    Text(
                                        text = time,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
