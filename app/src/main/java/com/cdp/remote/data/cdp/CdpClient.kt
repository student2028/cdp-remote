package com.cdp.remote.data.cdp

import android.util.Base64
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CdpClient(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : ICdpClient {
    companion object {
        private const val TAG = "CdpClient"
        private const val CALL_TIMEOUT = 15000L
        private const val CONNECT_TIMEOUT = 5000L
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val messageId = AtomicInteger(0)
    private val pendingCalls = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val eventListeners = ConcurrentHashMap<String, MutableList<(JsonObject) -> Unit>>()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private var currentHost: HostInfo? = null
    private var currentPage: CdpPage? = null
    private var webSocket: WebSocket? = null

    override val isConnected: Boolean get() = _connectionState.value == ConnectionState.CONNECTED

    // ─── Discovery ──────────────────────────────────────────────────

    override suspend fun discoverPages(host: HostInfo): CdpResult<List<CdpPage>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("${host.httpUrl}/json").build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()
            if (body == null) return@withContext CdpResult.Error("空响应")
            if (!response.isSuccessful) return@withContext CdpResult.Error("HTTP ${response.code}")

            val pages = parsePages(body).map { page ->
                page.copy(
                    webSocketDebuggerUrl = page.webSocketDebuggerUrl
                        .replace("127.0.0.1:${host.port}", host.address)
                        .replace("localhost:${host.port}", host.address)
                )
            }
            CdpResult.Success(pages)
        } catch (e: Exception) {
            Log.e(TAG, "发现页面失败: ${e.message}")
            CdpResult.Error("连接失败: ${e.message}")
        }
    }

    // ─── Connection ─────────────────────────────────────────────────

    override suspend fun connect(host: HostInfo, page: CdpPage): CdpResult<Unit> = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = ConnectionState.CONNECTING
            currentHost = host
            currentPage = page
            val wsUrl = page.webSocketDebuggerUrl
            Log.d(TAG, "连接 WebSocket: $wsUrl")

            val request = Request.Builder().url(wsUrl).build()
            val deferred = CompletableDeferred<CdpResult<Unit>>()

            webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket 已连接")
                    _connectionState.value = ConnectionState.CONNECTED
                    if (!deferred.isCompleted) deferred.complete(CdpResult.Success(Unit))
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket 失败: ${t.message}", t)
                    _connectionState.value = ConnectionState.ERROR
                    if (!deferred.isCompleted) deferred.complete(CdpResult.Error("连接失败: ${t.message}"))
                    pendingCalls.forEach { (_, d) -> d.completeExceptionally(t) }
                    pendingCalls.clear()
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket 关闭中: $code $reason")
                    webSocket.close(1000, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket 已关闭: $code $reason")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    pendingCalls.forEach { (_, d) -> d.completeExceptionally(RuntimeException("WebSocket 已关闭")) }
                    pendingCalls.clear()
                }
            })

            withTimeout(CONNECT_TIMEOUT) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            _connectionState.value = ConnectionState.ERROR
            CdpResult.Error("连接超时")
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.ERROR
            CdpResult.Error("连接异常: ${e.message}")
        }
    }

    override suspend fun connectDirect(wsUrl: String): CdpResult<Unit> = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = ConnectionState.CONNECTING
            Log.d(TAG, "直连 WebSocket: $wsUrl")

            val request = Request.Builder().url(wsUrl).build()
            val deferred = CompletableDeferred<CdpResult<Unit>>()

            webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket 已连接 (直连)")
                    _connectionState.value = ConnectionState.CONNECTED
                    if (!deferred.isCompleted) deferred.complete(CdpResult.Success(Unit))
                }
                override fun onMessage(webSocket: WebSocket, text: String) { handleMessage(text) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket 失败 (直连): ${t.message}", t)
                    _connectionState.value = ConnectionState.ERROR
                    if (!deferred.isCompleted) deferred.complete(CdpResult.Error("连接失败: ${t.message}"))
                    pendingCalls.forEach { (_, d) -> d.completeExceptionally(t) }
                    pendingCalls.clear()
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket 已关闭 (直连): $code $reason")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    pendingCalls.forEach { (_, d) -> d.completeExceptionally(RuntimeException("WebSocket 已关闭")) }
                    pendingCalls.clear()
                }
            })

            withTimeout(CONNECT_TIMEOUT) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            _connectionState.value = ConnectionState.ERROR; CdpResult.Error("连接超时")
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.ERROR; CdpResult.Error("连接异常: ${e.message}")
        }
    }

    override suspend fun connectToWorkbench(host: HostInfo): CdpResult<CdpPage> {
        val pagesResult = discoverPages(host)
        if (pagesResult is CdpResult.Error) return CdpResult.Error(pagesResult.message)

        val pages = pagesResult.getOrThrow()
        val workbench = pages.firstOrNull { it.isWorkbench }
            ?: return CdpResult.Error("找不到 IDE workbench 页面（共 ${pages.size} 个页面）")

        val connectResult = connect(host, workbench)
        return if (connectResult is CdpResult.Error) CdpResult.Error(connectResult.message)
        else CdpResult.Success(workbench)
    }

    override fun disconnect() {
        webSocket?.close(1000, "客户端主动断开")
        webSocket = null
        pendingCalls.clear()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    // ─── CDP Call ────────────────────────────────────────────────────

    override suspend fun call(method: String, params: JsonObject): CdpResult<JsonObject> {
        val ws = webSocket ?: return CdpResult.Error("未连接")
        val id = messageId.incrementAndGet()
        val deferred = CompletableDeferred<JsonObject>()
        pendingCalls[id] = deferred

        val msg = JsonObject().apply {
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }

        val sent = ws.send(msg.toString())
        if (!sent) {
            pendingCalls.remove(id)
            return CdpResult.Error("发送失败")
        }

        return try {
            val result = withTimeout(CALL_TIMEOUT) { deferred.await() }
            CdpResult.Success(result)
        } catch (e: TimeoutCancellationException) {
            pendingCalls.remove(id)
            CdpResult.Error("CDP 超时: $method")
        } catch (e: Exception) {
            pendingCalls.remove(id)
            CdpResult.Error("CDP 错误: ${e.message}")
        }
    }

    // ─── High-level CDP Methods ─────────────────────────────────────

    override suspend fun evaluate(expression: String, awaitPromise: Boolean): CdpResult<String?> {
        val params = JsonObject().apply {
            addProperty("expression", expression)
            addProperty("returnByValue", true)
            if (awaitPromise) {
                addProperty("awaitPromise", true)
            }
        }
        val result = call("Runtime.evaluate", params)
        if (result is CdpResult.Error) return CdpResult.Error(result.message)

        val data = result.getOrThrow()
        val exceptionDetails = data.getAsJsonObject("exceptionDetails")
        if (exceptionDetails != null) {
            val errText = exceptionDetails.getAsJsonObject("exception")
                ?.get("description")?.asString ?: "JS 执行异常"
            return CdpResult.Error(errText)
        }

        val resultObj = data.getAsJsonObject("result")
        val value = when (resultObj?.get("type")?.asString) {
            "string" -> resultObj.get("value")?.asString
            "number", "boolean" -> resultObj.get("value")?.asString
            "object" -> resultObj.get("value")?.toString()
            "undefined" -> null
            else -> resultObj?.get("value")?.asString
        }
        return CdpResult.Success(value)
    }

    override suspend fun dispatchKeyEvent(type: String, key: String): CdpResult<JsonObject> {
        val params = JsonObject().apply {
            addProperty("type", type)
            addProperty("key", key)
        }
        return call("Input.dispatchKeyEvent", params)
    }

    override suspend fun insertText(text: String): CdpResult<JsonObject> {
        val params = JsonObject().apply {
            addProperty("text", text)
        }
        return call("Input.insertText", params)
    }

    override suspend fun dispatchMouseEvent(
        type: String, x: Double, y: Double,
        button: String, clickCount: Int
    ): CdpResult<JsonObject> {
        val params = JsonObject().apply {
            addProperty("type", type)
            addProperty("x", x)
            addProperty("y", y)
            addProperty("button", button)
            addProperty("clickCount", clickCount)
        }
        return call("Input.dispatchMouseEvent", params)
    }

    override suspend fun dispatchScrollEvent(deltaY: Double, x: Double, y: Double): CdpResult<JsonObject> {
        val params = JsonObject().apply {
            addProperty("type", "mouseWheel")
            addProperty("x", x)
            addProperty("y", y)
            addProperty("deltaX", 0)
            addProperty("deltaY", deltaY)
        }
        return call("Input.dispatchMouseEvent", params)
    }

    override suspend fun captureScreenshot(quality: Int, clip: JsonObject?): CdpResult<ByteArray> {
        val params = JsonObject().apply {
            addProperty("format", "jpeg")
            addProperty("quality", quality)
            if (clip != null) {
                add("clip", clip)
            }
        }
        val result = call("Page.captureScreenshot", params)
        if (result is CdpResult.Error) return CdpResult.Error(result.message)

        val data = result.getOrThrow()
        val base64 = data.get("data")?.asString
            ?: return CdpResult.Error("截图数据为空")
        return CdpResult.Success(Base64.decode(base64, Base64.DEFAULT))
    }

    // ─── Events ─────────────────────────────────────────────────────

    override fun addEventListener(event: String, listener: (JsonObject) -> Unit) {
        eventListeners.getOrPut(event) { mutableListOf() }.add(listener)
    }

    override fun removeEventListeners(event: String) {
        eventListeners.remove(event)
    }

    // ─── Internal ───────────────────────────────────────────────────

    private fun handleMessage(text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject
            val id = json.get("id")?.asInt

            if (id != null) {
                val deferred = pendingCalls.remove(id)
                val error = json.getAsJsonObject("error")
                if (error != null) {
                    val msg = error.get("message")?.asString ?: "CDP 错误"
                    deferred?.completeExceptionally(RuntimeException(msg))
                } else {
                    deferred?.complete(json.getAsJsonObject("result") ?: JsonObject())
                }
                return
            }

            val method = json.get("method")?.asString ?: return
            val params = json.getAsJsonObject("params") ?: JsonObject()
            eventListeners[method]?.forEach { listener ->
                try { listener(params) } catch (e: Exception) {
                    Log.e(TAG, "事件处理异常: $method", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "消息解析失败: $text", e)
        }
    }
}
