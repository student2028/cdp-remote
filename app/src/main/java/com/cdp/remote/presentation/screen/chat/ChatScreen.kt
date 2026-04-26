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
import com.cdp.remote.data.AppOrderStore
import com.cdp.remote.data.cdp.ConnectionState
import com.cdp.remote.data.cdp.ElectronAppType
import com.cdp.remote.data.cdp.RELAY_OTA_HTTP_PORT
import com.cdp.remote.data.ota.OtaUpdateManager
import com.cdp.remote.presentation.screen.chat.components.ActionToolbar
import com.cdp.remote.presentation.screen.chat.components.InputBar
import com.cdp.remote.presentation.screen.chat.components.MessageBubble
import com.cdp.remote.presentation.screen.chat.components.TvLiveView
import kotlinx.coroutines.launch

/**
 * Cursor 桌面端「模型选择器」预置列表。
 *
 * **设计原则**：故意**只保留品牌/系列级关键词**，不写死版本号（如 `GPT-5.5`、`Sonnet 4.6`）。
 * Cursor 每隔几周就会上新小版本，写死版本号会导致「菜单里只剩新版 → 旧版 key 匹配不到 → 切换失败」
 * 这种低级回归（参见 2026-04 的 `GPT-5.4 → GPT-5.5` 事故）。
 *
 * 列表中的字符串既作为按钮显示文案，也作为 [com.cdp.remote.data.cdp.CursorCommands.switchModel]
 * 的关键词参数（子串匹配菜单 `<li>` 的 textContent，不区分大小写）。
 * 每一项必须在当前 [CURSOR_PRESET_CANONICAL_NAMES] 中**唯一**命中 1 行，否则行为不确定。
 *
 * 维护方式：当 Cursor 上线**新品牌**（不是小版本升级，而是全新模型系列）时，跑一遍
 * `~/.agents/skills/cursor/scripts/cursor_dump_model_picker_dom.js` 拿到最新菜单，
 * 同步更新本列表与 [CURSOR_PRESET_CANONICAL_NAMES]。
 */
val CURSOR_PRESET_MODELS: List<String> = listOf(
    "Auto",
    "Premium",
    // Composer 同时存在 2.x / 1.5 两档，必须保留区分号
    "Composer 2",
    "Composer 1.5",
    "GPT",
    "Codex",
    "Sonnet",
    "Opus"
)

/**
 * 当前 Cursor 模型选择菜单的「真实展示名」（textContent，已折叠空白），
 * 用于校验 [CURSOR_PRESET_MODELS] 中的每一项都能在菜单里命中。
 */
val CURSOR_PRESET_CANONICAL_NAMES: List<String> = listOf(
    "Auto Efficiency",
    "Premium Intelligence",
    "Composer 2 Fast",
    "Composer 1.5",
    "GPT-5.5 Medium",
    "Codex 5.3 Medium",
    "Sonnet 4.6 Medium",
    "Opus 4.7 Extra High"
)

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
    var showGlobalRuleDialog by remember { mutableStateOf(false) }
    var globalRuleDraft by remember { mutableStateOf("") }
    val showAntigravityGlobalRule = appName.contains("Antigravity", ignoreCase = true)

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
            controlMode = state.tvControlMode,
            onClose = { viewModel.toggleTvMode() },
            onSettingsChange = { q, i -> viewModel.setTvSettings(q, i) },
            onFocusChatChange = { viewModel.setTvFocusChat(it) },
            onToggleControlMode = { viewModel.toggleTvControlMode() },
            onRemoteInput = { type, rx, ry, btn -> viewModel.dispatchRemoteInput(type, rx, ry, btn) },
            onRemoteScroll = { rx, ry, dy -> viewModel.dispatchRemoteScroll(rx, ry, dy) },
            onRemoteText = { text -> viewModel.dispatchRemoteText(text) },
            onRemoteKey = { type, key -> viewModel.dispatchRemoteKey(type, key) }
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
                            val hostAddr = "$hostIp:$hostPort"
                            val sortedMenuApps = AppOrderStore.sortApps(
                                hostAddr, state.availableApps.filter { it.isWorkbench }
                            )
                            sortedMenuApps.forEach { page ->
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
                    isWindsurf = state.isWindsurf,
                    onNewSession = { viewModel.startNewSession() },
                    onStopGeneration = { viewModel.stopGeneration() },
                    onCancelRunningTask = { viewModel.cancelRunningTask() },
                    onScrollUp = { viewModel.scrollUp() },
                    onScrollDown = { viewModel.scrollDown() },
                    onAcceptAll = { viewModel.acceptAll() },
                    onRejectAll = { viewModel.rejectAll() },
                    onSwitchModel = { showModelDialog = true },
                    showGlobalRuleButton = showAntigravityGlobalRule,
                    onGlobalRule = { showGlobalRuleDialog = true }
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
                        controlMode = state.tvControlMode,
                        onClose = { viewModel.toggleTvMode() },
                        onSettingsChange = { q, i -> viewModel.setTvSettings(q, i) },
                        onFocusChatChange = { viewModel.setTvFocusChat(it) },
                        onToggleControlMode = { viewModel.toggleTvControlMode() },
                        onRemoteInput = { type, rx, ry, btn -> viewModel.dispatchRemoteInput(type, rx, ry, btn) },
                        onRemoteScroll = { rx, ry, dy -> viewModel.dispatchRemoteScroll(rx, ry, dy) },
                        onRemoteText = { text -> viewModel.dispatchRemoteText(text) },
                        onRemoteKey = { type, key -> viewModel.dispatchRemoteKey(type, key) }
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

            // 浮动上下切换按钮（按主页拖拽排序顺序）
            val hostAddr = "$hostIp:$hostPort"
            val workbenches = AppOrderStore.sortApps(
                hostAddr, state.availableApps.filter { it.isWorkbench }
            )
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

    // 反重力：全局规则（对端侧栏 Customizations → Global，经 CDP 远程写入）
    if (showGlobalRuleDialog) {
        AlertDialog(
            onDismissRequest = { showGlobalRuleDialog = false },
            title = { Text("全局规则") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "将写入对端反重力：侧栏 ⋯ → Customization → Rules → Global，与在电脑上操作等效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = globalRuleDraft,
                        onValueChange = { globalRuleDraft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 360.dp),
                        minLines = 4,
                        maxLines = 16,
                        placeholder = { Text("例如：使用中文回复…") }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showGlobalRuleDialog = false
                        viewModel.applyGlobalAgentRule(globalRuleDraft)
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showGlobalRuleDialog = false }) { Text("取消") }
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
    val cursorFixedModels = CURSOR_PRESET_MODELS

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
