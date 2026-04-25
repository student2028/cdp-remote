package com.cdp.remote.presentation.screen.workflow

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cdp.remote.presentation.screen.hosts.RemoteFolderBrowserDialog
import com.cdp.remote.presentation.screen.scheduler.IdeInfo
import kotlinx.coroutines.launch
import java.io.File

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.style.TextOverflow


private val purpleAccent = Color(0xFF6C5CE7)
private val emeraldAccent = Color(0xFF00B894)
private val warningAccent = Color(0xFFFDCB6E)
private val redAccent = Color(0xFFE74C3C)
private val subtleBorder = Color(0xFFD9DCE5)
private val textFieldBorder = Color(0xFFD6D9E3)

private data class IdeStyle(val icon: ImageVector, val color: Color, val bg: Color)

@Composable
private fun compactTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = purpleAccent.copy(alpha = 0.72f),
    unfocusedBorderColor = textFieldBorder,
    disabledBorderColor = textFieldBorder.copy(alpha = 0.55f),
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    cursorColor = purpleAccent,
)

private val ideStyles = mapOf(
    "Antigravity" to IdeStyle(Icons.Default.AutoAwesome, Color(0xFF6C5CE7), Color(0xFFF3F0FF)),
    "Windsurf"    to IdeStyle(Icons.Default.Air,         Color(0xFF00B894), Color(0xFFECFDF5)),
    "Cursor"      to IdeStyle(Icons.Default.Mouse,       Color(0xFF00CEC9), Color(0xFFE0F7FA)),
    "Codex"       to IdeStyle(Icons.Default.Code,        Color(0xFFE17055), Color(0xFFFFF0ED)),
)
private val defaultIdeStyle = IdeStyle(Icons.Default.Terminal, Color(0xFF636E72), Color(0xFFF1F2F6))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowScreen(
    hostIp: String,
    hostPort: Int,
    onNavigateBack: () -> Unit,
    viewModel: WorkflowViewModel = viewModel(),
) {
    val state = viewModel.uiState
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(hostIp, hostPort) {
        viewModel.init(hostIp, hostPort)
    }

    // Toast → SnackbarHostState（替代手动 Snackbar 组件）
    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
            viewModel.dismissToast()
        }
    }

    // 统一附件选择器（图片+文件合二为一）
    val attachPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        scope.launch {
            for (uri in uris) {
                try {
                    // P0#2: 用 use{} 保护 InputStream 防止泄漏
                    val bytes = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes()
                    } ?: continue
                    if (bytes.size > 10 * 1024 * 1024) {
                        viewModel.showToast("文件超过 10MB 上限，已跳过")
                        continue
                    }
                    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    val isImage = mimeType.startsWith("image/")

                    // P2#18: 用 DISPLAY_NAME 取真实文件名，而非 content URI 的 lastPathSegment
                    var displayName: String? = null
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            displayName = cursor.getString(0)
                        }
                    }
                    val name = displayName
                        ?: uri.lastPathSegment?.substringAfterLast('/')
                        ?: if (isImage) "image_${System.currentTimeMillis()}.png" else "file_${System.currentTimeMillis()}"

                    // P0#1: base64 写入 cacheDir，不在 UiState 内存中持有
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val cacheFile = File(context.cacheDir, "att_${System.nanoTime()}")
                    cacheFile.writeText(base64)

                    viewModel.addAttachment(
                        TaskAttachment(
                            type = if (isImage) AttachmentType.IMAGE else AttachmentType.FILE,
                            name = name,
                            mimeType = mimeType,
                            cachePath = cacheFile.absolutePath,
                            sizeBytes = bytes.size.toLong(),
                        )
                    )
                } catch (e: Exception) {
                    android.util.Log.e("WorkflowScreen", "附件读取失败", e)
                    viewModel.showToast("附件读取失败: ${e.message}")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Git 流水线",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", modifier = Modifier.size(20.dp))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.refreshIdes()
                            viewModel.refreshStatus()
                        },
                        modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", modifier = Modifier.size(19.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // P2#17: 运行中表单锁定（终态 DONE/ABORT 不算运行中）
            val isRunning = state.pipelineState != WorkflowState.IDLE
                    && state.pipelineState != WorkflowState.DONE
                    && state.pipelineState != WorkflowState.ABORT

            StatusCard(state)

            // ── 所有配置项合并到同一张卡片，用细线分隔，形成清晰的视觉层次 ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(1.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RoleSelectorSection(
                        icon = Icons.Outlined.Psychology,
                        title = "大脑 · Brain",
                        hint = "派发任务与审查代码",
                        instances = state.availableIdes,
                        selectedPort = state.brainSelectedPort,
                        isLoading = state.isLoadingIdes,
                        onSelect = viewModel::updateBrainIde,
                        enabled = !isRunning,
                    )
                    SectionDivider()
                    RoleSelectorSection(
                        icon = Icons.Outlined.Build,
                        title = "工人 · Worker",
                        hint = "接收任务并提交代码",
                        instances = state.availableIdes,
                        selectedPort = state.workerSelectedPort,
                        isLoading = state.isLoadingIdes,
                        onSelect = viewModel::updateWorkerIde,
                        enabled = !isRunning,
                    )
                    SectionDivider()
                    CwdSection(
                        cwd = state.cwd,
                        onCwdChange = viewModel::updateCwd,
                        cwdHistory = state.cwdHistory,
                        onBrowse = { viewModel.openFolderBrowser() },
                        onHistoryDelete = { viewModel.removeCwdHistoryItem(it) },
                        enabled = !isRunning,
                    )
                    SectionDivider()
                    InitialTaskSection(
                        value = state.initialTask,
                        onValueChange = viewModel::updateInitialTask,
                        attachments = state.attachments,
                        onAttach = { attachPickerLauncher.launch(arrayOf("*/*")) },
                        onRemoveAttachment = viewModel::removeAttachment,
                        enabled = !isRunning,
                    )
                }
            }

            ActionRow(
                state = state,
                onStart = viewModel::startPipeline,
                onAbort = viewModel::abortPipeline,
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    // 目录浏览器弹窗（复用 HostListScreen 的 RemoteFolderBrowserDialog）
    if (state.folderBrowserState.isOpen) {
        RemoteFolderBrowserDialog(
            state = state.folderBrowserState,
            onDismiss = { viewModel.closeFolderBrowser() },
            onPathSelected = { path -> viewModel.updateCwd(path) },
            onNavigate = { hostUrl, path -> viewModel.loadDirectory(hostUrl, path) },
            onCreateFolder = { folderName ->
                viewModel.createDirectory(
                    state.folderBrowserState.hostUrl,
                    state.folderBrowserState.currentPath,
                    folderName
                )
            }
        )
    }
}

