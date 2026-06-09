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
        uiState = uiState.copy(isLoadingIdes = true, modelOptionsByPort = emptyMap())
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

    fun openNewTaskDialog(isPipeline: Boolean, isHeterogeneous: Boolean = false) {
        uiState = uiState.copy(editing = TaskDraft(isPipeline = isPipeline, isHeterogeneous = isHeterogeneous))
    }

    fun editTask(task: ScheduledTaskUi) {
        val isPipelineTask = task.pipeline.isNotEmpty() || task.isHeterogeneous
        uiState = uiState.copy(editing = TaskDraft(
            id = task.id,
            targetIde = task.targetIde,
            targetPort = task.targetPort,
            prompt = task.prompt,
            scheduleType = task.scheduleType,
            intervalMinutes = task.intervalMinutes,
            sessionMode = task.sessionMode,
            fixedSessionTitle = task.fixedSessionTitle,
            model = task.model,
            cronExpression = task.cronExpression,
            maxRuns = task.maxRuns,
            isPipeline = isPipelineTask,
            isHeterogeneous = task.isHeterogeneous,
            projectName = task.projectName,
            pipeline = if (isPipelineTask) task.pipeline else listOf(
                PipelineStage(prompt = "", model = "", delayMinutes = 0),
                PipelineStage(prompt = "", model = "", delayMinutes = 5)
            )
        ))
        if (isPipelineTask && !task.isHeterogeneous) {
            loadModelOptionsForIde(task.targetIde, task.targetPort)
        }
        val targetLower = task.targetIde.lowercase()
        if (targetLower.contains("codex") || targetLower.contains("antigravity")) {
            loadProjectOptionsForIde(task.targetIde, task.targetPort)
        }
    }

    fun closeDialog() {
        uiState = uiState.copy(editing = null)
    }

    fun updateDraft(draft: TaskDraft) {
        uiState = uiState.copy(editing = draft)
    }

    fun loadModelOptionsForIde(ideName: String, port: Int) {
        if (relayBase.isBlank() || ideName.isBlank() || port <= 0) return
        if (uiState.modelOptionsByPort.containsKey(port) || uiState.loadingModelOptionsPorts.contains(port)) return

        uiState = uiState.copy(loadingModelOptionsPorts = uiState.loadingModelOptionsPorts + port)
        viewModelScope.launch {
            try {
                val models = fetchModelOptions(ideName, port)
                uiState = uiState.copy(
                    modelOptionsByPort = uiState.modelOptionsByPort + (port to models),
                    loadingModelOptionsPorts = uiState.loadingModelOptionsPorts - port
                )
            } catch (e: Exception) {
                Log.w(TAG, "拉取 IDE 模型列表失败: ${e.message}")
                uiState = uiState.copy(
                    loadingModelOptionsPorts = uiState.loadingModelOptionsPorts - port
                )
            }
        }
    }

    fun loadProjectOptionsForIde(ideName: String, port: Int) {
        if (relayBase.isBlank() || ideName.isBlank() || port <= 0) return
        val targetLower = ideName.lowercase()
        if (!targetLower.contains("codex") && !targetLower.contains("antigravity")) return
        if (uiState.projectOptionsByPort.containsKey(port) || uiState.loadingProjectsPorts.contains(port)) return

        uiState = uiState.copy(loadingProjectsPorts = uiState.loadingProjectsPorts + port)
        viewModelScope.launch {
            try {
                val projects = fetchProjectOptions(ideName, port)
                uiState = uiState.copy(
                    projectOptionsByPort = uiState.projectOptionsByPort + (port to projects),
                    loadingProjectsPorts = uiState.loadingProjectsPorts - port
                )
            } catch (e: Exception) {
                Log.w(TAG, "拉取 IDE 项目列表失败: ${e.message}")
                uiState = uiState.copy(
                    loadingProjectsPorts = uiState.loadingProjectsPorts - port
                )
            }
        }
    }

    fun loadSessionOptionsForIde(ideName: String, port: Int, projectName: String) {
        if (relayBase.isBlank() || ideName.isBlank() || port <= 0) return
        val cacheKey = "${port}:${projectName}"
        if (uiState.loadingSessionsKeys.contains(cacheKey)) return

        uiState = uiState.copy(loadingSessionsKeys = uiState.loadingSessionsKeys + cacheKey)
        viewModelScope.launch {
            try {
                val sessions = fetchSessionOptions(ideName, port, projectName)
                uiState = uiState.copy(
                    sessionOptionsByKey = uiState.sessionOptionsByKey + (cacheKey to sessions),
                    loadingSessionsKeys = uiState.loadingSessionsKeys - cacheKey
                )
            } catch (e: Exception) {
                Log.w(TAG, "拉取 IDE 会话列表失败: ${e.message}")
                uiState = uiState.copy(
                    loadingSessionsKeys = uiState.loadingSessionsKeys - cacheKey
                )
            }
        }
    }

    fun saveTask() {
        val draft = uiState.editing ?: return

        // 流水线有效阶段（提前计算，避免重复 filter）
        val validStages = if (draft.isPipeline) draft.pipeline.filter { it.prompt.isNotBlank() } else emptyList()

        // 流水线模式校验
        if (draft.isHeterogeneous) {
            if (validStages.size < 2 || validStages.any { it.targetIde.isBlank() }) {
                uiState = uiState.copy(toastMessage = "请填写至少两个阶段的提示词并为它们选择目标 IDE")
                return
            }
        } else if (draft.isPipeline) {
            if (draft.targetIde.isBlank() || validStages.size < 2) {
                uiState = uiState.copy(toastMessage = "请填写目标 IDE 和至少两个阶段的提示词")
                return
            }
        } else {
            if (draft.targetIde.isBlank() || draft.prompt.isBlank()) {
                uiState = uiState.copy(toastMessage = "请填写目标 IDE 和提示词")
                return
            }
        }

        val isEditing = draft.id.isNotBlank()

        viewModelScope.launch {
            try {
                val body = JSONObject().apply {
                    if (isEditing) put("id", draft.id)
                    put("targetIde", draft.targetIde)
                    put("targetPort", draft.targetPort)
                    put("scheduleType", draft.scheduleType.name)
                    put("intervalMinutes", draft.intervalMinutes)
                    put("cronExpression", draft.cronExpression)
                    put("fixedSessionTitle", draft.fixedSessionTitle)
                    put("sessionMode", draft.sessionMode.name)
                    put("model", draft.model)
                    put("maxRuns", draft.maxRuns)
                    put("isHeterogeneous", draft.isHeterogeneous)
                    put("projectName", draft.projectName)

                    if (draft.isPipeline) {
                        // 流水线模式：prompt 用第一个阶段的（向后兼容），pipeline 传完整阶段列表
                        put("prompt", validStages.firstOrNull()?.prompt ?: "")
                        val pipelineArr = org.json.JSONArray()
                        for (stage in validStages) {
                            pipelineArr.put(JSONObject().apply {
                                put("prompt", stage.prompt)
                                put("model", stage.model)
                                put("delayMinutes", stage.delayMinutes)
                                put("targetIde", stage.targetIde)
                                put("targetPort", stage.targetPort)
                            })
                        }
                        put("pipeline", pipelineArr)
                    } else {
                        put("prompt", draft.prompt)
                        // 清空 pipeline
                        put("pipeline", org.json.JSONArray())
                    }
                }

                val result = postJson("$relayBase/scheduler", body.toString())
                if (result.has("success") && result.get("success").asBoolean) {
                    uiState = uiState.copy(
                        editing = null,
                        toastMessage = if (isEditing) "任务已更新 ✅" else "任务已启动 ✅"
                    )
                    refreshTasks()
                } else {
                    val err = result.get("error")?.asString ?: "未知错误"
                    uiState = uiState.copy(toastMessage = "${if (isEditing) "更新" else "创建"}失败: $err")
                }
            } catch (e: Exception) {
                uiState = uiState.copy(toastMessage = "${if (isEditing) "更新" else "创建"}失败: ${e.message}")
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
        val request = Request.Builder().url("$relayBase/targets?expandUitty=true").build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw IllegalStateException(extractErrorMessage(body, "HTTP ${response.code}"))
            }
            parseIdesJsonOrThrow(body)
        }
    }

    private suspend fun fetchModelOptions(ideName: String, port: Int): List<String> = withContext(Dispatchers.IO) {
        val url = "$relayBase/scheduler/models?port=$port&ide=${java.net.URLEncoder.encode(ideName, "UTF-8")}"
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw IllegalStateException(extractErrorMessage(body, "HTTP ${response.code}"))
            }
            parseModelOptionsJson(body)
        }
    }

    private suspend fun fetchProjectOptions(ideName: String, port: Int): List<String> = withContext(Dispatchers.IO) {
        val url = "$relayBase/codex/projects?port=$port&ide=${java.net.URLEncoder.encode(ideName, "UTF-8")}"
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw IllegalStateException(extractErrorMessage(body, "HTTP ${response.code}"))
            }
            parseProjectOptionsJson(body)
        }
    }

    private suspend fun fetchSessionOptions(ideName: String, port: Int, projectName: String): List<String> = withContext(Dispatchers.IO) {
        val url = "$relayBase/codex/sessions?port=$port&ide=${java.net.URLEncoder.encode(ideName, "UTF-8")}&project=${java.net.URLEncoder.encode(projectName, "UTF-8")}"
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw IllegalStateException(extractErrorMessage(body, "HTTP ${response.code}"))
            }
            parseSessionOptionsJson(body)
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
                val projectName = obj.get("projectName")?.asString ?: ""
                val isHeterogeneous = obj.get("isHeterogeneous")?.asBoolean ?: false

                // 解析 pipeline 阶段
                val pipelineArr = obj.getAsJsonArray("pipeline")
                val pipeline = pipelineArr?.mapNotNull { stageEl ->
                    try {
                        val stageObj = stageEl.asJsonObject
                        PipelineStage(
                            prompt = stageObj.get("prompt")?.asString ?: "",
                            model = stageObj.get("model")?.asString ?: "",
                            delayMinutes = stageObj.get("delayMinutes")?.asInt ?: 0,
                            targetIde = stageObj.get("targetIde")?.asString ?: "",
                            targetPort = stageObj.get("targetPort")?.asInt ?: 0
                        )
                    } catch (_: Exception) { null }
                } ?: emptyList()

                ScheduledTaskUi(
                    id = obj.get("id")?.asString ?: "",
                    targetIde = obj.get("targetIde")?.asString ?: "",
                    targetPort = obj.get("targetPort")?.asInt ?: 0,
                    prompt = obj.get("prompt")?.asString ?: "",
                    ruleLabel = ruleLabel,
                    intervalMinutes = intervalMin,
                    cronExpression = cronExpr,
                    fixedSessionTitle = obj.get("fixedSessionTitle")?.asString ?: "",
                    sessionMode = try { SessionMode.valueOf(obj.get("sessionMode")?.asString ?: "NEW_EACH_TIME") } catch (_: Exception) { SessionMode.NEW_EACH_TIME },
                    model = obj.get("model")?.asString ?: "",
                    projectName = projectName,
                    scheduleType = if (schedType == "CRON") ScheduleType.CRON else ScheduleType.INTERVAL,
                    isRunning = obj.get("isRunning")?.asBoolean ?: false,
                    paused = obj.get("paused")?.asBoolean ?: false,
                    executionCount = obj.get("executionCount")?.asInt ?: 0,
                    maxRuns = obj.get("maxRuns")?.asInt ?: 0,
                    isHeterogeneous = isHeterogeneous,
                    pipeline = pipeline,
                    currentStage = obj.get("currentStage")?.asInt ?: -1
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

        internal fun parseModelOptionsJson(json: String): List<String> {
            return try {
                val root = JsonParser.parseString(json).asJsonObject
                if (root.has("success") && !root.get("success").asBoolean) return emptyList()
                val arr = root.getAsJsonArray("models") ?: return emptyList()
                arr.mapNotNull { el ->
                    el.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
                }.distinct()
            } catch (_: Exception) {
                emptyList()
            }
        }

        internal fun parseProjectOptionsJson(json: String): List<String> {
            return try {
                val root = JsonParser.parseString(json).asJsonObject
                if (root.has("success") && !root.get("success").asBoolean) return emptyList()
                val arr = root.getAsJsonArray("projects") ?: return emptyList()
                arr.mapNotNull { el ->
                    when {
                        el.isJsonPrimitive -> el.asString.trim().takeIf { it.isNotEmpty() }
                        el.isJsonObject -> el.asJsonObject.get("name")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                        else -> null
                    }
                }.distinct()
            } catch (_: Exception) {
                emptyList()
            }
        }

        internal fun parseSessionOptionsJson(json: String): List<String> {
            return try {
                val root = JsonParser.parseString(json).asJsonObject
                if (root.has("success") && !root.get("success").asBoolean) return emptyList()
                val arr = root.getAsJsonArray("sessions") ?: return emptyList()
                arr.mapNotNull { el ->
                    el.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
                }.distinct()
            } catch (_: Exception) {
                emptyList()
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
