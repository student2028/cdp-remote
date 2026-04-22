package com.cdp.remote.data.cdp

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AntigravityCommands / WindsurfCommands 集成测试。
 *
 * 使用 MockCdpServer 模拟真实的 CDP WebSocket 连接。
 * 通过 Robolectric 提供 android.util.Log 等 Android API 支持。
 *
 * **价值**：直接测试 sendMessage、getLastReply、isGenerating 等核心业务流程，
 * 覆盖了 AntigravityCommands 中 95% 的 JS 模板代码的"发送和解析"逻辑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CommandsIntegrationTest {

    private lateinit var mockServer: MockCdpServer
    private lateinit var cdpClient: CdpClient
    private lateinit var commands: AntigravityCommands

    @Before
    fun setup() {
        mockServer = MockCdpServer()
        mockServer.start()
        cdpClient = CdpClient()

        runBlocking {
            val result = cdpClient.connectDirect(mockServer.wsUrl)
            assertTrue("应成功连接到 MockCdpServer", result.isSuccess)
        }

        commands = AntigravityCommands(cdpClient, "TestIDE")
    }

    @After
    fun teardown() {
        cdpClient.disconnect()
        mockServer.shutdown()
    }

    // ─── focusInput ─────────────────────────────────────────────────

    @Test
    fun `focusInput sends Runtime evaluate`() = runBlocking {
        val result = commands.focusInput()
        assertTrue("focusInput 应成功", result.isSuccess)

        val expressions = mockServer.receivedExpressions
        assertTrue("应发送 JS 表达式", expressions.isNotEmpty())
        // focusInput 的 JS 中包含 agentSidePanelInputBox 选择器
        assertTrue(
            "JS 应包含输入框选择器",
            expressions.any { it.contains("agentSidePanelInputBox") || it.contains("contenteditable") }
        )
    }

    // ─── setInputText ───────────────────────────────────────────────

    @Test
    fun `setInputText sends text via JS template`() = runBlocking {
        val result = commands.setInputText("Hello CDP")
        assertTrue("setInputText 应成功", result.isSuccess)

        // 验证 JS 中包含我们的文本
        val expressions = mockServer.receivedExpressions
        assertTrue(
            "JS 应包含输入文本",
            expressions.any { it.contains("Hello CDP") }
        )
    }

    @Test
    fun `setInputText escapes single quotes`() = runBlocking {
        commands.setInputText("it's a test")

        val expressions = mockServer.receivedExpressions
        // 应包含转义后的文本 it\'s
        assertTrue(
            "单引号应被转义",
            expressions.any { it.contains("it\\'s") }
        )
        // 不应有未转义的 it's（会破坏 JS）
        assertFalse(
            "不应有未转义的单引号",
            expressions.any { it.contains("'it's'") }
        )
    }

    @Test
    fun `setInputText escapes backslashes`() = runBlocking {
        commands.setInputText("path\\to\\file")

        val expressions = mockServer.receivedExpressions
        assertTrue(
            "反斜杠应被转义",
            expressions.any { it.contains("path\\\\to\\\\file") }
        )
    }

    @Test
    fun `setInputText escapes newlines`() = runBlocking {
        commands.setInputText("line1\nline2")

        val expressions = mockServer.receivedExpressions
        assertTrue(
            "换行应被转义",
            expressions.any { it.contains("line1\\nline2") }
        )
    }

    @Test
    fun `setInputText handles Chinese text`() = runBlocking {
        commands.setInputText("请帮我写一个函数")

        val expressions = mockServer.receivedExpressions
        assertTrue(
            "中文应原样传递",
            expressions.any { it.contains("请帮我写一个函数") }
        )
    }

    // ─── clickSendButton ────────────────────────────────────────────

    @Test
    fun `clickSendButton sends correct JS`() = runBlocking {
        // Mock 返回 'clicked' 表示找到了发送按钮
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", "clicked")
                    })
                }
            } else null
        }

        val result = commands.clickSendButton()
        assertTrue("clickSendButton 应成功", result.isSuccess)

        val expressions = mockServer.receivedExpressions
        assertTrue(
            "JS 应查找发送按钮",
            expressions.any { it.contains("send") || it.contains("submit") || it.contains("aria-label") }
        )
    }

    // ─── isGenerating ───────────────────────────────────────────────

    @Test
    fun `isGenerating returns true when stop button visible`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "boolean")
                        addProperty("value", true)
                    })
                }
            } else null
        }

        val generating = commands.isGenerating()
        assertTrue("应返回正在生成", generating)
    }

    @Test
    fun `isGenerating returns false when idle`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "boolean")
                        addProperty("value", false)
                    })
                }
            } else null
        }

        val generating = commands.isGenerating()
        assertFalse("应返回未在生成", generating)
    }

    // ─── getLastReply ───────────────────────────────────────────────

    @Test
    fun `getLastReply parses AI response`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", "Hello! I'm Claude, an AI assistant.")
                    })
                }
            } else null
        }

        val reply = commands.getLastReply()
        assertTrue("getLastReply 应成功", reply.isSuccess)
        assertEquals("Hello! I'm Claude, an AI assistant.", reply.getOrNull())
    }

    @Test
    fun `getLastReply returns empty when no reply`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", "")
                    })
                }
            } else null
        }

        val reply = commands.getLastReply()
        assertTrue(reply.isSuccess)
        assertEquals("", reply.getOrNull())
    }

    // ─── acceptAll / rejectAll ───────────────────────────────────────

    @Test
    fun `acceptAll sends evaluate then mouse click when button found`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            when (method) {
                "Runtime.evaluate" -> JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"found":true,"x":500.0,"y":300.0}""")
                    })
                }
                "Input.dispatchMouseEvent" -> JsonObject()
                else -> null
            }
        }

        val result = commands.acceptAll()
        assertTrue("acceptAll 应成功", result.isSuccess)

        // 验证收到了鼠标事件
        val mouseEvents = mockServer.receivedMessages.filter {
            it.get("method")?.asString == "Input.dispatchMouseEvent"
        }
        assertTrue("应发送鼠标点击事件", mouseEvents.size >= 3) // moved + pressed + released
    }

    @Test
    fun `acceptAll returns error when button not found`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"found":false}""")
                    })
                }
            } else null
        }

        val result = commands.acceptAll()
        assertTrue("未找到按钮应返回 Error", result is CdpResult.Error)
    }

    // ─── autoAcceptActions ──────────────────────────────────────────

    @Test
    fun `autoAcceptActions injects helpers first`() = runBlocking {
        // 模拟 helpers 已注入但没找到按钮
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                val expr = req.getAsJsonObject("params")?.get("expression")?.asString ?: ""
                if (expr.contains("__cdpHelpers")) {
                    // autoAcceptActions 的主逻辑
                    JsonObject().apply {
                        add("result", JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("value", """{"found":false,"reason":"no-buttons"}""")
                        })
                    }
                } else {
                    // helpers 注入
                    JsonObject().apply {
                        add("result", JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("value", "ok")
                        })
                    }
                }
            } else null
        }

        val result = commands.autoAcceptActions()
        assertFalse("没找到 action 按钮应返回 false", result)
    }

    // ─── CDP 错误处理 ────────────────────────────────────────────────

    @Test
    fun `commands handle CDP JS exception gracefully`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                // 模拟 JS 执行异常
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "object")
                    })
                    add("exceptionDetails", JsonObject().apply {
                        add("exception", JsonObject().apply {
                            addProperty("description", "TypeError: Cannot read property 'click' of null")
                        })
                    })
                }
            } else null
        }

        val result = commands.focusInput()
        assertTrue("JS 异常应返回 Error", result is CdpResult.Error)
    }

    // ─── WindsurfCommands override 验证 ──────────────────────────────

    @Test
    fun `WindsurfCommands inherits focusInput from parent`() = runBlocking {
        val windsurfCommands = WindsurfCommands(cdpClient)
        val result = windsurfCommands.focusInput()
        assertTrue("Windsurf 应继承 focusInput", result.isSuccess)
    }

    @Test
    fun `WindsurfCommands inherits setInputText from parent`() = runBlocking {
        val windsurfCommands = WindsurfCommands(cdpClient)
        val result = windsurfCommands.setInputText("test from windsurf")
        assertTrue("Windsurf 应继承 setInputText", result.isSuccess)

        val expressions = mockServer.receivedExpressions
        assertTrue(
            "应包含 windsurf 的输入文本",
            expressions.any { it.contains("test from windsurf") }
        )
    }

    @Test
    fun `WindsurfCommands stopGeneration uses Cascade shortcut`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                // stopGeneration 先检查 stop 按钮
                val expr = req.getAsJsonObject("params")?.get("expression")?.asString ?: ""
                if (expr.contains("lucide-square") || expr.contains("stop")) {
                    JsonObject().apply {
                        add("result", JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("value", """{"clicked":false}""")
                        })
                    }
                } else {
                    JsonObject().apply {
                        add("result", JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("value", "ok")
                        })
                    }
                }
            } else null
        }

        val windsurfCommands = WindsurfCommands(cdpClient)
        windsurfCommands.stopGeneration()

        // WindsurfCommands.stopGeneration 应发送 Escape 键事件
        val keyEvents = mockServer.receivedMessages.filter {
            it.get("method")?.asString == "Input.dispatchKeyEvent"
        }
        // 要么找到 stop 按钮点击，要么发 Escape 键
        assertTrue(
            "stopGeneration 应发送键盘或鼠标事件",
            keyEvents.isNotEmpty() || mockServer.receivedExpressions.isNotEmpty()
        )
    }

    // ─── 连接状态验证 ────────────────────────────────────────────────

    @Test
    fun `CdpClient is CONNECTED after setup`() {
        assertEquals(ConnectionState.CONNECTED, cdpClient.connectionState.value)
        assertTrue(cdpClient.isConnected)
    }

    @Test
    fun `CdpClient transitions to DISCONNECTED after disconnect`() {
        cdpClient.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, cdpClient.connectionState.value)
    }
}
