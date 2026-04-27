package com.cdp.remote.presentation.screen.hosts

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.cdp.remote.data.ota.OtaCheckOutcome
import com.cdp.remote.data.ota.OtaUpdateManager
import com.cdp.remote.data.ota.appVersionCodeLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cdp.remote.data.cdp.CdpPage
import com.cdp.remote.data.cdp.ElectronAppType
import com.cdp.remote.data.cdp.HostInfo
import com.cdp.remote.data.cdp.RELAY_OTA_HTTP_PORT
import com.cdp.remote.presentation.theme.AccentCyan
import com.cdp.remote.presentation.theme.Secondary
import com.cdp.remote.presentation.theme.SuccessGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(
    onNavigateToChat: (hostIp: String, hostPort: Int, wsUrl: String, appName: String) -> Unit,
    onNavigateToScheduler: (hostIp: String, hostPort: Int) -> Unit = { _, _ -> },
    onNavigateToWorkflow: (hostIp: String, hostPort: Int) -> Unit = { _, _ -> },
    viewModel: HostListViewModel = viewModel()
) {
    val context = LocalContext.current
    val state = viewModel.uiState
    var launchCwd by remember { mutableStateOf("") }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<OtaUpdateManager.VersionInfo?>(null) }
    /** 发现更新时应用从中继拉 APK 的 base（可能与列表顺序不一致） */
    var updateRelayBaseUrl by remember { mutableStateOf<String?>(null) }
    var otaStatusLine by remember { mutableStateOf("正在检查更新…") }
    /** 用户点「更新」后若需先申请通知权限，再从此 URL 发起 DownloadManager */
    var pendingOtaDownloadUrl by remember { mutableStateOf<String?>(null) }
    val otaNotifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingOtaDownloadUrl?.let { url ->
            OtaUpdateManager(context, url).downloadAndInstallUpdate(url)
            pendingOtaDownloadUrl = null
        }
    }
    var hostToDelete by remember { mutableStateOf<HostInfo?>(null) }
    var pageToClose by remember { mutableStateOf<CdpPage?>(null) }

    val localPkg = remember {
        try {
            val p = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            Pair(p.versionName ?: "", p.versionCode)
        } catch (_: Exception) {
            Pair("?", 0)
        }
    }

    suspend fun runOtaCheck() {
        // OTA 只走固定 19336，绝不用列表里用于 CDP 的其它端口
        val urls = state.hosts.map { it.otaHttpBaseUrl }.distinct()
        if (urls.isEmpty()) {
            otaStatusLine = "未添加中继，无法检查更新（点右上角 + 添加 Mac 的 IP；OTA 固定请求 :${RELAY_OTA_HTTP_PORT}）"
            return
        }
        otaStatusLine = "正在连接 OTA 端口 ${RELAY_OTA_HTTP_PORT}: ${urls.first()}/version …"
        val localVc = context.appVersionCodeLong()
        val mgr = OtaUpdateManager(context, urls.first())
        when (val r = mgr.checkFromRelayUrls(urls, localVc)) {
            is OtaCheckOutcome.UpdateAvailable -> {
                updateInfo = r.info
                updateRelayBaseUrl = r.relayBaseUrl
                otaStatusLine =
                    "发现新版本 v${r.info.versionName} (versionCode=${r.info.versionCode}) ← ${r.relayBaseUrl}"
                showUpdateDialog = true
            }
            is OtaCheckOutcome.UpToDate -> {
                val lc = context.appVersionCodeLong()
                otaStatusLine = "已是最新：本机 versionCode=$lc，中继 ${r.remote.versionCode} ← ${r.relayBaseUrl}"
            }
            is OtaCheckOutcome.Failed ->
                otaStatusLine = "OTA：${r.detail}"
        }
    }

    LaunchedEffect(Unit) {
        viewModel.scanAllHosts()
    }

    LaunchedEffect(state.hosts) {
        delay(400)
        runOtaCheck()
    }

    Scaffold(
        topBar = {
            val scope = rememberCoroutineScope()
            TopAppBar(
                title = { Text("CDP Remote", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    IconButton(onClick = {
                        val host = state.hosts.firstOrNull()
                        if (host != null) onNavigateToWorkflow(host.ip, host.port)
                    }) {
                        Icon(
                            Icons.Default.AccountTree,
                            contentDescription = "Git 流水线",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = {
                        val host = state.hosts.firstOrNull()
                        if (host != null) onNavigateToScheduler(host.ip, host.port)
                    }) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = "任务调度",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { viewModel.toggleTvMode() }) {
                        Icon(
                            Icons.Default.Tv,
                            contentDescription = "TV 模式",
                            tint = if (state.tvMode) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        viewModel.scanAllHosts()
                        scope.launch { runOtaCheck() }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "扫描")
                    }
                    IconButton(onClick = { viewModel.showAddDialog() }) {
                        Icon(Icons.Default.Add, contentDescription = "添加主机")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.showLaunchDialog() },
                containerColor = AccentCyan
            ) {
                Icon(Icons.Default.RocketLaunch, contentDescription = "远程启动")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = otaStatusLine,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            // Loading indicator
            if (state.isScanning) {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            // Error
            state.scanError?.let { error ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Hosts with discovered apps
            items(state.hosts) { host ->
                HostCard(
                    host = host,
                    apps = state.discoveredApps[host.address] ?: emptyList(),
                    isScanning = state.isScanning,
                    onScan = { viewModel.scanHost(host) },
                    onRemove = { hostToDelete = host },
                    onAppClick = { page ->
                        onNavigateToChat(
                            host.ip,
                            host.port,
                            page.webSocketDebuggerUrl,
                            page.appType.displayName
                        )
                    },
                    onAppClose = { page -> pageToClose = page },
                    appOrder = state.appOrder[host.address] ?: emptyList(),
                    onReorder = { orderedWsUrls ->
                        viewModel.reorderApps(host.address, orderedWsUrls)
                    }
                )
            }

            // ─── TV 实时画面 ───
            if (state.tvMode) {
                val allPages = state.discoveredApps.values.flatten().filter { it.isWorkbench }
                if (allPages.isEmpty()) {
                    item {
                        Text(
                            "TV 模式已开启，等待 IDE 连接…",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(allPages) { page ->
                    val frame = state.tvFrames[page.webSocketDebuggerUrl]
                    TvFrameCard(
                        page = page,
                        frame = frame,
                        onClick = {
                            val host = state.hosts.firstOrNull()
                            if (host != null) {
                                onNavigateToChat(
                                    host.ip, host.port,
                                    page.webSocketDebuggerUrl,
                                    page.appType.displayName
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    // Add Host Dialog
    if (state.showAddDialog) {
        AddHostDialog(
            onDismiss = { viewModel.hideAddDialog() },
            onConfirm = { ip, port, name -> viewModel.addHost(ip, port, name) }
        )
    }

    // Folder Browser Dialog
    if (state.folderBrowserState.isOpen) {
        RemoteFolderBrowserDialog(
            state = state.folderBrowserState,
            onDismiss = { viewModel.closeFolderBrowser() },
            onPathSelected = { path -> launchCwd = path },
            onNavigate = { hostUrl, path -> viewModel.loadDirectory(hostUrl, path) },
            onCreateFolder = { folderName ->
                val hostUrl = state.hosts.firstOrNull()?.httpUrl ?: ""
                viewModel.createDirectory(hostUrl, state.folderBrowserState.currentPath, folderName)
            }
        )
    }

    // Launch IDE Dialog
    if (state.showLaunchDialog) {
        LaunchIdeDialog(
            launchStep = state.launchStep,
            launchStatus = state.launchStatus,
            cwd = launchCwd,
            onCwdChange = { launchCwd = it },
            onDismiss = { viewModel.hideLaunchDialog() },
            onLaunch = { appName, cdpPort, cwd ->
                viewModel.remoteLaunchOnPort(cdpPort, appName, cwd)
            },
            onBrowseFolder = {
                viewModel.openFolderBrowser(
                    state.hosts.firstOrNull()?.httpUrl ?: ""
                )
            },
            cwdHistory = state.cwdHistory,
            onHistoryDelete = { path -> viewModel.removeCwdHistoryItem(path) }
        )
    }

    // Delete Host Confirmation Dialog
    hostToDelete?.let { host ->
        AlertDialog(
            onDismissRequest = { hostToDelete = null },
            title = { Text("删除主机") },
            text = { Text("确定要删除主机「${host.displayName}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeHost(host)
                    hostToDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { hostToDelete = null }) { Text("取消") }
            }
        )
    }

    // Close Page Confirmation Dialog
    pageToClose?.let { page ->
        AlertDialog(
            onDismissRequest = { pageToClose = null },
            title = { Text("${page.appType.displayName} :${page.cdpPort ?: ""}") },
            text = { Text("关闭窗口仅关闭当前标签页；退出 IDE 会请求该端口实例正常退出，不会直接杀进程。") },
            confirmButton = {
                TextButton(onClick = {
                    val host = state.hosts.firstOrNull()
                    if (host != null) {
                        viewModel.closePage(host.ip, host.port, page)
                    }
                    pageToClose = null
                }) { Text("关闭窗口") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pageToClose = null }) { Text("取消") }
                    TextButton(onClick = {
                        val host = state.hosts.firstOrNull()
                        val cdpPort = page.cdpPort
                        if (host != null && cdpPort != null) {
                            viewModel.exitPortProcess(host.ip, host.port, cdpPort)
                        }
                        pageToClose = null
                    }) { Text("退出 IDE", color = MaterialTheme.colorScheme.error) }
                }
            }
        )
    }

    // Update Available Dialog
    if (showUpdateDialog && updateInfo != null) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("发现新版本 v${updateInfo!!.versionName}") },
            text = {
                Text(
                    "当前已安装: v${localPkg.first} (${localPkg.second})\n" +
                        "远端版本码: ${updateInfo!!.versionCode}\n\n${updateInfo!!.updateMessage}"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showUpdateDialog = false
                    val base = updateRelayBaseUrl ?: state.hosts.firstOrNull()?.otaHttpBaseUrl
                    if (base == null) return@TextButton
                    // Android 13+：无通知权限时 DownloadManager 完成通知可能不出现；权限拒绝仍继续下载
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
                TextButton(onClick = { showUpdateDialog = false }) { Text("稍后") }
            }
        )
    }
}

@Composable
fun HostCard(
    host: HostInfo,
    apps: List<CdpPage>,
    isScanning: Boolean,
    onScan: () -> Unit,
    onRemove: () -> Unit,
    onAppClick: (CdpPage) -> Unit,
    onAppClose: (CdpPage) -> Unit,
    appOrder: List<String> = emptyList(),
    onReorder: (List<String>) -> Unit = {}
) {
    val workbenchApps = apps.filter { it.isWorkbench }
    // 按用户自定义顺序排序；未出现在 appOrder 中的排在末尾
    val sortedApps = remember(workbenchApps, appOrder) {
        if (appOrder.isEmpty()) workbenchApps
        else {
            val orderMap = appOrder.withIndex().associate { (i, ws) -> ws to i }
            workbenchApps.sortedBy { orderMap[it.webSocketDebuggerUrl] ?: Int.MAX_VALUE }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Host header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (apps.isNotEmpty()) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = host.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = host.address,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row {
                    IconButton(onClick = onScan) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(20.dp))
                    }
                }
            }

            // Discovered apps — drag-to-reorder
            if (sortedApps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                DraggableAppList(
                    apps = sortedApps,
                    onAppClick = onAppClick,
                    onAppClose = onAppClose,
                    onReorder = { reordered ->
                        onReorder(reordered.map { it.webSocketDebuggerUrl })
                    }
                )
            } else if (isScanning) {
                Spacer(modifier = Modifier.height(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "未发现应用，点击刷新重试",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 支持长按拖拽排序的 IDE 应用列表。
 * 拖拽时被拖项半透明 + 缩放，其余项自动让位。
 */
@Composable
fun DraggableAppList(
    apps: List<CdpPage>,
    onAppClick: (CdpPage) -> Unit,
    onAppClose: (CdpPage) -> Unit,
    onReorder: (List<CdpPage>) -> Unit
) {
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var currentList by remember(apps) { mutableStateOf(apps) }
    val itemHeightPx = remember { mutableFloatStateOf(0f) }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        currentList.forEachIndexed { index, page ->
            val isDragged = draggedIndex == index

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        if (itemHeightPx.floatValue == 0f) {
                            itemHeightPx.floatValue = coords.size.height.toFloat()
                        }
                    }
                    .then(
                        if (isDragged)
                            Modifier
                                .zIndex(10f)
                                .graphicsLayer {
                                    translationY = dragOffset
                                    scaleX = 1.04f
                                    scaleY = 1.04f
                                    alpha = 0.85f
                                    shadowElevation = 12f
                                    shape = RoundedCornerShape(10.dp)
                                    clip = true
                                }
                        else Modifier
                            .zIndex(0f)
                            .graphicsLayer {
                                // 非拖拽项自然位置
                                alpha = if (draggedIndex != null) 0.7f else 1f
                            }
                    )
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggedIndex = index
                                dragOffset = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffset += dragAmount.y
                                // 计算目标位置
                                if (itemHeightPx.floatValue > 0f) {
                                    val targetIndex = (index + (dragOffset / (itemHeightPx.floatValue + 6.dp.toPx())).toInt())
                                        .coerceIn(0, currentList.size - 1)
                                    if (targetIndex != index && draggedIndex == index) {
                                        val newList = currentList.toMutableList()
                                        val item = newList.removeAt(index)
                                        newList.add(targetIndex, item)
                                        currentList = newList
                                        draggedIndex = targetIndex
                                        dragOffset = 0f
                                    }
                                }
                            },
                            onDragEnd = {
                                draggedIndex = null
                                dragOffset = 0f
                                onReorder(currentList)
                            },
                            onDragCancel = {
                                draggedIndex = null
                                dragOffset = 0f
                            }
                        )
                    }
            ) {
                AppItem(
                    page = page,
                    onClick = { onAppClick(page) },
                    onCloseClick = { onAppClose(page) }
                )
            }
        }
    }
}

@Composable
fun AppItem(page: CdpPage, onClick: () -> Unit, onCloseClick: () -> Unit, showDragHandle: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Drag handle (长按拖拽提示)
        if (showDragHandle) {
            Icon(
                Icons.Default.DragHandle,
                contentDescription = "长按拖拽排序",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        // App icon with tinted background circle
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Secondary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (page.appType) {
                    ElectronAppType.ANTIGRAVITY -> Icons.Default.AutoAwesome
                    ElectronAppType.WINDSURF -> Icons.Default.Air
                    ElectronAppType.CURSOR -> Icons.Default.Mouse
                    else -> Icons.Default.Code
                },
                contentDescription = null,
                tint = Secondary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        // Title + subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = page.appType.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            val portLabel = page.cdpPort?.let { ":$it" } ?: ""
            Text(
                text = "${portLabel} ${page.title.take(36)}".trim(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Close chip — small subtle pill
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f))
                .clickable(onClick = onCloseClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "关闭",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
        }

    }
}

@Composable
fun AddHostDialog(
    onDismiss: () -> Unit,
    onConfirm: (ip: String, port: Int, name: String) -> Unit
) {
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("19336") }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加主机") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("IP 地址") })
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("端口") })
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称 (可选)") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(ip, port.toIntOrNull() ?: 19336, name.ifBlank { ip }) }) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun RemoteFolderBrowserDialog(
    state: FolderBrowserState,
    onDismiss: () -> Unit,
    onPathSelected: (path: String) -> Unit,
    onNavigate: (hostUrl: String, path: String) -> Unit,
    onCreateFolder: (path: String) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    val folderIconColor = Color(0xFF6C5CE7)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // ── Header: 选择工作目录 + 新建按钮 ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "选择工作目录",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = { showCreateDialog = !showCreateDialog },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "新建文件夹", tint = folderIconColor)
                    }
                }

                // ── 当前路径 ──
                Text(
                    text = "当前: ${state.currentPath.ifEmpty { "/" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )

                // ── 新建文件夹输入 ──
                if (showCreateDialog) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newFolderName,
                            onValueChange = { newFolderName = it },
                            label = { Text("文件夹名") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        IconButton(onClick = {
                            if (newFolderName.isNotBlank()) {
                                onCreateFolder(newFolderName)
                                newFolderName = ""
                                showCreateDialog = false
                            }
                        }) {
                            Icon(Icons.Default.Check, contentDescription = "创建", tint = folderIconColor)
                        }
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outlineVariant)

                // ── Loading / Error ──
                if (state.isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        color = folderIconColor
                    )
                }
                state.error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                // ── 目录列表 ──
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .weight(1f, fill = false)
                ) {
                    // 上一级
                    if (state.parentPath.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigate(state.hostUrl, state.parentPath) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = folderIconColor
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = ".. (上一级)",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                    items(state.dirs) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigate(state.hostUrl, item.path) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = folderIconColor
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── 底部按钮 ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("取消", color = folderIconColor)
                    }
                    Button(
                        onClick = {
                            onPathSelected(state.currentPath)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = folderIconColor),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Text("选择此目录", color = Color.White)
                    }
                }
            }
        }
    }
}

