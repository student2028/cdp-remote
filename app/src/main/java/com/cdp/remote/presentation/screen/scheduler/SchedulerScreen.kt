package com.cdp.remote.presentation.screen.scheduler

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

// IDE 卡片颜色配置
private data class IdeStyle(
    val icon: ImageVector,
    val iconColor: Color,
    val bgColor: Color,
    val chipBg: Color     // 更深一层的 chip 背景
)

private val ideStyles = mapOf(
    "Antigravity" to IdeStyle(Icons.Default.AutoAwesome, Color(0xFF6C5CE7), Color(0xFFF3F0FF), Color(0xFFE8E0FF)),
    "Devin" to IdeStyle(Icons.Default.Air, Color(0xFF00B894), Color(0xFFECFDF5), Color(0xFFD0F5E8)),
    "Cursor" to IdeStyle(Icons.Default.Mouse, Color(0xFF00CEC9), Color(0xFFE0F7FA), Color(0xFFB2EBF2)),
    "Codex" to IdeStyle(Icons.Default.Code, Color(0xFFE17055), Color(0xFFFFF0ED), Color(0xFFFFDDD6)),
    "DSME" to IdeStyle(Icons.Default.Terminal, Color(0xFF636E72), Color(0xFFF1F2F6), Color(0xFFE8EAED)),
    "uitty" to IdeStyle(Icons.Default.Terminal, Color(0xFF636E72), Color(0xFFF1F2F6), Color(0xFFE8EAED))
)
private val defaultStyle = IdeStyle(Icons.Default.Terminal, Color(0xFF636E72), Color(0xFFF1F2F6), Color(0xFFE8EAED))
private val purpleAccent = Color(0xFF6C5CE7)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulerScreen(
    hostIp: String,
    hostPort: Int,
    onNavigateBack: () -> Unit,
    viewModel: SchedulerViewModel = viewModel()
) {
    val state = viewModel.uiState
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(hostIp, hostPort) {
        viewModel.init(hostIp, hostPort)
    }

    LaunchedEffect(state.toastMessage) {
        if (state.toastMessage != null) {
            kotlinx.coroutines.delay(2500)
            viewModel.dismissToast()
        }
    }

    LaunchedEffect(
        state.editing?.targetIde,
        state.editing?.targetPort,
        state.editing?.isPipeline,
        state.editing?.isHeterogeneous
    ) {
        val draft = state.editing
        if (draft != null && draft.isPipeline && !draft.isHeterogeneous) {
            viewModel.loadModelOptionsForIde(draft.targetIde, draft.targetPort)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, null, tint = purpleAccent, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("任务调度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (state.tasks.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Badge(containerColor = purpleAccent) {
                                Text("${state.tasks.size}", color = Color.White)
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshIdeList() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    when(selectedTab) {
                        0 -> viewModel.openNewTaskDialog(isPipeline = false, isHeterogeneous = false)
                        1 -> viewModel.openNewTaskDialog(isPipeline = true, isHeterogeneous = false)
                        2 -> viewModel.openNewTaskDialog(isPipeline = true, isHeterogeneous = true)
                    }
                },
                containerColor = purpleAccent,
                contentColor = Color.White,
                icon = {
                    when(selectedTab) {
                        0 -> Icon(Icons.Default.Add, null)
                        1 -> Icon(Icons.Default.AccountTree, null)
                        else -> Icon(Icons.Default.AltRoute, null)
                    }
                },
                text = {
                    when(selectedTab) {
                        0 -> Text("新建简单")
                        1 -> Text("新建流水线")
                        else -> Text("新建异构")
                    }
                }
            )
        },
        snackbarHost = {
            state.toastMessage?.let { msg ->
                Snackbar(modifier = Modifier.padding(16.dp)) { Text(msg) }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
        ) {
            // IDE 状态
            item {
                IdeStatusSection(ides = state.availableIdes, isLoading = state.isLoadingIdes)
                Spacer(modifier = Modifier.height(16.dp))
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = purpleAccent,
                    divider = {}
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Send, null, modifier = Modifier.size(20.dp)) },
                        text = { Text("简单调度", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.AccountTree, null, modifier = Modifier.size(20.dp)) },
                        text = { Text("流水线", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.AltRoute, null, modifier = Modifier.size(20.dp)) },
                        text = { Text("异构流水线", fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            val filteredTasks = state.tasks.filter { task ->
                when (selectedTab) {
                    0 -> task.pipeline.isEmpty() && !task.isHeterogeneous
                    1 -> task.pipeline.isNotEmpty() && !task.isHeterogeneous
                    2 -> task.isHeterogeneous
                    else -> true
                }
            }

            if (filteredTasks.isEmpty()) {
                item { EmptyHint(selectedTab) }
            } else {
                items(filteredTasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        onEdit = { viewModel.editTask(task) },
                        onPauseResume = {
                            if (task.paused) viewModel.resumeTask(task.id)
                            else viewModel.pauseTask(task.id)
                        },
                        onTrigger = { viewModel.triggerTask(task.id) },
                        onDelete = { viewModel.cancelTask(task.id) }
                    )
                }
            }
        }
    }

    // 新建弹窗 — 使用 BottomSheet
    if (state.editing != null) {
        TaskCreateSheet(
            draft = state.editing,
            availableIdes = state.availableIdes,
            modelOptionsByPort = state.modelOptionsByPort,
            loadingModelOptionsPorts = state.loadingModelOptionsPorts,
            loadModelOptionsForIde = { name, port -> viewModel.loadModelOptionsForIde(name, port) },
            projectOptionsByPort = state.projectOptionsByPort,
            loadingProjectsPorts = state.loadingProjectsPorts,
            loadProjectOptionsForIde = { name, port -> viewModel.loadProjectOptionsForIde(name, port) },
            sessionOptionsByKey = state.sessionOptionsByKey,
            loadingSessionsKeys = state.loadingSessionsKeys,
            loadSessionOptionsForIde = { name, port, project -> viewModel.loadSessionOptionsForIde(name, port, project) },
            onDismiss = { viewModel.closeDialog() },
            onUpdate = { viewModel.updateDraft(it) },
            onSave = { viewModel.saveTask() }
        )
    }
}

