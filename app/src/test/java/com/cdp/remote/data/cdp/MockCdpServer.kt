package com.cdp.remote.data.cdp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 本地 Mock CDP Server — 模拟 Chrome DevTools Protocol 的 WebSocket 端。
 *
 * 用法：
 * 1. 创建 MockCdpServer 并设置响应策略
 * 2. 启动 server.start()
 * 3. CdpClient.connectDirect(server.wsUrl) 连接
 * 4. 执行 Commands 方法
 * 5. 验证 server.receivedExpressions 中捕获的 JS 表达式
 * 6. server.shutdown()
 */
class MockCdpServer {

    private val server = okhttp3.mockwebserver.MockWebServer()
    private val _receivedMessages = ConcurrentLinkedQueue<JsonObject>()
    private var _responseProvider: (JsonObject) -> JsonObject? = { defaultResponse(it) }
    private var _webSocket: WebSocket? = null
    private val connectedLatch = CountDownLatch(1)

    /** 所有收到的 CDP 请求 */
    val receivedMessages: List<JsonObject> get() = _receivedMessages.toList()

    /** 所有收到的 Runtime.evaluate 表达式 */
    val receivedExpressions: List<String>
        get() = receivedMessages
            .filter { it.get("method")?.asString == "Runtime.evaluate" }
            .mapNotNull { it.getAsJsonObject("params")?.get("expression")?.asString }

    /** WebSocket URL */
    val wsUrl: String get() = "ws://localhost:${server.port}/devtools/page/mock"

    /** HTTP URL (for /json endpoint) */
    val httpUrl: String get() = "http://localhost:${server.port}"

    /**
     * 设置自定义响应策略。
     * 参数是收到的 CDP 请求 JsonObject，返回响应的 result JsonObject。
     * 返回 null 则使用默认响应。
     */
    fun onRequest(provider: (JsonObject) -> JsonObject?) {
        _responseProvider = provider
    }

    fun start() {
        server.enqueue(okhttp3.mockwebserver.MockResponse()
            .withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _webSocket = webSocket
                    connectedLatch.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JsonParser.parseString(text).asJsonObject
                        _receivedMessages.add(json)

                        val id = json.get("id")?.asInt ?: return

                        // 尝试自定义响应
                        val customResult = _responseProvider(json)
                        val result = customResult ?: defaultResponse(json)

                        val response = JsonObject().apply {
                            addProperty("id", id)
                            add("result", result)
                        }
                        webSocket.send(response.toString())
                    } catch (e: Exception) {
                        // 忽略解析错误
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            }))
    }

    fun shutdown() {
        _webSocket?.close(1000, "test done")
        server.shutdown()
    }

    fun waitForConnection(timeoutMs: Long = 5000): Boolean {
        return connectedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** 主动发送 CDP 事件给客户端 */
    fun sendEvent(method: String, params: JsonObject = JsonObject()) {
        val event = JsonObject().apply {
            addProperty("method", method)
            add("params", params)
        }
        _webSocket?.send(event.toString())
    }

    private fun defaultResponse(request: JsonObject): JsonObject {
        val method = request.get("method")?.asString ?: ""
        return when (method) {
            "Runtime.evaluate" -> {
                // 默认返回 string "ok"
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", "ok")
                    })
                }
            }
            "Input.dispatchKeyEvent",
            "Input.dispatchMouseEvent",
            "Input.insertText" -> JsonObject() // 空 result
            "Page.captureScreenshot" -> {
                JsonObject().apply {
                    // 1x1 白色 JPEG 的 base64
                    addProperty("data", "/9j/4AAQSkZJRg==")
                }
            }
            else -> JsonObject()
        }
    }
}