private data class IdeAppOption(
    val name: String,
    val defaultPort: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val iconColor: Color,
    val bgColor: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdeAppCard(
    app: IdeAppOption,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .then(
                if (isSelected) Modifier.border(2.dp, app.iconColor, RoundedCornerShape(16.dp))
                else Modifier
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = app.bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 2.dp else 0.dp),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(app.iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(app.icon, contentDescription = null, tint = app.iconColor, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(app.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text(":${app.defaultPort}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchIdeDialog(
    launchStep: LaunchStep = LaunchStep.IDLE,
    launchStatus: String?,
    cwd: String,
    onCwdChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onLaunch: (appName: String, cdpPort: Int, cwd: String) -> Unit,
    onBrowseFolder: () -> Unit,
    cwdHistory: List<CwdHistoryItem> = emptyList(),
    onHistoryDelete: (String) -> Unit = {}
) {
    val purpleAccent = Color(0xFF6C5CE7)
    val appOptions = remember {
        listOf(
            IdeAppOption("Antigravity", 9333, Icons.Default.AutoAwesome, Color(0xFF6C5CE7), Color(0xFFF3F0FF)),
            IdeAppOption("Windsurf", 9444, Icons.Default.Air, Color(0xFF00B894), Color(0xFFECFDF5)),
            IdeAppOption("Cursor", 9555, Icons.Default.Mouse, Color(0xFF00CEC9), Color(0xFFE0F7FA)),
            IdeAppOption("Codex", 9666, Icons.Default.Code, Color(0xFFE17055), Color(0xFFFFF0ED))
        )
    }
    var selectedIndex by remember { mutableStateOf(0) }
    var cdpPort by remember { mutableStateOf("9333") }
    
    // 记录最近一次启动时的状态，若用户切换选项则自动重置成功状态
    var launchedIndex by remember { mutableStateOf(-1) }
    var launchedPort by remember { mutableStateOf<String?>(null) }
    var launchedCwd by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // ── App Grid (2×2) ──

            // (app grid continued)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                appOptions.take(2).forEachIndexed { i, app ->
                    IdeAppCard(
                        app = app, isSelected = selectedIndex == i,
                        onClick = { selectedIndex = i; cdpPort = app.defaultPort.toString() },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                appOptions.drop(2).forEachIndexed { i, app ->
                    val idx = i + 2
                    IdeAppCard(
                        app = app, isSelected = selectedIndex == idx,
                        onClick = { selectedIndex = idx; cdpPort = app.defaultPort.toString() },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── 工作目录 ──
            Text("工作目录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = cwd,
                    onValueChange = onCwdChange,
                    placeholder = { Text("选择或输入路径…", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.weight(1f).height(46.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onBrowseFolder) {
                    Icon(Icons.Default.FolderOpen, contentDescription = "浏览", tint = purpleAccent)
                }
            }

            // ── 最近使用 ──
            if (cwdHistory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    "最近使用",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                cwdHistory.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                onCwdChange(item.path)
                            }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = purpleAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val parts = item.path.split("/").filter { it.isNotEmpty() }
                            val shortPath = if (parts.size > 2) {
                                ".../${parts.takeLast(2).joinToString("/")}"
                            } else {
                                item.path
                            }
                            Text(shortPath, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable { onHistoryDelete(item.path) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // ── CDP 端口（直接显示，不折叠） ──
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = cdpPort,
                onValueChange = { cdpPort = it },
                label = { Text("CDP 端口", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(12.dp)
            )

            // ── 状态 ──
            launchStatus?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ── 底部按钮 ──
            Spacer(modifier = Modifier.height(16.dp))
            val isLaunching = launchStep == LaunchStep.CONNECTING || launchStep == LaunchStep.LAUNCHING
            // 只有当当前选择的选项与启动时完全一致，才视为“当前应用已启动成功”
            val isSuccess = launchStep == LaunchStep.SUCCESS &&
                    selectedIndex == launchedIndex &&
                    cdpPort == launchedPort &&
                    cwd == launchedCwd
            val isFailed = launchStep == LaunchStep.FAILED
            val buttonColor = when {
                isSuccess -> Color(0xFF00B894)
                isFailed -> Color(0xFFE17055)
                else -> purpleAccent
            }
            val buttonText = when {
                isSuccess -> "✅ 启动成功"
                isFailed -> "⚠ 点击重试"
                isLaunching -> "启动中..."
                else -> "启动"
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !isLaunching,
                    border = ButtonDefaults.outlinedButtonBorder
                ) { Text("取消") }
                Button(
                    onClick = {
                        val app = appOptions[selectedIndex]
                        launchedIndex = selectedIndex
                        launchedPort = cdpPort
                        launchedCwd = cwd
                        onLaunch(app.name, cdpPort.toIntOrNull() ?: app.defaultPort, cwd)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !isLaunching && !isSuccess,
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
                ) { Text(buttonText, color = Color.White) }
            }
        }
    }
}

@Composable
fun TvFrameCard(
    page: CdpPage,
    frame: ByteArray?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column {
            // Header: app name + LIVE badge
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (page.appType) {
                            ElectronAppType.ANTIGRAVITY -> Icons.Default.AutoAwesome
                            ElectronAppType.WINDSURF -> Icons.Default.Air
                            ElectronAppType.CURSOR -> Icons.Default.Mouse
                            else -> Icons.Default.Code
                        },
                        contentDescription = null,
                        tint = Secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = page.appType.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    page.cdpPort?.let { port ->
                        Text(
                            text = " :$port",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (frame != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(SuccessGreen.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(SuccessGreen)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "LIVE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = SuccessGreen
                        )
                    }
                }
            }
            if (frame != null) {
                val bitmap = remember(frame) {
                    BitmapFactory.decodeByteArray(frame, 0, frame.size)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "${page.appType.displayName} 实时画面",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}
