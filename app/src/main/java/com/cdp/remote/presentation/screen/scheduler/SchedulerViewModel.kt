package com.cdp.remote.presentation.screen.scheduler

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cdp.remote.data.cdp.IdeTargetsParser
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 调度管理页 ViewModel — 纯 HTTP 客户端，所有调度逻辑和存储在 Relay 端。
 *
 * Android 只做 UI 管理（遥控器角色）：
 * - GET  /scheduler          → 拉取任务列表
 * - POST /scheduler          → 创建/更新任务
 * - DELETE /scheduler?id=xxx → 取消任务
 * - GET  /targets            → 拉取在线 IDE 列表
 */
class SchedulerViewModel(application: Application) : AndroidViewModel(application) {



    var uiState by mutableStateOf(SchedulerUiState())
        private set

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var relayBase = "" // e.g. "http://100.106.253.39:19336"

    // ─── 公开 API ─────────────────────────────────────────────────

    fun init(hostIp: String, hostPort: Int) {
        relayBase = "http://$hostIp:$hostPort"
        refreshAll()
    }

    /** 同时拉取 IDE 列表 + 任务列表 */
    fun refreshAll() {
        refreshIdeList()
        refreshTasks()
    }

    fun refreshIdeList() {
        uiState = uiState.copy(isLoadingIdes = true)
        viewModelScope.launch {
            try {
                val ides = fetchIdes()
                uiState = uiState.copy(availableIdes = ides, isLoadingIdes = false)
            } catch (e: Exception) {
                Log.e(TAG, "拉取 IDE 列表失败", e)
                uiState = uiState.copy(isLoadingIdes = false, toastMessage = "拉取 IDE 失败: ${e.message}")
            }
        }
    }

    fun refreshTasks() {
        viewModelScope.launch {
            try {
                val tasks = fetchTasks()
                uiState = uiState.copy(tasks = tasks)
            } catch (e: Exception) {
                Log.e(TAG, "拉取任务列表失败", e)
                uiState = uiState.copy(toastMessage = "拉取任务失败: ${e.message}")
            }
        }
    }

    fun openNewTaskDialog() {
        uiState = uiState.copy(editing = TaskDraft())
    }

    fun closeDialog() {
        uiState = uiState.copy(editing = null)
    }

    fun updateDraft(draft: TaskDraft) {
        uiState = uiState.copy(editing = draft)
    }

    fun saveTask() {
        val draft = uiState.editing ?: return
        if (draft.targetIde.isBlank() || draft.prompt.isBlank()) {
            uiState = uiState.copy(toastMessage = "请填写目标 IDE 和提示词")
            return
        }

        viewModelScope.launch {
            try {
                val body = JSONObject().apply {
                    if (draft.id.isNotBlank()) put("id", draft.id)
                    put("targetIde", draft.targetIde)
                    put("targetPort", draft.targetPort)
                    put("prompt", draft.prompt)
                    put("scheduleType", draft.scheduleType.name)
                    put("intervalMinutes", draft.intervalMinutes)
                    put("cronExpression", draft.cronExpression)
                    put("fixedSessionTitle", draft.fixedSessionTitle)
                }

                val result = postJson("$relayBase/scheduler", body.toString())
                if (result.has("success") && result.get("success").asBoolean) {
                    uiState = uiState.copy(editing = null, toastMessage = "任务已启动 ✅")
                    refreshTasks()
                } else {
                    val err = result.get("error")?.asString ?: "未知错误"
                    uiState = uiState.copy(toastMessage = "创建失败: $err")
                }
            } catch (e: Exception) {
                uiState = uiState.copy(toastMessage = "创建失败: ${e.message}")
            }
        }
    }

    fun cancelTask(taskId: String) {
        viewModelScope.launch {
            try {
                httpDelete("$relayBase/scheduler?id=$taskId")
                uiState = uiState.copy(
                    tasks = uiState.tasks.filter { it.id != taskId },
                    toastMessage = "已删除"
                )
            } catch (e: Exception) {
                uiState = uiState.copy(toastMessage = "删除失败: ${e.message}")
            }
        }
    }

    fun pauseTask(taskId: String) {
        viewModelScope.launch {
            try {
                requireSuccessOrThrow(
                    postJson("$relayBase/scheduler/pause?id=$taskId", "{}"),
                    "暂停失败"
                )
                uiState = uiState.copy(
                    tasks = uiState.tasks.map {
                        if (it.id == taskId) it.copy(paused = true, isRunning = false) else it
                    },
                    toastMessage = "已暂停 ⏸️"
                )
            } catch (e: Exception) {
                uiState = uiState.copy(toastMessage = "暂停失败: ${e.message}")
            }
        }
    }

    fun resumeTask(taskId: String) {
        viewModelScope.launch {
            try {
                requireSuccessOrThrow(
                    postJson("$relayBase/scheduler/resume?id=$taskId", "{}"),
                    "恢复失败"
                )
                uiState = uiState.copy(
                    tasks = uiState.tasks.map {
                        if (it.id == taskId) it.copy(paused = false, isRunning = true) else it
                    },
                    toastMessage = "已恢复 ▶️"
                )
            } catch (e: Exception) {
                uiState = uiState.copy(toastMessage = "恢复失败: ${e.message}")
            }
        }
    }

