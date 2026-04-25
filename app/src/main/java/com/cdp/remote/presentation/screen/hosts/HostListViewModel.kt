package com.cdp.remote.presentation.screen.hosts

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cdp.remote.data.cdp.*
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class HostListViewModel : ViewModel() {

    companion object {
        private const val TAG = "HostListVM"
        private const val TV_SCREENSHOT_TIMEOUT_MS = 3000L
        private const val TV_RECONNECT_THRESHOLD = 3
    }

    var uiState by mutableStateOf(HostUiState())
        private set

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // ─── Host Management ────────────────────────────────────────────

    fun addHost(ip: String, port: Int, name: String) {
        val host = HostInfo(ip, port, name)
        val hosts = uiState.hosts.toMutableList()
        if (hosts.none { it.address == ip && it.port == port }) {
            hosts.add(host)
            uiState = uiState.copy(hosts = hosts, showAddDialog = false)
            scanHost(host)
        }
    }

    fun removeHost(host: HostInfo) {
        uiState = uiState.copy(hosts = uiState.hosts.filter { it != host })
    }

    fun showAddDialog() { uiState = uiState.copy(showAddDialog = true) }
    fun hideAddDialog() { uiState = uiState.copy(showAddDialog = false) }
    fun showLaunchDialog() {
        uiState = uiState.copy(showLaunchDialog = true)
        fetchCwdHistory()
    }
    fun hideLaunchDialog() { uiState = uiState.copy(showLaunchDialog = false, launchStep = LaunchStep.IDLE, launchStatus = null) }

    // ─── CWD History ────────────────────────────────────────────────

    fun fetchCwdHistory() {
        viewModelScope.launch {
            try {
                val host = uiState.hosts.firstOrNull() ?: return@launch
                val (result, body) = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("${host.httpUrl}/cwd_history")
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
                val host = uiState.hosts.firstOrNull() ?: return@launch
                val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
                withContext(Dispatchers.IO) {
                    val emptyBody = ByteArray(0).toRequestBody(null)
                    val request = Request.Builder()
                        .url("${host.httpUrl}/cwd_history?path=$encodedPath")
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

    // ─── Scanning ───────────────────────────────────────────────────

    fun scanAllHosts() {
        uiState = uiState.copy(isScanning = true, scanError = null)
        viewModelScope.launch {
            val allApps = mutableMapOf<String, List<CdpPage>>()
            for (host in uiState.hosts) {
                try {
                    // Use /targets endpoint to discover ALL CDP instances
                    val pages = discoverViaTargets(host)
                    if (pages.isNotEmpty()) {
                        allApps[host.address] = pages
                    } else {
                        // Fallback to /json if /targets returns nothing
                        val cdpClient = CdpClient()
                        val result = cdpClient.discoverPages(host)
                        if (result is CdpResult.Success) {
                            allApps[host.address] = result.data
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "扫描 ${host.address} 失败", e)
                }
            }
            uiState = uiState.copy(
                isScanning = false,
                discoveredApps = allApps,
                scanError = if (allApps.isEmpty()) "未发现 IDE 应用，点击「远程启动」试试" else null
            )
        }
    }

    fun scanHost(host: HostInfo) {
        uiState = uiState.copy(isScanning = true)
        viewModelScope.launch {
            try {
                val pages = discoverViaTargets(host)
                val apps = uiState.discoveredApps.toMutableMap()
                if (pages.isNotEmpty()) {
                    apps[host.address] = pages
                    uiState = uiState.copy(isScanning = false, discoveredApps = apps)
                } else {
                    // Fallback
                    val cdpClient = CdpClient()
                    val result = cdpClient.discoverPages(host)
                    when (result) {
                        is CdpResult.Success -> {
                            apps[host.address] = result.data
                            uiState = uiState.copy(isScanning = false, discoveredApps = apps)
                        }
                        is CdpResult.Error -> {
                            val isProxyError = result.message.contains("502") || result.message.contains("503") || result.message.contains("504")
                            uiState = uiState.copy(
                                isScanning = false, 
                                scanError = if (isProxyError) null else "扫描失败: ${result.message}"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                uiState = uiState.copy(isScanning = false, scanError = "扫描异常: ${e.message}")
            }
        }
    }

    suspend fun discoverViaTargets(host: HostInfo): List<CdpPage> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${host.httpUrl}/targets")
                    .build()
                val response = httpClient.newCall(request).execute()
                val body = response.use { it.body?.string() } ?: return@withContext emptyList()

                // /targets returns: { "targets": [{ "cdpPort": 9333, "appName": "Antigravity", "pages": [...] }, ...] }
                val root = JsonParser.parseString(body).asJsonObject
                val targetsArray = root.getAsJsonArray("targets") ?: return@withContext emptyList()

                val allPages = mutableListOf<CdpPage>()
                for (targetElem in targetsArray) {
                    val target = targetElem.asJsonObject
                    val pagesArray = target.getAsJsonArray("pages") ?: continue
                    // target.appName 是中继按端口映射出的**权威** IDE 标识；走唯一通道
                    // ElectronAppType.fromAppName 转成枚举一次性钉死，下游不再做任何推断。
                    // 不这样做的话，用户在 Windsurf 里打开 "CursorPresetModelsTest.kt" 就会
                    // 被启发式误判为 Cursor（2026-04 的 9444 事故）。
                    val pageAppType = ElectronAppType.fromAppName(target.get("appName")?.asString)
                    for (pageElem in pagesArray) {
                        val obj = pageElem.asJsonObject
                        allPages.add(CdpPage(
                            id = obj.get("id")?.asString ?: "",
                            type = obj.get("type")?.asString ?: "",
                            title = obj.get("title")?.asString ?: "",
                            url = obj.get("url")?.asString ?: "",
                            webSocketDebuggerUrl = obj.get("webSocketDebuggerUrl")?.asString ?: "",
                            devtoolsFrontendUrl = obj.get("devtoolsFrontendUrl")?.asString ?: "",
                            appType = pageAppType
                        ))
                    }
                }
                Log.d(TAG, "/targets 发现 ${allPages.size} 个页面 (${targetsArray.size()} 个 CDP 实例)")
                allPages
            } catch (e: Exception) {
                Log.d(TAG, "/targets 不可用: ${e.message}")
                emptyList()
            }
        }
    }

    // ─── Remote Launch ──────────────────────────────────────────────

    fun remoteLaunch() {
        uiState = uiState.copy(showLaunchDialog = true)
    }

    fun remoteLaunchOnPort(cdpPort: Int, appName: String, cwd: String) {
        viewModelScope.launch {
            uiState = uiState.copy(launchStep = LaunchStep.CONNECTING, launchStatus = "正在连接 Relay...")
            try {
                val host = uiState.hosts.firstOrNull()
                if (host == null) {
                    uiState = uiState.copy(launchStep = LaunchStep.FAILED, launchStatus = "未找到主机")
                    return@launch
                }
                uiState = uiState.copy(launchStep = LaunchStep.LAUNCHING, launchStatus = "正在启动 $appName :$cdpPort...")
                val isSuccess = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("${host.httpUrl}/launch?port=$cdpPort&app=$appName&cwd=$cwd")
                        .build()
                    httpClient.newCall(request).execute().use { it.isSuccessful }
                }
                if (isSuccess) {
                    uiState = uiState.copy(launchStep = LaunchStep.SUCCESS, launchStatus = "✅ 启动成功")
                    delay(4000)
                    scanAllHosts()
                } else {
                    uiState = uiState.copy(launchStep = LaunchStep.FAILED, launchStatus = "无法连接 Relay")
                }
            } catch (e: Exception) {
                uiState = uiState.copy(launchStep = LaunchStep.FAILED, launchStatus = "无法连接到 Relay 服务")
            }
        }
    }

    // ─── Folder Browser ─────────────────────────────────────────────

    fun openFolderBrowser(hostUrl: String, initialPath: String = "") {
        uiState = uiState.copy(
            folderBrowserState = uiState.folderBrowserState.copy(
                isOpen = true, hostUrl = hostUrl, currentPath = initialPath
            )
        )
        loadDirectory(hostUrl, initialPath)
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
                    val request = Request.Builder()
                        .url("$hostUrl/dirs?path=$path")
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
                        error = e.message ?: "未知错误: ${e.javaClass.simpleName}"
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

    // ─── Page Management ────────────────────────────────────────────

    fun closePage(hostIp: String, hostPort: Int, page: CdpPage) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Route through the correct CDP port: /cdp/{port}/json/close/{id}
                    val cdpPort = page.cdpPort
                    val closeUrl = if (cdpPort != null) {
                        "http://$hostIp:$hostPort/cdp/$cdpPort/json/close/${page.id}"
                    } else {
                        "http://$hostIp:$hostPort/json/close/${page.id}"
                    }
                    Log.d(TAG, "关闭页面: $closeUrl")
                    val request = Request.Builder().url(closeUrl).build()
                    httpClient.newCall(request).execute().close()
                }
                // Refresh after close
                val host = uiState.hosts.firstOrNull { it.ip == hostIp && it.port == hostPort }
                    ?: HostInfo(hostIp, hostPort)
                scanHost(host)
            } catch (e: Exception) {
                Log.e(TAG, "关闭窗口失败: ${e.message}")
                uiState = uiState.copy(scanError = "关闭窗口失败: ${e.message}")
            }
        }
    }

    // ─── TV Mode ─────────────────────────────────────────────────────

    private val tvClients = mutableMapOf<String, CdpClient>()
    /** 每个 wsUrl 的连续失败计数，超过阈值触发重连 */
    private val tvFailCounts = mutableMapOf<String, Int>()
    private var tvJob: kotlinx.coroutines.Job? = null

    fun toggleTvMode() {
        val newMode = !uiState.tvMode
        uiState = uiState.copy(tvMode = newMode)
        if (newMode) startTvMode() else stopTvMode()
    }

    private fun startTvMode() {
        tvJob?.cancel()
        tvJob = viewModelScope.launch {
            // 收集所有 workbench 页面的 wsUrl
            val pages = uiState.discoveredApps.values.flatten().filter { it.isWorkbench }
            if (pages.isEmpty()) return@launch

            // 为每个 IDE 建连
            connectTvClients(pages)

            // 周期截屏（并行 + 超时）
            while (isActive && uiState.tvMode) {
                val entries = tvClients.entries.toList()
                if (entries.isEmpty()) {
                    // 所有连接都断了，尝试重连
                    connectTvClients(pages)
                    if (tvClients.isEmpty()) {
                        delay(3000)
                        continue
                    }
                }

                // 并行截屏：每个 IDE 独立超时，互不阻塞
                val results = entries.map { (ws, client) ->
                    async(Dispatchers.IO) {
                        try {
                            val frame = withTimeoutOrNull(TV_SCREENSHOT_TIMEOUT_MS) {
                                client.captureScreenshot(uiState.tvQuality)
                            }
                            if (frame is CdpResult.Success<ByteArray>) {
                                tvFailCounts[ws] = 0
                                Pair(ws, frame.data)
                            } else {
                                val count = (tvFailCounts[ws] ?: 0) + 1
                                tvFailCounts[ws] = count
                                if (count >= TV_RECONNECT_THRESHOLD) {
                                    Log.w(TAG, "TV 连续 $count 次失败，重连: $ws")
                                    reconnectTvClient(ws, pages)
                                }
                                null
                            }
                        } catch (e: Exception) {
                            val count = (tvFailCounts[ws] ?: 0) + 1
                            tvFailCounts[ws] = count
                            Log.e(TAG, "TV 截图异常(${count}次): ${e.message}")
                            if (count >= TV_RECONNECT_THRESHOLD) {
                                reconnectTvClient(ws, pages)
                            }
                            null
                        }
                    }
                }.awaitAll()

                // 仅更新成功截到的帧
                val newFrames = uiState.tvFrames.toMutableMap()
                for (pair in results.filterNotNull()) {
                    newFrames[pair.first] = pair.second
                }
                uiState = uiState.copy(tvFrames = newFrames)
                delay(uiState.tvIntervalMs)
            }
        }
    }

    /** 为所有未连接的 workbench 页面建立 WebSocket 连接 */
    private suspend fun connectTvClients(pages: List<CdpPage>) {
        for (page in pages) {
            val ws = page.webSocketDebuggerUrl
            if (ws.isBlank() || tvClients.containsKey(ws)) continue
            val client = CdpClient()
            val result = withTimeoutOrNull(5000) { client.connectDirect(ws) }
            if (result is CdpResult.Success) {
                tvClients[ws] = client
                tvFailCounts[ws] = 0
                Log.d(TAG, "TV 已连接: ${page.appType.displayName}")
            } else {
                Log.w(TAG, "TV 连接失败: ${page.appType.displayName}")
            }
        }
    }

    /** 断开旧连接并尝试重连单个 IDE */
    private suspend fun reconnectTvClient(ws: String, pages: List<CdpPage>) {
        try {
            tvClients.remove(ws)?.disconnect()
        } catch (_: Exception) {}
        tvFailCounts[ws] = 0
        val page = pages.find { it.webSocketDebuggerUrl == ws } ?: return
        val client = CdpClient()
        val result = withTimeoutOrNull(5000) { client.connectDirect(ws) }
        if (result is CdpResult.Success) {
            tvClients[ws] = client
            Log.d(TAG, "TV 重连成功: ${page.appType.displayName}")
        } else {
            Log.w(TAG, "TV 重连失败: ${page.appType.displayName}")
        }
    }

    private fun stopTvMode() {
        tvJob?.cancel()
        tvJob = null
        tvClients.values.forEach { try { it.disconnect() } catch (_: Exception) {} }
        tvClients.clear()
        tvFailCounts.clear()
        uiState = uiState.copy(tvFrames = emptyMap())
    }

    // ─── App Reorder ─────────────────────────────────────────────────

    /**
     * 保存用户手动拖拽排序后的 IDE 应用顺序。
     * @param hostAddress 主机地址（如 "100.106.253.39:19336"）
     * @param orderedWsUrls 排序后的 webSocketDebuggerUrl 列表
     */
    fun reorderApps(hostAddress: String, orderedWsUrls: List<String>) {
        val newOrder = uiState.appOrder.toMutableMap()
        newOrder[hostAddress] = orderedWsUrls
        uiState = uiState.copy(appOrder = newOrder)
        // 同步写入全局单例，ChatScreen 切换按钮也能读到
        com.cdp.remote.data.AppOrderStore.setOrder(hostAddress, orderedWsUrls)
    }

    override fun onCleared() {
        super.onCleared()
        stopTvMode()
    }

    fun killPortProcess(hostIp: String, hostPort: Int, cdpPort: Int) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val killUrl = "http://$hostIp:$hostPort/kill?port=$cdpPort"
                    Log.d(TAG, "终止进程: $killUrl")
                    val request = Request.Builder().url(killUrl).build()
                    httpClient.newCall(request).execute().close()
                }
                // Refresh after kill
                delay(1000)
                val host = uiState.hosts.firstOrNull { it.ip == hostIp && it.port == hostPort }
                    ?: HostInfo(hostIp, hostPort)
                scanHost(host)
            } catch (e: Exception) {
                Log.e(TAG, "终止进程失败: ${e.message}")
                uiState = uiState.copy(scanError = "终止进程失败: ${e.message}")
            }
        }
    }
}