// ─── IDE 状态卡片 ────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdeStatusSection(ides: List<IdeInfo>, isLoading: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        if (isLoading) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = purpleAccent)
                Spacer(modifier = Modifier.width(10.dp))
                Text("扫描在线 IDE...", style = MaterialTheme.typography.bodySmall)
            }
        } else if (ides.isEmpty()) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("无在线 IDE，请确认 Relay 已启动", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        } else {
            // 使用 FlowRow 自动换行，避免 3+ IDE 时文字溢出截断
            FlowRow(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ides.forEach { ide ->
                    val style = ideStyles[ide.name.substringBefore(":")] ?: defaultStyle
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(style.chipBg)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(style.iconColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(style.icon, null, tint = style.iconColor, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "${ide.name}:${ide.port}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

// ─── 空状态 ─────────────────────────────────────────────────

@Composable
private fun EmptyHint(selectedTab: Int = 0) {
    val text = when (selectedTab) {
        0 -> "暂无简单调度任务"
        1 -> "暂无流水线任务"
        2 -> "暂无异构流水线任务"
        else -> "暂无调度任务"
    }
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 80.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Schedule, null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            Text("点击下方「新建」开始", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
        }
    }
}

// ─── 任务卡片 ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskCard(
    task: ScheduledTaskUi,
    onEdit: () -> Unit,
    onPauseResume: () -> Unit,
    onTrigger: () -> Unit,
    onDelete: () -> Unit
) {
    val style = ideStyles[task.targetIde.substringBefore(":")] ?: defaultStyle
    val isPaused = task.paused
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        onClick = onEdit
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 顶部彩色指示条（暂停时变灰）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(if (isPaused) Color(0xFFDFE6E9) else style.iconColor)
            )

            Row(
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // IDE 图标
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isPaused) Color(0xFFF1F2F6) else style.chipBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        style.icon, null,
                        tint = if (isPaused) Color(0xFFB2BEC3) else style.iconColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 运行灯
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isPaused -> Color(0xFFFDCB6E)  // 黄色 = 暂停
                                        task.isRunning -> Color(0xFF00B894) // 绿色 = 运行
                                        else -> Color(0xFFDFE6E9)
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (task.targetPort > 0) "${task.targetIde}:${task.targetPort}" else task.targetIde,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isPaused) Color(0xFFB2BEC3) else Color.Unspecified
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            task.ruleLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isPaused) Color(0xFFB2BEC3) else style.iconColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isPaused) Color(0xFFF1F2F6) else style.chipBg)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                        if (isPaused) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "已暂停",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFDCB6E),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    if (task.fixedSessionTitle.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "绑定对话: ${task.fixedSessionTitle}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isPaused) Color(0xFFB2BEC3) else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    // 流水线标签
                    if (task.pipeline.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "🔄 流水线",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isPaused) Color(0xFFB2BEC3) else Color(0xFF6C5CE7),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isPaused) Color(0xFFF1F2F6) else Color(0xFFF3F0FF))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "${task.pipeline.size} 个阶段",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isPaused) Color(0xFFB2BEC3) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    if (task.pipeline.isNotEmpty()) {
                        // 显示各阶段摘要
                        task.pipeline.forEachIndexed { idx, stage ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${idx + 1}.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isPaused) Color(0xFFB2BEC3) else Color(0xFF6C5CE7),
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                if (stage.model.isNotBlank()) {
                                    Text(
                                        "[${stage.model}]",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isPaused) Color(0xFFB2BEC3) else Color(0xFF00B894),
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(
                                    stage.prompt,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isPaused) Color(0xFFB2BEC3) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (stage.delayMinutes > 0) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "⏱${stage.delayMinutes}m",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isPaused) Color(0xFFB2BEC3) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (idx < task.pipeline.lastIndex) Spacer(modifier = Modifier.height(2.dp))
                        }
                    } else {
                        Text(
                            task.prompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isPaused) Color(0xFFB2BEC3) else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (task.executionCount > 0 || task.maxRuns > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (task.maxRuns > 0) "已执行 ${task.executionCount}/${task.maxRuns} 轮" else "已执行 ${task.executionCount} 轮",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isPaused) Color(0xFFB2BEC3) else Color(0xFF00B894),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // ─── 操作按钮行 ───
            Divider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                modifier = Modifier.padding(horizontal = 14.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // 暂停/恢复
                TextButton(onClick = onPauseResume, modifier = Modifier.weight(1f)) {
                    Icon(
                        if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isPaused) Color(0xFF00B894) else Color(0xFFFDCB6E)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (isPaused) "恢复" else "暂停",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 竖线分隔
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .align(Alignment.CenterVertically)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                )

                // 手动触发
                TextButton(onClick = onTrigger, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Default.FlashOn, null,
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFF6C5CE7)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "触发",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .align(Alignment.CenterVertically)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                )

                // 删除
                TextButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Default.Delete, null,
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFFE74C3C)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "删除",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFE74C3C)
                    )
                }
            }
        }
    }
}