    fun triggerTask(taskId: String) {
        viewModelScope.launch {
            try {
                requireSuccessOrThrow(
                    postJson("$relayBase/scheduler/trigger?id=$taskId", "{}"),
                    "触发失败"
                )
                uiState = uiState.copy(toastMessage = "已手动触发 ⚡")
                // 延迟刷新以获取更新后的执行次数
                kotlinx.coroutines.delay(1000)
                refreshTasks()
            } catch (e: Exception) {
                uiState = uiState.copy(toastMessage = "触发失败: ${e.message}")
            }
        }
    }

    fun dismissToast() {
        uiState = uiState.copy(toastMessage = null)
    }

    // ─── HTTP 请求 ────────────────────────────────────────────────

    private suspend fun fetchTasks(): List<ScheduledTaskUi> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$relayBase/scheduler").build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw IllegalStateException(extractErrorMessage(body, "HTTP ${response.code}"))
            }
            parseTasksJsonOrThrow(body)
        }
    }

    private suspend fun fetchIdes(): List<IdeInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$relayBase/targets").build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw IllegalStateException(extractErrorMessage(body, "HTTP ${response.code}"))
            }
            parseIdesJsonOrThrow(body)
        }
    }

    companion object {
        private const val TAG = "SchedulerVM"

        /** 解析调度任务 JSON (可独立测试) */
        internal fun parseTasksJson(json: String): List<ScheduledTaskUi> {
            return try { parseTasksJsonOrThrow(json) } catch (e: Exception) { emptyList() }
        }

        /** 解析 IDE 列表 JSON (可独立测试) */
        internal fun parseIdesJson(json: String): List<IdeInfo> {
            return try { parseIdesJsonOrThrow(json) } catch (e: Exception) { emptyList() }
        }

        internal fun parseTasksJsonOrThrow(json: String): List<ScheduledTaskUi> {
            val root = JsonParser.parseString(json).asJsonObject
            val tasks = root.getAsJsonArray("tasks")
                ?: throw IllegalStateException("缺少 tasks 字段")
            return tasks.map { el ->
                val obj = el.asJsonObject
                val schedType = obj.get("scheduleType")?.asString ?: "INTERVAL"
                val intervalMin = obj.get("intervalMinutes")?.asInt ?: 5
                val cronExpr = obj.get("cronExpression")?.asString ?: ""
                val ruleLabel = if (schedType == "CRON") "cron: $cronExpr" else "每 $intervalMin 分钟"
                ScheduledTaskUi(
                    id = obj.get("id")?.asString ?: "",
                    targetIde = obj.get("targetIde")?.asString ?: "",
                    targetPort = obj.get("targetPort")?.asInt ?: 0,
                    prompt = obj.get("prompt")?.asString ?: "",
                    ruleLabel = ruleLabel,
                    intervalMinutes = intervalMin,
                    cronExpression = cronExpr,
                    fixedSessionTitle = obj.get("fixedSessionTitle")?.asString ?: "",
                    scheduleType = if (schedType == "CRON") ScheduleType.CRON else ScheduleType.INTERVAL,
                    isRunning = obj.get("isRunning")?.asBoolean ?: false,
                    paused = obj.get("paused")?.asBoolean ?: false,
                    executionCount = obj.get("executionCount")?.asInt ?: 0
                )
            }
        }

        internal fun parseIdesJsonOrThrow(json: String): List<IdeInfo> {
            return IdeTargetsParser.parseInstances(json).map { instance ->
                IdeInfo(
                    name = instance.name,
                    port = instance.port,
                    title = instance.title,
                    emoji = instance.emoji,
                    wsUrl = instance.wsUrl,
                    workspace = instance.workspace
                )
            }
        }

        internal fun requireSuccessOrThrow(
            response: com.google.gson.JsonObject,
            defaultMessage: String
        ): com.google.gson.JsonObject {
            if (response.has("success") && !response.get("success").asBoolean) {
                throw IllegalStateException(response.get("error")?.asString ?: defaultMessage)
            }
            return response
        }

        internal fun extractErrorMessage(body: String, fallback: String): String {
            return try {
                val obj = JsonParser.parseString(body).asJsonObject
                obj.get("error")?.asString ?: fallback
            } catch (e: Exception) {
                fallback
            }
        }
    }

    private suspend fun postJson(url: String, json: String): com.google.gson.JsonObject =
        withContext(Dispatchers.IO) {
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            httpClient.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                if (!response.isSuccessful) {
                    throw IllegalStateException(extractErrorMessage(respBody, "HTTP ${response.code}"))
                }
                JsonParser.parseString(respBody).asJsonObject
            }
        }

    private suspend fun httpDelete(url: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).delete().build()
        httpClient.newCall(request).execute().close()
    }
}
