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
    "Windsurf" to IdeStyle(Icons.Default.Air, Color(0xFF00B894), Color(0xFFECFDF5), Color(0xFFD0F5E8)),
    "Cursor" to IdeStyle(Icons.Default.Mouse, Color(0xFF00CEC9), Color(0xFFE0F7FA), Color(0xFFB2EBF2)),
    "Codex" to IdeStyle(Icons.Default.Code, Color(0xFFE17055), Color(0xFFFFF0ED), Color(0xFFFFDDD6))
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

    LaunchedEffect(hostIp, hostPort) {
        viewModel.init(hostIp, hostPort)
    }

    LaunchedEffect(state.toastMessage) {
        if (state.toastMessage != null) {
            kotlinx.coroutines.delay(2500)
            viewModel.dismissToast()
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
                onClick = { viewModel.openNewTaskDialog() },
                containerColor = purpleAccent,
                contentColor = Color.White,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("新建") }
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
            }

            if (state.tasks.isEmpty()) {
                item { EmptyHint() }
            } else {
                items(state.tasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
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
                    val style = ideStyles[ide.name] ?: defaultStyle
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
private fun EmptyHint() {
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
            Text("暂无调度任务", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            Text("点击下方「新建」开始", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
        }
    }
}

// ─── 任务卡片 ────────────────────────────────────────────────

@Composable
private fun TaskCard(
    task: ScheduledTaskUi,
    onPauseResume: () -> Unit,
    onTrigger: () -> Unit,
    onDelete: () -> Unit
) {
    val style = ideStyles[task.targetIde] ?: defaultStyle
    val isPaused = task.paused
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
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
                            task.targetIde,
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
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        task.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isPaused) Color(0xFFB2BEC3) else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (task.executionCount > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "已执行 ${task.executionCount} 次",
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
            Text(
                "新建调度任务",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "选择目标 IDE，设置触发规则和要发送的提示词",
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
                            val style = ideStyles[ide.name] ?: defaultStyle
                            val isSelected = draft.targetIde == ide.name
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
                                    onUpdate(draft.copy(targetIde = ide.name, targetPort = ide.port))
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

            // ── 绑定固定对话 (可选) ──
            Text("绑定固定对话标题 (可选)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.fixedSessionTitle,
                onValueChange = { onUpdate(draft.copy(fixedSessionTitle = it)) },
                placeholder = { Text("例如: Bug Fix #123 (留空则在当前对话发送)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── 提示词 ──
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
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    enabled = draft.targetIde.isNotBlank() && draft.prompt.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = purpleAccent)
                ) { Text("启动任务", color = Color.White) }
            }
        }
    }
}
