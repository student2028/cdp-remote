package com.cdp.remote.presentation.screen.scheduler

/**
 * 调度管理页 UI 状态。
 */
data class SchedulerUiState(
    val tasks: List<ScheduledTaskUi> = emptyList(),
    val editing: TaskDraft? = null,
    val availableIdes: List<IdeInfo> = emptyList(),
    val isLoadingIdes: Boolean = false,
    val modelOptionsByPort: Map<Int, List<String>> = emptyMap(),
    val loadingModelOptionsPorts: Set<Int> = emptySet(),
    val projectOptionsByPort: Map<Int, List<String>> = emptyMap(),
    val loadingProjectsPorts: Set<Int> = emptySet(),
    val sessionOptionsByKey: Map<String, List<String>> = emptyMap(),
    val loadingSessionsKeys: Set<String> = emptySet(),
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
    val sessionMode: SessionMode = SessionMode.NEW_EACH_TIME,
    val model: String = "",
    val scheduleType: ScheduleType = ScheduleType.INTERVAL,
    val isRunning: Boolean,
    val paused: Boolean = false,
    val executionCount: Int = 0,
    val maxRuns: Int = 0,          // 0 = 不限制
    val isHeterogeneous: Boolean = false,
    val projectName: String = "",
    val pipeline: List<PipelineStage> = emptyList(),
    val currentStage: Int = -1     // 当前正在执行的阶段（-1 = 空闲）
)

/** 流水线阶段 */
data class PipelineStage(
    val prompt: String = "",
    val model: String = "",        // 为空 = 使用 IDE 当前默认模型
    val delayMinutes: Int = 0,     // 上一阶段完成后、该阶段执行前额外等待分钟数
    val targetIde: String = "",
    val targetPort: Int = 0
)

/** 新建/编辑任务时的草稿 */
data class TaskDraft(
    val id: String = "",
    val targetIde: String = "",
    val targetPort: Int = 0,
    val prompt: String = "",
    val scheduleType: ScheduleType = ScheduleType.INTERVAL,
    val intervalMinutes: Int = 5,
    val sessionMode: SessionMode = SessionMode.NEW_EACH_TIME,
    val fixedSessionTitle: String = "",
    val model: String = "",
    val cronExpression: String = "*/30 * * * *",
    val maxRuns: Int = 0,          // 0 = 不限制
    val isPipeline: Boolean = false,
    val isHeterogeneous: Boolean = false,
    val projectName: String = "",
    val pipeline: List<PipelineStage> = listOf(
        PipelineStage(prompt = "", model = "", delayMinutes = 0),
        PipelineStage(prompt = "", model = "", delayMinutes = 5)
    )
)

enum class ScheduleType { INTERVAL, CRON }

/** 会话模式 */
enum class SessionMode {
    NEW_EACH_TIME,  // 每次新建会话
    SPECIFIED,      // 指定已有会话
    SHARED          // 所有调度共用同一个会话
}

/** 从 Relay 获取到的在线 IDE */
data class IdeInfo(
    val name: String,
    val port: Int,
    val title: String,
    val emoji: String = "",
    val wsUrl: String = "",
    val workspace: String = ""
)
