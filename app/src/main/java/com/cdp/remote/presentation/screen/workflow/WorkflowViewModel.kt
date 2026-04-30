package com.cdp.remote.presentation.screen.workflow

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cdp.remote.presentation.screen.hosts.CwdHistoryItem
import com.cdp.remote.presentation.screen.hosts.DirItem
import com.cdp.remote.presentation.screen.scheduler.IdeInfo
import com.cdp.remote.presentation.screen.scheduler.SchedulerViewModel
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 流水线管理页 ViewModel — 纯 HTTP 客户端。
 *
 * - GET  /targets          → 在线 IDE 列表（复用 Scheduler 的解析逻辑）
 * - POST /workflow/start   → 启动流水线
 * - POST /workflow/abort   → 中断流水线
 * - GET  /workflow/status  → 轮询状态（每 2 秒一次）
 */
class WorkflowViewModel(application: Application) : AndroidViewModel(application) {

    var uiState by mutableStateOf(WorkflowUiState())
        private set

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)   // 上传大 base64 附件需要更多时间
        .build()

    private var relayBase = ""
    private var pollJob: Job? = null
    private var initialized = false   // 防重入

    // P1#6: 表单持久化
    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences("workflow_form", android.content.Context.MODE_PRIVATE)
    }

    fun init(hostIp: String, hostPort: Int) {
        val newBase = "http://$hostIp:$hostPort"
        if (initialized && relayBase == newBase) return  // 防重入
        relayBase = newBase
        initialized = true
        // P1#6: 恢复上次的表单数据
        restoreFormState()
        refreshIdes()
        refreshStatus()
        startPolling()
    }

    private fun restoreFormState() {
        val savedBrain = prefs.getString("brainIde", null)
        val savedWorker = prefs.getString("workerIde", null)
        val savedCwd = prefs.getString("cwd", null)
        val savedTask = prefs.getString("initialTask", null)
        uiState = uiState.copy(
            brainIde = savedBrain ?: uiState.brainIde,
            brainSelectedPort = prefs.getInt("brainPort", 0),
            workerIde = savedWorker ?: uiState.workerIde,
            workerSelectedPort = prefs.getInt("workerPort", 0),
            cwd = savedCwd ?: uiState.cwd,
            initialTask = savedTask ?: uiState.initialTask,
        )
    }

    private fun saveFormState() {
        prefs.edit()
            .putString("brainIde", uiState.brainIde)
            .putInt("brainPort", uiState.brainSelectedPort)
            .putString("workerIde", uiState.workerIde)
            .putInt("workerPort", uiState.workerSelectedPort)
            .putString("cwd", uiState.cwd)
            .putString("initialTask", uiState.initialTask)
            .apply()
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
        // 清理附件缓存文件
        uiState.attachments.forEach { att ->
            runCatching { File(att.cachePath).delete() }
        }
    }

    // ─── 表单更新 ──────────────────────────────────────────────────

    fun updateBrainIde(info: IdeInfo) {
        uiState = uiState.copy(
            brainIde = info.name,
            brainSelectedPort = info.port,
            cwd = if (uiState.cwd.isBlank() && info.workspace.isNotBlank()) info.workspace else uiState.cwd
        )
        saveFormState()
    }
    fun updateWorkerIde(info: IdeInfo) {
        uiState = uiState.copy(
            workerIde = info.name,
            workerSelectedPort = info.port,
            cwd = if (uiState.cwd.isBlank() && info.workspace.isNotBlank()) info.workspace else uiState.cwd
        )
        saveFormState()
    }
    fun updateInitialTask(text: String) {
        uiState = uiState.copy(initialTask = text)
        saveFormState()
    }
    fun updateCwd(path: String) {
        uiState = uiState.copy(cwd = path)
        saveFormState()
    }
    fun dismissToast() { uiState = uiState.copy(toastMessage = null) }
    fun showToast(msg: String) { uiState = uiState.copy(toastMessage = msg) }

    // ─── 附件管理 ───────────────────────────────────────────────────

    fun addAttachment(attachment: TaskAttachment) {
        uiState = uiState.copy(attachments = uiState.attachments + attachment)
    }

    fun removeAttachment(id: Long) {
        uiState = uiState.copy(attachments = uiState.attachments.filter { it.id != id })
    }

    fun clearAttachments() {
        // 清理缓存文件
        uiState.attachments.forEach { att ->
            runCatching { File(att.cachePath).delete() }
        }
        uiState = uiState.copy(attachments = emptyList())
    }

    // ─── 目录浏览器 ──────────────────────────────────────────────────

    fun openFolderBrowser() {
        val hostUrl = relayBase
        uiState = uiState.copy(
            folderBrowserState = uiState.folderBrowserState.copy(
                isOpen = true, hostUrl = hostUrl, currentPath = uiState.cwd
            )
        )
        loadDirectory(hostUrl, uiState.cwd)
    }

    fun closeFolderBrowser() {
        uiState = uiState.copy(
            folderBrowserState = uiState.folderBrowserState.copy(isOpen = false)
        )
    }

    fun loadDirectory(hostUrl: String, path: String) {
        uiState = uiState.copy(
            folderBrowserState = uiState.folderBrowserState.copy(
                isLoading = true, currentPath = path, error = null
            )
        )
        viewModelScope.launch {
            try {
                val (isSuccess, body, code) = withContext(Dispatchers.IO) {
                    val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
                    val request = Request.Builder()
                        .url("$hostUrl/dirs?path=$encodedPath")
                        .build()
                    val res = httpClient.newCall(request).execute()
                    val b = res.body?.string()
                    val s = res.isSuccessful
                    val c = res.code
                    res.close()
                    Triple(s, b, c)
                }
                if (body != null && isSuccess) {
                    val jsonObj = JsonParser.parseString(body).asJsonObject
                    val dirsArray = jsonObj.getAsJsonArray("dirs")
                    val currentElement = jsonObj.get("current")
                    val currentStr = if (currentElement != null && !currentElement.isJsonNull) currentElement.asString else ""
                    val parentElement = jsonObj.get("parent")
                    val parentStr = if (parentElement != null && !parentElement.isJsonNull) parentElement.asString else ""
                    val dirsElements = dirsArray?.toList() ?: emptyList()
                    val dirs = dirsElements.map { it.asJsonObject }.map { obj ->
                        val nameElement = obj.get("name")
                        val pathElement = obj.get("path")
                        DirItem(
                            name = if (nameElement != null && !nameElement.isJsonNull) nameElement.asString else "",
                            path = if (pathElement != null && !pathElement.isJsonNull) pathElement.asString else ""
                        )
                    }
                    uiState = uiState.copy(
                        folderBrowserState = uiState.folderBrowserState.copy(
                            isLoading = false,
                            currentPath = currentStr,
                            parentPath = parentStr,
                            dirs = dirs
                        )
                    )
                } else {
                    uiState = uiState.copy(
                        folderBrowserState = uiState.folderBrowserState.copy(
                            isLoading = false,
                            error = "加载失败: 服务器响应异常 (Code: $code)"
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Directory load failed", e)
                uiState = uiState.copy(
                    folderBrowserState = uiState.folderBrowserState.copy(
                        isLoading = false,
                        error = e.message ?: "未知错误"
                    )
                )
            }
        }
    }

    fun createDirectory(hostUrl: String, parentPath: String, folderName: String) {
        viewModelScope.launch {
            try {
                val cleanParent = if (parentPath.endsWith("/")) parentPath.dropLast(1) else parentPath
                val newDirPath = "$cleanParent/$folderName"
                val encodedPath = java.net.URLEncoder.encode(newDirPath, "UTF-8")
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$hostUrl/mkdir?path=$encodedPath")
                        .build()
                    httpClient.newCall(request).execute().close()
                }
                loadDirectory(hostUrl, parentPath)
            } catch (e: Exception) {
                uiState = uiState.copy(
                    folderBrowserState = uiState.folderBrowserState.copy(
                        error = "创建文件夹失败: ${e.message}"
                    )
                )
            }
        }
    }

    // ─── CWD 历史记录 ─────────────────────────────────────────────────

    fun fetchCwdHistory() {
        viewModelScope.launch {
            try {
                val (result, body) = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$relayBase/cwd_history")
                        .build()
                    val res = httpClient.newCall(request).execute()
                    val b = res.body?.string()
                    val successful = res.isSuccessful
                    res.close()
                    Pair(successful, b)
                }
                if (result && body != null) {
                    val root = JsonParser.parseString(body).asJsonObject
                    val historyArray = root.getAsJsonArray("history") ?: return@launch
                    val items = historyArray.map { elem ->
                        val obj = elem.asJsonObject
                        CwdHistoryItem(
                            path = obj.get("path")?.asString ?: "",
                            app = obj.get("app")?.asString ?: "",
                            time = obj.get("time")?.asString ?: ""
                        )
                    }
                    uiState = uiState.copy(cwdHistory = items)
                }
            } catch (e: Exception) {
                Log.d(TAG, "获取目录历史失败: ${e.message}")
            }
        }
    }

    fun removeCwdHistoryItem(path: String) {
        viewModelScope.launch {
            try {
                val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
                withContext(Dispatchers.IO) {
                    val emptyBody = ByteArray(0).toRequestBody(null)
                    val request = Request.Builder()
                        .url("$relayBase/cwd_history?path=$encodedPath")
                        .delete(emptyBody)
                        .build()
                    httpClient.newCall(request).execute().close()
                }
                uiState = uiState.copy(
                    cwdHistory = uiState.cwdHistory.filter { it.path != path }
                )
            } catch (e: Exception) {
                Log.d(TAG, "删除目录历史失败: ${e.message}")
            }
        }
    }

    // ─── 公开动作 ──────────────────────────────────────────────────

    fun refreshIdes() {
        uiState = uiState.copy(isLoadingIdes = true)
        viewModelScope.launch {
            try {
                val ides = fetchIdes()
                val mergedIdes = mergeWorkflowDefaultIdes(ides)
                uiState = uiState.copy(
                    availableIdes = mergedIdes,
                    isLoadingIdes = false,
                    brainIde = uiState.brainIde.ifBlank { "Antigravity" },
                    brainSelectedPort = uiState.brainSelectedPort.takeIf { it != 0 } ?: 9333,
                    workerIde = uiState.workerIde.ifBlank { "Cursor" },
                    workerSelectedPort = uiState.workerSelectedPort.takeIf { it != 0 } ?: 9555,
                )
                // 同时拉取 CWD 历史
                fetchCwdHistory()
            } catch (e: Exception) {
                Log.e(TAG, "拉取 IDE 列表失败", e)
                uiState = uiState.copy(
                    isLoadingIdes = false,
                    toastMessage = "拉取 IDE 失败: ${e.message}"
                )
            }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            try {
                val status = fetchStatus()
                val newState = WorkflowState.from(status.state)
                val prevState = uiState.pipelineState

                // P1#7: 检测终态转变，弹 toast 通知用户
                if (newState == WorkflowState.DONE && prevState != WorkflowState.DONE) {
                    uiState = uiState.copy(toastMessage = "✅ 流水线已完成")
                } else if (newState == WorkflowState.ABORT && prevState != WorkflowState.ABORT) {
                    // 如果 Relay 带了 lastError（审查轮次超限 / 发送消息失败等），把原因一并显示
                    val msg = status.lastError?.takeIf { it.isNotBlank() }
                        ?.let { "❌ 流水线已中断：$it" }
                        ?: "❌ 流水线已中断"
                    uiState = uiState.copy(toastMessage = msg)
                }

                // 如果本地任务描述为空但服务端有（外部启动的流水线），用服务端的补全
                val syncedTask = if (uiState.initialTask.isBlank() && !status.initialTask.isNullOrBlank())
                    status.initialTask else uiState.initialTask

                uiState = uiState.copy(
                    pipelineState = newState,
                    brainPort = status.brainPort,
                    workerPort = status.workerPort,
                    elapsedMs = status.elapsedMs,
                    warned = status.warned,
                    activeCwd = status.cwd,
                    activeBrainIde = status.brainIde,
                    activeWorkerIde = status.workerIde,
                    reviewRound = status.reviewRound,
                    minReviewRounds = status.minReviewRounds,
                    lastReviewVerdict = status.lastReviewVerdict,
                    eventLog = status.eventLog,
                    lastError = status.lastError,
                    lastFinishedState = status.lastFinishedState,
                    initialTask = syncedTask,
                )
            } catch (_: Exception) {
                // 轮询静默失败，不扰民
            }
        }
    }

    fun startPipeline() {
        val s = uiState
        val brain = s.brainIde.trim()
        val worker = s.workerIde.trim()
        val task = s.initialTask.trim()
        val cwd = s.cwd.trim()

        when {
            brain.isBlank() || worker.isBlank() ->
                { uiState = s.copy(toastMessage = "请选择大脑和工人 IDE"); return }
            brain.equals(worker, ignoreCase = true) && s.brainSelectedPort == s.workerSelectedPort ->
                { uiState = s.copy(toastMessage = "大脑和工人必须是不同的 IDE 实例"); return }
            task.isBlank() && s.attachments.isEmpty() ->
                { uiState = s.copy(toastMessage = "请输入初始任务或添加附件"); return }
            cwd.isBlank() ->
                { uiState = s.copy(toastMessage = "请输入 Git 仓库路径"); return }
        }

        // 将附件信息合并到任务文本中（使用 safeName 保证与 Relay 落盘一致）
        val fullTask = buildString {
            append(task)
            if (s.attachments.isNotEmpty()) {
                if (task.isNotBlank()) append("\n\n")
                append("── 附件 (${s.attachments.size} 个) ──\n")
                s.attachments.forEachIndexed { index, att ->
                    val sizeKb = att.sizeBytes / 1024
                    val typeLabel = when (att.type) {
                        AttachmentType.IMAGE -> "🖼️ 图片"
                        AttachmentType.FILE -> "📄 文件"
                    }
                    append("${index + 1}. $typeLabel: ${att.safeName} (${sizeKb}KB, ${att.mimeType})\n")
                    append("   → 已保存至 .orchestra/attachments/${att.safeName}\n")
                }
            }
        }

        uiState = s.copy(isStarting = true)
        viewModelScope.launch {
            try {
                // 先将附件保存到服务端；任一失败即中止启动
                if (s.attachments.isNotEmpty()) {
                    // App 端 10MB 预检
                    val oversize = s.attachments.filter { it.sizeBytes > 10 * 1024 * 1024 }
                    if (oversize.isNotEmpty()) {
                        val names = oversize.joinToString { it.name }
                        uiState = uiState.copy(
                            isStarting = false,
                            toastMessage = "附件超过 10MB 上限: $names"
                        )
                        return@launch
                    }
                    if (s.attachments.size > 10) {
                        uiState = uiState.copy(
                            isStarting = false,
                            toastMessage = "附件数量不能超过 10 个（当前 ${s.attachments.size} 个）"
                        )
                        return@launch
                    }

                    val total = s.attachments.size
                    val failedNames = mutableListOf<String>()
                    for ((idx, att) in s.attachments.withIndex()) {
                        uiState = uiState.copy(uploadProgress = "${idx + 1}/$total")
                        val err = uploadSingleAttachment(cwd, att)
                        if (err != null) failedNames.add(err)
                    }
                    uiState = uiState.copy(uploadProgress = null)
                    if (failedNames.isNotEmpty()) {
                        uiState = uiState.copy(
                            isStarting = false,
                            toastMessage = "附件上传失败，已中止启动: ${failedNames.joinToString()}"
                        )
                        return@launch
                    }
                }

                val body = JSONObject().apply {
                    put("pipeline", "pair_programming")
                    put("brain",  JSONObject().put("ide", brain).put("port", s.brainSelectedPort))
                    put("worker", JSONObject().put("ide", worker).put("port", s.workerSelectedPort))
                    put("initial_task", fullTask)
                    put("cwd", cwd)
                }
                postJson("$relayBase/workflow/start", body.toString())
                uiState = uiState.copy(
                    isStarting = false,
                    toastMessage = "流水线已启动 🚀",
                )
                // 清理附件（连同缓存文件）
                clearAttachments()
                refreshStatus()
            } catch (e: Exception) {
                Log.e(TAG, "启动流水线失败", e)
                uiState = uiState.copy(
                    isStarting = false,
                    toastMessage = "启动失败: ${e.message}"
                )
            }
        }
    }

    fun abortPipeline() {
        uiState = uiState.copy(isAborting = true)
        viewModelScope.launch {
            try {
                val body = JSONObject().apply {
                    uiState.activeCwd?.let { put("cwd", it) }
                }
                postJson("$relayBase/workflow/abort", body.toString())
                uiState = uiState.copy(
                    isAborting = false,
                    toastMessage = "流水线已中断 ❌"
                )
                refreshStatus()
            } catch (e: Exception) {
                Log.e(TAG, "中断流水线失败", e)
                uiState = uiState.copy(
                    isAborting = false,
                    toastMessage = "中断失败: ${e.message}"
                )
            }
        }
    }

    // ─── 状态轮询 ─────────────────────────────────────────────────

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                // P1#10: IDLE 时 8 秒轮询，活跃时 2 秒
                val interval = if (uiState.pipelineState == WorkflowState.IDLE) 8_000L else POLL_INTERVAL_MS
                delay(interval)
                refreshStatus()
            }
        }
    }

    // ─── HTTP ────────────────────────────────────────────────────

    private suspend fun fetchIdes() = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$relayBase/targets").build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    SchedulerViewModel.extractErrorMessage(body, "HTTP ${response.code}")
                )
            }
            mergeWorkflowDefaultIdes(SchedulerViewModel.parseIdesJsonOrThrow(body))
        }
    }

    private suspend fun fetchStatus(): WorkflowStatusDto = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$relayBase/workflow/status").build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    SchedulerViewModel.extractErrorMessage(body, "HTTP ${response.code}")
                )
            }
            parseStatusJson(body)
        }
    }

    private suspend fun postJson(url: String, json: String) = withContext(Dispatchers.IO) {
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        httpClient.newCall(request).execute().use { response ->
            val respBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    SchedulerViewModel.extractErrorMessage(respBody, "HTTP ${response.code}")
                )
            }
            JsonParser.parseString(respBody).asJsonObject
        }
    }

    companion object {
        private const val TAG = "WorkflowVM"
        private const val POLL_INTERVAL_MS = 2_000L

        private val workflowDefaultIdes = listOf(
            IdeInfo("Antigravity", 9333, "可自动启动", emoji = "🚀"),
            IdeInfo("Cursor", 9555, "可自动启动", emoji = "🖱️"),
            IdeInfo("Windsurf", 9444, "可自动启动", emoji = "🏄"),
            IdeInfo("Codex", 9666, "可自动启动", emoji = "📦"),
        )

        internal fun mergeWorkflowDefaultIdes(onlineIdes: List<IdeInfo>): List<IdeInfo> {
            val byKey = linkedMapOf<Pair<String, Int>, IdeInfo>()
            workflowDefaultIdes.forEach { byKey[it.name to it.port] = it }
            onlineIdes.forEach { byKey[it.name to it.port] = it }
            return byKey.values.toList()
        }

        /** 解析 /workflow/status 响应（可独立测试） */
        internal fun parseStatusJson(json: String): WorkflowStatusDto {
            val obj = JsonParser.parseString(json).asJsonObject
            fun objOrNull(key: String) = obj.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
            val brain = objOrNull("brain")
            val worker = objOrNull("worker")
            val events = obj.get("eventLog")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { el ->
                    val event = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    WorkflowEvent(
                        type = event.get("type")?.takeIf { !it.isJsonNull }?.asString ?: "",
                        from = event.get("from")?.takeIf { !it.isJsonNull }?.asString ?: "",
                        to = event.get("to")?.takeIf { !it.isJsonNull }?.asString ?: "",
                        verb = event.get("verb")?.takeIf { !it.isJsonNull }?.asString ?: "",
                        hash = event.get("hash")?.takeIf { !it.isJsonNull }?.asString,
                        summary = event.get("summary")?.takeIf { !it.isJsonNull }?.asString ?: "",
                        time = event.get("time")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                    )
                }
                ?: emptyList()
            return WorkflowStatusDto(
                state = obj.get("state")?.asString ?: "IDLE",
                elapsedMs = obj.get("elapsed_ms")?.asLong ?: 0,
                warned = obj.get("warned")?.asBoolean ?: false,
                cwd = obj.get("cwd")?.takeIf { !it.isJsonNull }?.asString,
                initialTask = obj.get("initialTask")?.takeIf { !it.isJsonNull }?.asString,
                brainPort = brain?.get("port")?.takeIf { !it.isJsonNull }?.asInt,
                workerPort = worker?.get("port")?.takeIf { !it.isJsonNull }?.asInt,
                brainIde = brain?.get("ide")?.takeIf { !it.isJsonNull }?.asString,
                workerIde = worker?.get("ide")?.takeIf { !it.isJsonNull }?.asString,
                reviewRound = obj.get("reviewRound")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                minReviewRounds = obj.get("minReviewRounds")?.takeIf { !it.isJsonNull }?.asInt ?: 3,
                lastReviewVerdict = obj.get("lastReviewVerdict")?.takeIf { !it.isJsonNull }?.asString,
                eventLog = events,
                lastError = obj.get("lastError")?.takeIf { !it.isJsonNull }?.asString,
                lastFinishedState = obj.get("lastFinishedState")?.takeIf { !it.isJsonNull }?.asString,
            )
        }
    }

    /** 上传单个附件到 Relay。返回错误描述（null = 成功） */
    private suspend fun uploadSingleAttachment(cwd: String, att: TaskAttachment): String? {
        // 独立超时：上传用 30 秒总超时，防止 Relay 无响应时永久挂住
        val uploadClient = httpClient.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        return withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("cwd", cwd)
                    put("filename", att.name)  // Relay 端会做同样的清洗
                    // P0#1: 从 cacheDir 读取 base64，不在内存中持有
                    put("base64", File(att.cachePath).readText())
                }
                val body = payload.toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$relayBase/workflow/upload_attachment")
                    .post(body)
                    .build()
                uploadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val respBody = response.body?.string()?.take(200) ?: ""
                        Log.e(TAG, "上传附件失败 ${att.name}: HTTP ${response.code} $respBody")
                        "${att.name}(HTTP ${response.code})"
                    } else null
                }
            } catch (e: Exception) {
                Log.e(TAG, "上传附件异常 ${att.name}", e)
                val hint = when {
                    e.message?.contains("timeout", true) == true -> "超时，请检查 Relay 是否已重启"
                    e.message?.contains("refused", true) == true -> "连接被拒，Relay 未运行"
                    else -> e.message?.take(60) ?: "未知错误"
                }
                "${att.name}($hint)"
            }
        }
    }

    /** 流水线状态的网络响应 DTO */
    internal data class WorkflowStatusDto(
        val state: String,
        val elapsedMs: Long,
        val warned: Boolean,
        val cwd: String?,
        val initialTask: String? = null,
        val brainPort: Int?,
        val workerPort: Int?,
        val brainIde: String? = null,
        val workerIde: String? = null,
        val reviewRound: Int = 0,
        val minReviewRounds: Int = 3,
        val lastReviewVerdict: String? = null,
        val eventLog: List<WorkflowEvent> = emptyList(),
        val lastError: String? = null,
        val lastFinishedState: String? = null,
    )
}
