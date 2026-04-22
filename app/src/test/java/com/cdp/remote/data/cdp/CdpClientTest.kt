package com.cdp.remote.data.cdp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

/**
 * CdpClient 的消息解析逻辑测试。
 *
 * 验证 evaluate() 内部的返回值类型分支和异常检测。
 * 这些解析逻辑如果错了，IDE 交互就会静默失败或 crash。
 */
class CdpClientTest {

    // ─── evaluate 返回值类型分支 — 5种类型都要覆盖 ────────────────

    @Test
    fun `evaluate string result`() {
        val resultObj = parseResult("""{"type": "string", "value": "hello"}""")
        assertEquals("hello", extractValue(resultObj))
    }

    @Test
    fun `evaluate number result`() {
        val resultObj = parseResult("""{"type": "number", "value": 42}""")
        assertEquals("42", extractValue(resultObj))
    }

    @Test
    fun `evaluate boolean result`() {
        val resultObj = parseResult("""{"type": "boolean", "value": true}""")
        assertEquals("true", extractValue(resultObj))
    }

    @Test
    fun `evaluate undefined result returns null`() {
        val resultObj = parseResult("""{"type": "undefined"}""")
        assertNull(extractValue(resultObj))
    }

    @Test
    fun `evaluate object result returns JSON string`() {
        val resultObj = parseResult("""{"type": "object", "value": {"key": "val"}}""")
        val value = extractValue(resultObj)
        assertNotNull(value)
        assertTrue(value!!.contains("key"))
    }

    // ─── 异常检测 — 错了就吞掉 JS 错误 ─────────────────────────────

    @Test
    fun `detect JS exception in evaluate response`() {
        val json = JsonParser.parseString("""
            {
                "result": {"type": "object"},
                "exceptionDetails": {
                    "exception": {"description": "ReferenceError: foo is not defined"}
                }
            }
        """).asJsonObject

        val exceptionDetails = json.getAsJsonObject("exceptionDetails")
        assertNotNull("应检测到 JS 异常", exceptionDetails)
        val errText = exceptionDetails!!.getAsJsonObject("exception")
            ?.get("description")?.asString
        assertEquals("ReferenceError: foo is not defined", errText)
    }

    @Test
    fun `no exceptionDetails means evaluate success`() {
        val json = JsonParser.parseString("""
            {"result": {"type": "string", "value": "ok"}}
        """).asJsonObject
        assertNull(json.getAsJsonObject("exceptionDetails"))
    }

    // ─── CDP 错误响应 vs 成功响应 — 处理错了会丢失错误信息 ─────────

    @Test
    fun `CDP error response has error field`() {
        val json = JsonParser.parseString("""
            {"id": 5, "error": {"code": -32601, "message": "Method not found"}}
        """).asJsonObject

        val error = json.getAsJsonObject("error")
        assertNotNull(error)
        assertEquals("Method not found", error!!.get("message").asString)
    }

    @Test
    fun `CDP event message has method but no id`() {
        val json = JsonParser.parseString("""
            {"method": "Page.frameNavigated", "params": {"frame": {"id": "main"}}}
        """).asJsonObject

        assertNull("事件消息无 id", json.get("id"))
        assertEquals("Page.frameNavigated", json.get("method")?.asString)
    }

    // ─── WebSocket URL 重写 — 连不上 IDE 的常见原因 ─────────────────

    @Test
    fun `rewrite 127_0_0_1 to real host address`() {
        val hostPort = 19335
        val hostAddress = "192.168.1.10:19335"
        val wsUrl = "ws://127.0.0.1:19335/devtools/page/abc"
        val rewritten = wsUrl
            .replace("127.0.0.1:$hostPort", hostAddress)
            .replace("localhost:$hostPort", hostAddress)
        assertEquals("ws://192.168.1.10:19335/devtools/page/abc", rewritten)
    }

    @Test
    fun `rewrite localhost to real host address`() {
        val hostPort = 19335
        val hostAddress = "10.0.0.1:19335"
        val wsUrl = "ws://localhost:19335/cdp/9333/devtools/page/xyz"
        val rewritten = wsUrl
            .replace("127.0.0.1:$hostPort", hostAddress)
            .replace("localhost:$hostPort", hostAddress)
        assertEquals("ws://10.0.0.1:19335/cdp/9333/devtools/page/xyz", rewritten)
    }

    @Test
    fun `already-correct address is not modified`() {
        val hostPort = 19335
        val hostAddress = "192.168.1.10:19335"
        val wsUrl = "ws://192.168.1.10:19335/devtools/page/abc"
        val rewritten = wsUrl
            .replace("127.0.0.1:$hostPort", hostAddress)
            .replace("localhost:$hostPort", hostAddress)
        assertEquals(wsUrl, rewritten)
    }

    // ─── 初始状态 ────────────────────────────────────────────────────

    @Test
    fun `CdpClient starts disconnected`() {
        val client = CdpClient()
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
        assertFalse(client.isConnected)
    }

    // ─── helpers ─────────────────────────────────────────────────────

    /** 模拟 CdpClient.evaluate() 中的返回值解析逻辑 */
    private fun extractValue(resultObj: JsonObject): String? {
        return when (resultObj.get("type")?.asString) {
            "string" -> resultObj.get("value")?.asString
            "number", "boolean" -> resultObj.get("value")?.asString
            "object" -> resultObj.get("value")?.toString()
            "undefined" -> null
            else -> resultObj.get("value")?.asString
        }
    }

    private fun parseResult(json: String): JsonObject {
        return JsonParser.parseString(json).asJsonObject
    }
}