// ─── 状态卡片 ─────────────────────────────────────────────────

@Composable
private fun StatusCard(state: WorkflowUiState) {
    val isIdle = state.pipelineState == WorkflowState.IDLE
    val isTerminal = state.pipelineState == WorkflowState.DONE || state.pipelineState == WorkflowState.ABORT
    // IDLE 用主题紫色而非灰色，让"就绪待命"读起来是正向的
    val accent = when (state.pipelineState) {
        WorkflowState.IDLE          -> purpleAccent
        WorkflowState.WORKER_CODE   -> emeraldAccent
        WorkflowState.BRAIN_REVIEW  -> purpleAccent
        WorkflowState.BRAIN_RECOVER -> Color(0xFFE17055)
        WorkflowState.DONE          -> emeraldAccent
        WorkflowState.ABORT         -> redAccent
        WorkflowState.UNKNOWN       -> Color(0xFFB2BEC3)
    }
    val accentDark = accent.copy(alpha = 0.7f)
    // IDLE 时显示"就绪待命"，不挂 emoji 避免视觉杂乱
    val statusLabel = when (state.pipelineState) {
        WorkflowState.IDLE -> "就绪待命"
        else -> "${state.pipelineState.badgeEmoji} ${state.pipelineState.displayName}".trim()
    }

    // 呼吸动画（运行中时脉冲）
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "pulseAlpha"
    )
    val dotAlpha = if (!isIdle && !isTerminal) pulseAlpha else 1f

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 渐变顶部条
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(
                        Brush.horizontalGradient(listOf(accent.copy(alpha = 0.92f), accentDark.copy(alpha = 0.82f), accent.copy(alpha = 0.92f)))
                    )
            )
            Column(modifier = Modifier.padding(16.dp)) {
                // 标题行：状态 + 计时
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = dotAlpha))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                    )
                    // IDLE 时把"上次流水线已完成/中断"作为紧凑 chip 挂在标题右侧，而不是占下一行
                    if (isIdle && state.lastFinishedState != null) {
                        Spacer(Modifier.width(8.dp))
                        val isDone = state.lastFinishedState == "DONE"
                        val chipColor = if (isDone) emeraldAccent else redAccent
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = chipColor.copy(alpha = 0.1f),
                        ) {
                            Text(
                                if (isDone) "上次 已完成" else "上次 已中断",
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = chipColor,
                                fontSize = 8.sp,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (!isIdle) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = accent.copy(alpha = 0.1f),
                        ) {
                            Text(
                                formatElapsed(state.elapsedMs),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = accent,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                // 流水线步骤进度条
                if (!isIdle || isTerminal) {
                    Spacer(Modifier.height(12.dp))
                    PipelineStepBar(state.pipelineState, accent)
                }

                // 超时警告
                if (state.warned) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = warningAccent.copy(alpha = 0.12f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Warning, null, tint = warningAccent, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "状态停留过久，建议检查 IDE",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFD68910),
                            )
                        }
                    }
                }

                // 运行中：显示 IDE 角色 + 工作目录
                if (!isIdle && (state.activeBrainIde != null || state.activeWorkerIde != null)) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.activeBrainIde?.let { brain ->
                            val s = ideStyles[brain] ?: defaultIdeStyle
                            IdeRoleBadge("🧠", brain, s.icon, s.color)
                        }
                        state.activeWorkerIde?.let { worker ->
                            val s = ideStyles[worker] ?: defaultIdeStyle
                            IdeRoleBadge("👷", worker, s.icon, s.color)
                        }
                    }
                }
                if (!isIdle && state.activeCwd != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "📂 ${state.activeCwd}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Relay 错误
                if (state.lastError != null) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = redAccent.copy(alpha = 0.08f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Warning, null, tint = redAccent, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                state.lastError,
                                style = MaterialTheme.typography.labelSmall,
                                color = redAccent,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Section 公共组件 ─────────────────────────────────────────
/** 配置卡片里 4 个 section 共用的标题：Icon + 主标题 + 副标题 */
@Composable
private fun SectionHeader(icon: ImageVector, title: String, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            null,
            tint = purpleAccent.copy(alpha = 0.78f),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
        )
        if (!subtitle.isNullOrEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                fontSize = 9.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 卡片内部 section 之间的细分隔线 */
@Composable
private fun SectionDivider() {
    Divider(
        modifier = Modifier.padding(vertical = 1.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f),
    )
}

/** 流水线 4 步进度条 */
@Composable
private fun PipelineStepBar(current: WorkflowState, accent: Color) {
    data class Step(val label: String, val emoji: String)
    val steps = listOf(
        Step("任务", "📋"),
        Step("编码", "👷"),
        Step("审查", "🧠"),
        Step("完成", "✅"),
    )
    val activeIndex = when (current) {
        WorkflowState.WORKER_CODE   -> 1
        WorkflowState.BRAIN_REVIEW, WorkflowState.BRAIN_RECOVER -> 2
        WorkflowState.DONE          -> 3
        WorkflowState.ABORT         -> -1 // 特殊：中断
        else -> 0
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { i, step ->
            val isDone = i < activeIndex
            val isCurrent = i == activeIndex
            val stepColor = when {
                current == WorkflowState.ABORT -> redAccent.copy(alpha = 0.4f)
                isDone -> accent
                isCurrent -> accent
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            }
            // 圆形步骤指示
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(if (isCurrent) 28.dp else 24.dp)
                        .clip(CircleShape)
                        .background(if (isCurrent || isDone) stepColor.copy(alpha = 0.15f) else Color.Transparent)
                        .border(
                            width = if (isCurrent) 2.dp else 1.dp,
                            color = stepColor,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (isDone) "✓" else step.emoji,
                        fontSize = if (isCurrent) 12.sp else 10.sp,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    step.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = if (isCurrent || isDone) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                )
            }
            // 连线
            if (i < steps.lastIndex) {
                val lineColor = if (isDone) accent.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .padding(horizontal = 2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(lineColor)
                )
            }
        }
    }
}

/** IDE 角色小标签 */
@Composable
private fun IdeRoleBadge(role: String, name: String, icon: ImageVector, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(0.8.dp, color.copy(alpha = 0.18f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                "$role $name",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Normal,
                color = color,
            )
        }
    }
}

// ─── IDE 角色选择 ─────────────────────────────────────────────

@Composable
private fun RoleSelectorSection(
    icon: ImageVector,
    title: String,
    hint: String,
    instances: List<IdeInfo>,
    selectedPort: Int,
    isLoading: Boolean,
    onSelect: (IdeInfo) -> Unit,
    enabled: Boolean = true,
) {
    val nameCounts = instances.groupBy { it.name }.mapValues { it.value.size }
    val alpha = if (enabled) 1f else 0.5f
    Column(modifier = Modifier.graphicsLayer(alpha = alpha)) {
        SectionHeader(icon, title, hint)
        Spacer(Modifier.height(8.dp))
        when {
            isLoading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("加载 IDE 中…", style = MaterialTheme.typography.bodySmall)
                }
            }
            instances.isEmpty() -> {
                Text(
                    "无在线 IDE，请先在主机列表页启动一个",
                    style = MaterialTheme.typography.bodySmall,
                    color = redAccent,
                )
            }
            else -> {
                instances.chunked(3).forEachIndexed { rowIndex, rowItems ->
                    if (rowIndex > 0) Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowItems.forEach { info ->
                            val showPort = (nameCounts[info.name] ?: 0) > 1
                            Box(modifier = Modifier.weight(1f)) {
                                IdeChip(
                                    info = info,
                                    showPort = showPort,
                                    selected = selectedPort == info.port,
                                    onClick = { if (enabled) onSelect(info) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        repeat(3 - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IdeChip(
    info: IdeInfo,
    showPort: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = ideStyles[info.name] ?: defaultIdeStyle
    val label = if (showPort) "${info.name} :${info.port}" else info.name
    val bg = if (selected) style.color.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface
    val border = if (selected) style.color.copy(alpha = 0.68f) else subtleBorder
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = androidx.compose.foundation.BorderStroke(if (selected) 1.3.dp else 0.8.dp, border),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(style.icon, null, tint = style.color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            AutoSizeText(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 自动缩小字体的 Text，兼容 Compose 1.5.x。
 * 从 [maxFontSize] 开始，每次溢出时以 [stepSize] 减小字体，直到 [minFontSize] 或不再溢出。
 */
@Composable
private fun AutoSizeText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    fontWeight: FontWeight,
    color: Color,
    maxFontSize: androidx.compose.ui.unit.TextUnit = 11.sp,
    minFontSize: androidx.compose.ui.unit.TextUnit = 7.5.sp,
    stepSize: Float = 0.5f,
) {
    var fontSize by remember(text) { mutableStateOf(maxFontSize) }
    var readyToDraw by remember(text) { mutableStateOf(false) }
    Text(
        text = text,
        style = style,
        fontWeight = fontWeight,
        fontSize = fontSize,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && fontSize.value > minFontSize.value) {
                val next = (fontSize.value - stepSize).coerceAtLeast(minFontSize.value)
                fontSize = next.sp
            } else {
                readyToDraw = true
            }
        },
        modifier = Modifier.graphicsLayer(alpha = if (readyToDraw) 1f else 0f),
    )
}

// ─── Git 仓库路径（含浏览按钮 + 历史记录） ─────────────────────

@Composable
private fun CwdSection(
    cwd: String,
    onCwdChange: (String) -> Unit,
    cwdHistory: List<com.cdp.remote.presentation.screen.hosts.CwdHistoryItem>,
    onBrowse: () -> Unit,
    onHistoryDelete: (String) -> Unit,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.5f
    Column(modifier = Modifier.graphicsLayer(alpha = alpha)) {
        SectionHeader(Icons.Outlined.FolderOpen, "工作目录", "Git 仓库路径")
        Spacer(Modifier.height(7.dp))
        // 输入框 + 浏览按钮：去掉冗余的 leadingIcon，整体更利落
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = cwd,
                onValueChange = onCwdChange,
                enabled = enabled,
                placeholder = { Text("选择或输入路径…", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier.weight(1f).height(44.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                shape = RoundedCornerShape(12.dp),
                colors = compactTextFieldColors(),
            )
            Spacer(Modifier.width(6.dp))
            FilledTonalIconButton(
                onClick = onBrowse,
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = purpleAccent.copy(alpha = 0.12f),
                    contentColor = purpleAccent,
                ),
            ) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = "浏览")
            }
        }

        // 最近使用：改成横向可滑动 chip，节省垂直空间
        if (cwdHistory.isNotEmpty()) {
            Spacer(Modifier.height(7.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "最近",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                cwdHistory.take(8).forEach { item ->
                    RecentPathChip(
                        path = item.path,
                        selected = item.path == cwd,
                        onClick = { onCwdChange(item.path) },
                        onRemove = { onHistoryDelete(item.path) },
                    )
                }
            }
        }
    }
}

/** 横向展示的历史路径 chip，触摸目标 ≥ 36dp */
@Composable
private fun RecentPathChip(
    path: String,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val parts = path.split("/").filter { it.isNotEmpty() }
    val shortPath = if (parts.size > 2) parts.takeLast(2).joinToString("/") else path
    val bg = if (selected) purpleAccent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
    val borderColor = if (selected) purpleAccent.copy(alpha = 0.32f) else Color.Transparent
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = androidx.compose.foundation.BorderStroke(0.8.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.height(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                shortPath,
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .clickable(onClick = onClick)
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) purpleAccent else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(28.dp)
                    .clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Close, contentDescription = "移除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// ─── 初始任务（含附件支持） ─────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InitialTaskSection(
    value: String,
    onValueChange: (String) -> Unit,
    attachments: List<TaskAttachment>,
    onAttach: () -> Unit,
    onRemoveAttachment: (Long) -> Unit,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.5f
    Column(modifier = Modifier.graphicsLayer(alpha = alpha)) {
        SectionHeader(Icons.Outlined.EditNote, "任务描述", "将作为 TASK: 派发给工人")
        Spacer(Modifier.height(7.dp))

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            placeholder = { Text("例如：实现 LoginViewModel，登录失败时显示 Snackbar", style = MaterialTheme.typography.bodySmall) },
            trailingIcon = {
                // 使用 Material 3 BadgedBox，比手搭 offset 的 Badge 更稳
                BadgedBox(
                    badge = {
                        if (attachments.isNotEmpty()) {
                            Badge(containerColor = purpleAccent) {
                                Text("${attachments.size}", color = Color.White)
                            }
                        }
                    },
                ) {
                    IconButton(onClick = onAttach) {
                        Icon(
                            Icons.Default.AttachFile, contentDescription = "添加附件",
                            tint = if (attachments.isNotEmpty()) purpleAccent
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp),
            maxLines = 8,
            shape = RoundedCornerShape(12.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
            colors = compactTextFieldColors(),
        )

        // 附件列表
        if (attachments.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { att ->
                    AttachmentChip(attachment = att, onRemove = { onRemoveAttachment(att.id) })
                }
            }
        }
    }
}

@Composable
private fun AttachmentChip(attachment: TaskAttachment, onRemove: () -> Unit) {
    val (icon, color) = when (attachment.type) {
        AttachmentType.IMAGE -> Pair(Icons.Default.Image, purpleAccent)
        AttachmentType.FILE -> Pair(Icons.Default.Description, emeraldAccent)
    }
    val sizeLabel = when {
        attachment.sizeBytes < 1024 -> "${attachment.sizeBytes}B"
        attachment.sizeBytes < 1024 * 1024 -> "${attachment.sizeBytes / 1024}KB"
        else -> "${"%.1f".format(attachment.sizeBytes / 1024.0 / 1024.0)}MB"
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(0.8.dp, color.copy(alpha = 0.22f)),
    ) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        attachment.name.take(20) + if (attachment.name.length > 20) "…" else "",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Normal,
                    )
                    Text(
                        sizeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                    )
                }
            }
            // 触摸目标加大到 36dp（原 20dp 太难按中）
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(36.dp)
                    .clip(RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Close, contentDescription = "移除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// ─── 操作按钮行 ───────────────────────────────────────────────

@Composable
private fun ActionRow(
    state: WorkflowUiState,
    onStart: () -> Unit,
    onAbort: () -> Unit,
) {
    val isIdle = state.pipelineState == WorkflowState.IDLE
    val isTerminal = state.pipelineState == WorkflowState.DONE || state.pipelineState == WorkflowState.ABORT
    val canStart = (isIdle || isTerminal) && !state.isStarting
    val canAbort = !isIdle && !isTerminal && !state.isAborting

    val startGradient = Brush.horizontalGradient(
        listOf(Color(0xFF00B894), Color(0xFF00CEC9))
    )
    val disabledBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 启动按钮（渐变）
        Box(
            modifier = Modifier
                .weight(1f)
                .height(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (canStart) startGradient else Brush.linearGradient(listOf(disabledBg, disabledBg)))
                .clickable(enabled = canStart, onClick = onStart),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.isStarting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    state.uploadProgress?.let { prog ->
                        Spacer(Modifier.width(6.dp))
                        Text("上传 $prog", fontSize = 12.sp, color = Color.White)
                    }
                } else {
                    Icon(Icons.Default.PlayArrow, null, tint = if (canStart) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                    Spacer(Modifier.width(6.dp))
                    val label = when (state.pipelineState) {
                        WorkflowState.IDLE -> "启动流水线"
                        WorkflowState.WORKER_CODE -> "编码中…"
                        WorkflowState.BRAIN_REVIEW -> "审查中…"
                        WorkflowState.BRAIN_RECOVER -> "决策中…"
                        WorkflowState.DONE -> "重新启动"
                        WorkflowState.ABORT -> "重新启动"
                        else -> "已运行"
                    }
                    Text(
                        label,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = if (canStart) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                }
            }
        }
        // 中断按钮
        OutlinedButton(
            onClick = onAbort,
            enabled = canAbort,
            modifier = Modifier.weight(0.6f).height(50.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = redAccent),
            border = androidx.compose.foundation.BorderStroke(
                if (canAbort) 1.2.dp else 0.9.dp,
                if (canAbort) redAccent else MaterialTheme.colorScheme.outlineVariant,
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (state.isAborting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = redAccent,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(Icons.Default.Cancel, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("中断", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            }
        }
    }
}

private fun formatElapsed(ms: Long): String {
    if (ms < 1000) return "${ms}ms"
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "%dm %02ds".format(s / 60, s % 60)
        else -> "%dh %02dm".format(s / 3600, (s % 3600) / 60)
    }
}