// ─── 新建任务 BottomSheet ─────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskCreateSheet(
    draft: TaskDraft,
    availableIdes: List<IdeInfo>,
    modelOptionsByPort: Map<Int, List<String>>,
    loadingModelOptionsPorts: Set<Int>,
    loadModelOptionsForIde: (String, Int) -> Unit,
    projectOptionsByPort: Map<Int, List<String>>,
    loadingProjectsPorts: Set<Int>,
    loadProjectOptionsForIde: (String, Int) -> Unit,
    sessionOptionsByKey: Map<String, List<String>>,
    loadingSessionsKeys: Set<String>,
    loadSessionOptionsForIde: (String, Int, String) -> Unit,
    onDismiss: () -> Unit,
    onUpdate: (TaskDraft) -> Unit,
    onSave: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()

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
            // ── 标题 ──
            val isEditing = draft.id.isNotBlank()
            val titleText = when {
                draft.isHeterogeneous -> "新建异构流水线任务"
                draft.isPipeline -> "新建流水线任务"
                else -> "新建简单调度任务"
            }
            Text(
                if (isEditing) "编辑调度任务" else titleText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (isEditing) "修改目标 IDE、触发规则或提示词" else "选择目标 IDE，设置触发规则和要发送的提示词",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── IDE 选择网格 (2 列) ──
            val uniqueIdes = availableIdes.distinctBy { it.name to it.port }
            if (uniqueIdes.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "无在线 IDE",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            } else {
                val rows = uniqueIdes.chunked(2)
                rows.forEachIndexed { rIdx, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        row.forEach { ide ->
                            val style = ideStyles[ide.name.substringBefore(":")] ?: defaultStyle
                            val isSelected = draft.targetIde == ide.name && draft.targetPort == ide.port
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        if (isSelected) Modifier.border(2.dp, style.iconColor, RoundedCornerShape(16.dp))
                                        else Modifier
                                    ),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = style.bgColor),
                                elevation = CardDefaults.cardElevation(if (isSelected) 2.dp else 0.dp),
                                onClick = {
                                    onUpdate(draft.copy(
                                        targetIde = ide.name,
                                        targetPort = ide.port,
                                        pipeline = draft.pipeline.map { it.copy(model = "") }
                                    ))
                                }
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                                            .background(style.iconColor.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(style.icon, null, tint = style.iconColor, modifier = Modifier.size(20.dp))
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(ide.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                    Text(":${ide.port}", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        // 奇数行填充
                        if (row.size < 2) Spacer(modifier = Modifier.weight(1f))
                    }
                    if (rIdx < rows.lastIndex) Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── 调度方式 ──
            Text("调度方式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = draft.scheduleType == ScheduleType.INTERVAL,
                    onClick = { onUpdate(draft.copy(scheduleType = ScheduleType.INTERVAL)) },
                    label = { Text("固定间隔") },
                    leadingIcon = if (draft.scheduleType == ScheduleType.INTERVAL) {{
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                    }} else null
                )
                FilterChip(
                    selected = draft.scheduleType == ScheduleType.CRON,
                    onClick = { onUpdate(draft.copy(scheduleType = ScheduleType.CRON)) },
                    label = { Text("Cron 表达式") },
                    leadingIcon = if (draft.scheduleType == ScheduleType.CRON) {{
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                    }} else null
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (draft.scheduleType == ScheduleType.INTERVAL) {
                OutlinedTextField(
                    value = draft.intervalMinutes.toString(),
                    onValueChange = { v ->
                        val n = v.filter { it.isDigit() }.toIntOrNull() ?: 0
                        onUpdate(draft.copy(intervalMinutes = n.coerceIn(1, 1440)))
                    },
                    label = { Text("间隔 (分钟)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    supportingText = { Text("1 ~ 1440 分钟 (24 小时)") }
                )
            } else {
                OutlinedTextField(
                    value = draft.cronExpression,
                    onValueChange = { onUpdate(draft.copy(cronExpression = it)) },
                    label = { Text("Cron 表达式") },
                    placeholder = { Text("*/30 * * * *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    supportingText = { Text("格式: 分 时 日 月 周  (例: */30 * * * * = 每30分钟)") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            val needsProject = draft.targetIde.contains("antigravity", ignoreCase = true) || 
                               draft.targetIde.contains("codex", ignoreCase = true) || 
                               draft.pipeline.any { 
                                   it.targetIde.contains("antigravity", ignoreCase = true) || 
                                   it.targetIde.contains("codex", ignoreCase = true) 
                               }

            // ── 会话模式选择 ──
            Text("会话模式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                data class SessionModeOption(val mode: SessionMode, val label: String, val icon: ImageVector)
                val modeOptions = listOf(
                    SessionModeOption(SessionMode.NEW_EACH_TIME, "每次新建", Icons.Default.Add),
                    SessionModeOption(SessionMode.SPECIFIED, "指定会话", Icons.Default.Reorder),
                    SessionModeOption(SessionMode.SHARED, "共用会话", Icons.Default.Share)
                )
                modeOptions.forEach { opt ->
                    FilterChip(
                        selected = draft.sessionMode == opt.mode,
                        onClick = {
                            onUpdate(draft.copy(
                                sessionMode = opt.mode,
                                fixedSessionTitle = if (opt.mode == SessionMode.NEW_EACH_TIME) "" else draft.fixedSessionTitle
                            ))
                        },
                        label = { Text(opt.label, style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = if (draft.sessionMode == opt.mode) {{
                            Icon(opt.icon, null, modifier = Modifier.size(16.dp))
                        }} else null,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 指定会话模式：需要先选项目，再选会话
            if (draft.sessionMode == SessionMode.SPECIFIED) {
                Spacer(modifier = Modifier.height(8.dp))
                val sessionCacheKey = "${draft.targetPort}:${draft.projectName}"
                val sessionOptions = sessionOptionsByKey[sessionCacheKey].orEmpty()
                val isLoadingSessions = loadingSessionsKeys.contains(sessionCacheKey)

                if (needsProject && draft.projectName.isBlank()) {
                    Text(
                        "请先选择项目，再选择会话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LaunchedEffect(draft.targetIde, draft.targetPort, draft.projectName, draft.sessionMode) {
                        if (draft.targetIde.isNotBlank() && draft.targetPort > 0) {
                            loadSessionOptionsForIde(draft.targetIde, draft.targetPort, draft.projectName)
                        }
                    }
                    SchedulerSessionDropdown(
                        selected = draft.fixedSessionTitle,
                        options = sessionOptions,
                        isLoading = isLoadingSessions,
                        onSelect = { onUpdate(draft.copy(fixedSessionTitle = it)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 共用会话模式：说明文字
            if (draft.sessionMode == SessionMode.SHARED) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "所有调度任务将在同一个会话中执行，不会新建对话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // ── 指定模型（可选） ──
            if (!draft.isPipeline) {
                val liveModelOptions = modelOptionsByPort[draft.targetPort].orEmpty()
                val isLoadingModelOpts = loadingModelOptionsPorts.contains(draft.targetPort)
                LaunchedEffect(draft.targetIde, draft.targetPort) {
                    if (draft.targetIde.isNotBlank() && draft.targetPort > 0) {
                        loadModelOptionsForIde(draft.targetIde, draft.targetPort)
                    }
                }
                val allModelOptions = schedulerModelOptionsForIde(draft.targetIde, liveModelOptions)
                if (allModelOptions.size > 1 || isLoadingModelOpts) {
                    var modelExpanded by remember { mutableStateOf(false) }
                    val modelLabel = allModelOptions.find { it.value == draft.model }?.label
                        ?: draft.model.takeIf { it.isNotBlank() }
                        ?: "默认 (IDE 当前模型)"
                    ExposedDropdownMenuBox(
                        expanded = modelExpanded,
                        onExpandedChange = { modelExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = modelLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(if (isLoadingModelOpts) "指定模型 (加载中...)" else "指定模型 (可选)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        ExposedDropdownMenu(
                            expanded = modelExpanded,
                            onDismissRequest = { modelExpanded = false }
                        ) {
                            allModelOptions.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = { onUpdate(draft.copy(model = opt.value)); modelExpanded = false }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // ── 最大轮次（可选） ──
            OutlinedTextField(
                value = if (draft.maxRuns > 0) draft.maxRuns.toString() else "",
                onValueChange = { v ->
                    val n = v.filter { it.isDigit() }.toIntOrNull() ?: 0
                    onUpdate(draft.copy(maxRuns = n.coerceIn(0, 999)))
                },
                label = { Text("最大轮次") },
                placeholder = { Text("不限制") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                supportingText = { Text("整条流水线跑完算 1 轮；0 或留空表示不限制") }
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (needsProject) {
                val projectOptions = projectOptionsByPort[draft.targetPort].orEmpty()
                val isLoadingProjects = loadingProjectsPorts.contains(draft.targetPort)
                LaunchedEffect(draft.targetIde, draft.targetPort) {
                    if (draft.targetIde.isNotBlank() && draft.targetPort > 0) {
                        loadProjectOptionsForIde(draft.targetIde, draft.targetPort)
                    }
                }
                SchedulerProjectDropdown(
                    selected = draft.projectName,
                    options = projectOptions,
                    isLoading = isLoadingProjects,
                    onSelect = { onUpdate(draft.copy(projectName = it)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (draft.isPipeline) {
                // ── 流水线阶段编辑 ──
                val liveModelOptions = modelOptionsByPort[draft.targetPort].orEmpty()
                val isLoadingModelOptions = loadingModelOptionsPorts.contains(draft.targetPort)

                val modelOptions = schedulerModelOptionsForIde(draft.targetIde, liveModelOptions)
                if (isLoadingModelOptions) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = purpleAccent
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "正在读取当前 IDE 模型列表...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                draft.pipeline.forEachIndexed { idx, stage ->
                    val stageLiveModels = if (draft.isHeterogeneous) {
                        modelOptionsByPort[stage.targetPort].orEmpty()
                    } else liveModelOptions

                    val stageModelOptions = if (draft.isHeterogeneous) {
                        schedulerModelOptionsForIde(stage.targetIde, stageLiveModels)
                    } else {
                        schedulerModelOptionsForIde(draft.targetIde, stageLiveModels)
                    }
                    val isLoadingModel = if (draft.isHeterogeneous) {
                        loadingModelOptionsPorts.contains(stage.targetPort)
                    } else {
                        loadingModelOptionsPorts.contains(draft.targetPort)
                    }

                    PipelineStageEditor(
                        index = idx,
                        stage = stage,
                        modelOptions = stageModelOptions,
                        canDelete = draft.pipeline.size > 2,
                        isHeterogeneous = draft.isHeterogeneous,
                        availableIdes = availableIdes,
                        isLoadingModel = isLoadingModel,
                        loadModelOptions = loadModelOptionsForIde,
                        onChange = { updated ->
                            val newPipeline = draft.pipeline.toMutableList()
                            newPipeline[idx] = updated
                            onUpdate(draft.copy(pipeline = newPipeline))
                        },
                        onDelete = {
                            val newPipeline = draft.pipeline.toMutableList()
                            newPipeline.removeAt(idx)
                            onUpdate(draft.copy(pipeline = newPipeline))
                        }
                    )
                    if (idx < draft.pipeline.lastIndex) {
                        // 阶段间连接指示器
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.width(2.dp).height(16.dp)
                                    .background(Color(0xFFDFE6E9))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val nextStage = draft.pipeline.getOrNull(idx + 1)
                            Text(
                                if (nextStage != null && nextStage.delayMinutes > 0) "↓ 等待 ${nextStage.delayMinutes} 分钟" else "↓",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                // 添加阶段按钮
                if (draft.pipeline.size < 5) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val newPipeline = draft.pipeline + PipelineStage(
                                prompt = "",
                                model = "",
                                delayMinutes = 5
                            )
                            onUpdate(draft.copy(pipeline = newPipeline))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDFE6E9))
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("添加阶段")
                    }
                }
            } else {
                // ── 原有单提示词模式 ──
                Text("提示词", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.prompt,
                    onValueChange = { onUpdate(draft.copy(prompt = it)) },
                    placeholder = { Text("输入要定时发送给 IDE 的指令...") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    maxLines = 6,
                    shape = RoundedCornerShape(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── 底部按钮 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("取消") }
                val canSave = if (draft.isHeterogeneous) {
                    val validStages = draft.pipeline.filter { it.prompt.isNotBlank() }
                    validStages.size >= 2 && validStages.all { it.targetIde.isNotBlank() && it.targetPort > 0 }
                } else if (draft.isPipeline) {
                    draft.targetIde.isNotBlank() && draft.pipeline.count { it.prompt.isNotBlank() } >= 2
                } else {
                    draft.targetIde.isNotBlank() && draft.prompt.isNotBlank()
                }
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    enabled = canSave,
                    colors = ButtonDefaults.buttonColors(containerColor = purpleAccent)
                ) { Text(if (draft.id.isNotBlank()) "保存" else "启动任务", color = Color.White) }
            }
        }
    }
}

// ─── 流水线阶段编辑器 ─────────────────────────────────────────

@Composable
private fun PipelineStageEditor(
    index: Int,
    stage: PipelineStage,
    modelOptions: List<SchedulerModelOption>,
    canDelete: Boolean,
    isHeterogeneous: Boolean,
    availableIdes: List<IdeInfo>,
    isLoadingModel: Boolean,
    loadModelOptions: (String, Int) -> Unit,
    onChange: (PipelineStage) -> Unit,
    onDelete: () -> Unit
) {
    if (isHeterogeneous && stage.targetIde.isNotBlank() && stage.targetPort > 0) {
        LaunchedEffect(stage.targetIde, stage.targetPort) {
            loadModelOptions(stage.targetIde, stage.targetPort)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F9FA)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(purpleAccent),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "阶段 ${index + 1}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (canDelete) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Close, null,
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFFE74C3C)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 异构流水线 IDE 选择
            if (isHeterogeneous) {
                SchedulerIdeDropdown(
                    selectedIde = stage.targetIde,
                    selectedPort = stage.targetPort,
                    options = availableIdes,
                    onSelect = { name, port ->
                        onChange(stage.copy(targetIde = name, targetPort = port, model = ""))
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 模型 + 延迟 (同行)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SchedulerModelDropdown(
                    selected = stage.model,
                    options = modelOptions,
                    onSelect = { onChange(stage.copy(model = it)) },
                    isLoading = isLoadingModel,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = if (stage.delayMinutes > 0) stage.delayMinutes.toString() else "",
                    onValueChange = { v ->
                        val n = v.filter { it.isDigit() }.toIntOrNull() ?: 0
                        onChange(stage.copy(delayMinutes = n.coerceIn(0, 120)))
                    },
                    label = { Text("前置等待") },
                    placeholder = { Text("0") },
                    suffix = { Text("分钟") },
                    modifier = Modifier.width(120.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 提示词
            OutlinedTextField(
                value = stage.prompt,
                onValueChange = { onChange(stage.copy(prompt = it)) },
                label = { Text("提示词") },
                placeholder = { Text(if (index == 0) "例：请实现..." else "例：请审查上面的代码...") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                maxLines = 4,
                shape = RoundedCornerShape(10.dp),
                textStyle = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchedulerModelDropdown(
    selected: String,
    options: List<SchedulerModelOption>,
    onSelect: (String) -> Unit,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selected }?.label
        ?: selected.takeIf { it.isNotBlank() }
        ?: "默认"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(if (isLoading) "模型 (加载中...)" else "模型") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            textStyle = MaterialTheme.typography.bodySmall
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = {
                        onSelect(option.value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchedulerIdeDropdown(
    selectedIde: String,
    selectedPort: Int,
    options: List<IdeInfo>,
    onSelect: (String, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = if (selectedPort > 0) "$selectedIde:$selectedPort" else "选择执行 IDE"
    val uniqueOptions = options.distinctBy { it.name to it.port }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("目标 IDE") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            textStyle = MaterialTheme.typography.bodySmall
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            uniqueOptions.forEach { ide ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${ide.name}:${ide.port}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = {
                        onSelect(ide.name, ide.port)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchedulerProjectDropdown(
    selected: String,
    options: List<String>,
    isLoading: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = selected.takeIf { it.isNotBlank() } ?: "未选择 (当前工作区)"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(if (isLoading) "目标项目 (加载中...)" else "目标项目") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            textStyle = MaterialTheme.typography.bodySmall
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { proj ->
                DropdownMenuItem(
                    text = {
                        Text(
                            proj,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = {
                        onSelect(proj)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchedulerSessionDropdown(
    selected: String,
    options: List<String>,
    isLoading: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = selected.takeIf { it.isNotBlank() } ?: "选择会话..."

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(if (isLoading) "目标会话 (加载中...)" else "目标会话") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            textStyle = MaterialTheme.typography.bodySmall
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { session ->
                DropdownMenuItem(
                    text = {
                        Text(
                            session,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = {
                        onSelect(session)
                        expanded = false
                    }
                )
            }
        }
    }
}
