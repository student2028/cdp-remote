package com.cdp.remote.presentation.screen.workflow

import com.cdp.remote.presentation.screen.hosts.CwdHistoryItem
import com.cdp.remote.presentation.screen.hosts.FolderBrowserState
import com.cdp.remote.presentation.screen.scheduler.IdeInfo
import java.util.concurrent.atomic.AtomicLong

private val nextAttachmentId = AtomicLong(1)

/**
 * 流水线管理页 UI 状态。
 *
 * 后端（Relay）模块：POST /workflow/start、POST /workflow/abort、GET /workflow/status
 * 详见 docs/git_driven_agent_workflow.md
 */
data class WorkflowUiState(
    // ── 启动表单 ──
    val brainIde: String = "",
    val brainSelectedPort: Int = 0,
    val workerIde: String = "",
    val workerSelectedPort: Int = 0,
    val initialTask: String = "",
    val cwd: String = "",

    // ── IDE 在线列表（从 /targets 拉取） ──
    val availableIdes: List<IdeInfo> = emptyList(),
    val isLoadingIdes: Boolean = false,

    // ── 目录浏览器 & 历史（复用 LaunchIdeDialog 的基础设施） ──
    val folderBrowserState: FolderBrowserState = FolderBrowserState(),
    val cwdHistory: List<CwdHistoryItem> = emptyList(),

    // ── 初始任务附件（图片 / 文件） ──
    val attachments: List<TaskAttachment> = emptyList(),

    // ── 流水线运行时状态（从 /workflow/status 轮询） ──
    val pipelineState: WorkflowState = WorkflowState.IDLE,
    val brainPort: Int? = null,
    val workerPort: Int? = null,
    val elapsedMs: Long = 0,
    val warned: Boolean = false,
    val activeCwd: String? = null,
    /** 运行中的大脑 IDE 名称 */
    val activeBrainIde: String? = null,
    /** 运行中的工人 IDE 名称 */
    val activeWorkerIde: String? = null,
    val reviewRound: Int = 0,
    val minReviewRounds: Int = 3,
    val lastReviewVerdict: String? = null,
    val eventLog: List<WorkflowEvent> = emptyList(),

    // ── 操作中标志 ──
    val isStarting: Boolean = false,
    val isAborting: Boolean = false,
    /** 上传进度："2/5" 或 null */
    val uploadProgress: String? = null,

    // ── Relay 错误/终态 ──
    /** Relay 最近一次错误（如消息发送失败） */
    val lastError: String? = null,
    /** 最近一次流水线完成状态（DONE/ABORT） */
    val lastFinishedState: String? = null,

    // ── Toast / 错误 ──
    val toastMessage: String? = null,
)

/** 初始任务附件（不持有 base64，数据缓存在 cacheDir） */
data class TaskAttachment(
    val id: Long = nextAttachmentId.getAndIncrement(),
    val type: AttachmentType,
    val name: String,
    /** 经安全清洗后的文件名（与 Relay 端同逻辑），用于实际落盘路径 */
    val safeName: String = sanitizeFileName(name),
    /** MIME 类型 */
    val mimeType: String = "",
    /** 缓存文件的绝对路径（base64 数据写入此文件，不在内存中持有） */
    val cachePath: String = "",
    /** 文件大小（字节） */
    val sizeBytes: Long = 0,
)

data class WorkflowEvent(
    val type: String = "",
    val from: String = "",
    val to: String = "",
    val verb: String = "",
    val hash: String? = null,
    val summary: String = "",
    val time: Long = 0,
)

/** 与 Relay 端 safeName 逻辑完全一致：仅保留 [a-zA-Z0-9._-]，其余替换为 _ */
private val SAFE_NAME_REGEX = Regex("""[^a-zA-Z0-9.\-_]""")
fun sanitizeFileName(name: String): String = name.replace(SAFE_NAME_REGEX, "_")

enum class AttachmentType {
    IMAGE, FILE
}

/**
 * 流水线状态枚举，与 Relay 端 WORKFLOW_TRANSITIONS 表保持一致。
 */
enum class WorkflowState(val displayName: String, val badgeEmoji: String) {
    IDLE(          "空闲",         "💤"),
    BRAIN_PLAN(    "大脑规划中",    "📋"),
    WORKER_CODE(   "工人编码中",    "👷"),
    BRAIN_REVIEW(  "大脑审查中",    "🧠"),
    BRAIN_RECOVER( "大脑决策中",    "🩺"),
    // DONE/ABORT 是终态动词，Relay 正常情况会自愈回 IDLE；
    // 但竞态窗口内 App 可能轮询到，映射成 ✅/❌ 比 ❓未知 更友好。
    DONE(          "已完成",        "✅"),
    ABORT(         "已中断",        "❌"),
    UNKNOWN(       "未知",          "❓");

    companion object {
        fun from(raw: String?): WorkflowState =
            values().firstOrNull { it.name == raw } ?: UNKNOWN
    }
}
