package com.cdp.remote.presentation.screen.scheduler

/**
 * 调度管理页 UI 状态。
 */
data class SchedulerUiState(
    val tasks: List<ScheduledTaskUi> = emptyList(),
    val editing: TaskDraft? = null,
    val availableIdes: List<IdeInfo> = emptyList(),
    val isLoadingIdes: Boolean = false,
    val toastMessage: String? = null
)

/** 展示用的任务条目 */
data class ScheduledTaskUi(
    val id: String,
    val targetIde: String,
    val targetPort: Int = 0,
    val prompt: String,
    val ruleLabel: String,
    val intervalMinutes: Int = 5,
    val cronExpression: String = "",
    val fixedSessionTitle: String = "",
    val scheduleType: ScheduleType = ScheduleType.INTERVAL,
    val isRunning: Boolean,
    val paused: Boolean = false,
    val executionCount: Int = 0
)

/** 新建/编辑任务时的草稿 */
data class TaskDraft(
    val id: String = "",
    val targetIde: String = "",
    val targetPort: Int = 0,
    val prompt: String = "",
    val scheduleType: ScheduleType = ScheduleType.INTERVAL,
    val intervalMinutes: Int = 5,
    val fixedSessionTitle: String = "",
    val cronExpression: String = "*/30 * * * *"
)

enum class ScheduleType { INTERVAL, CRON }

/** 从 Relay 获取到的在线 IDE */
data class IdeInfo(
    val name: String,
    val port: Int,
    val title: String,
    val emoji: String = "",
    val wsUrl: String = "",
    val workspace: String = ""
)
