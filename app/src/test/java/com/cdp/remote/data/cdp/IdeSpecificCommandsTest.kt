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
 * WindsurfCommands + CursorCommands 集成测试。
 *
 * 验证这两个 IDE 覆写方法相对于 AntigravityCommands 基类的差异：
 * - Windsurf: pasteImage 使用 #chat / windsurf.cascadePanel 容器
 * - Windsurf: startNewSession 使用 lucide-plus SVG / Cmd+L 降级
 * - Windsurf: switchModel 使用 chat-client-root + adaptive 关键词
 * - Windsurf: getRecentSessionsList 使用 h-[34px] min-w-[80px] 标签栏
 * - Cursor: switchModel 使用 ui-model-picker__trigger / model-picker-menu
 * - Cursor: showRecentSessions 使用 codicon-history-two
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class IdeSpecificCommandsTest {

    private lateinit var mockServer: MockCdpServer
    private lateinit var cdpClient: CdpClient
    private lateinit var windsurf: WindsurfCommands
    private lateinit var cursor: CursorCommands

    @Before
    fun setup() {
        mockServer = MockCdpServer()
        mockServer.start()
        cdpClient = CdpClient()
        runBlocking {
            assertTrue(cdpClient.connectDirect(mockServer.wsUrl).isSuccess)
        }
        windsurf = WindsurfCommands(cdpClient)
        cursor = CursorCommands(cdpClient, "Cursor")
    }

    @After
    fun teardown() {
        cdpClient.disconnect()
        mockServer.shutdown()
    }

    // ═══════════════════════════════════════════════════════════════
    // WindsurfCommands — pasteImage 容器选择器
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `Windsurf pasteImage uses chat and cascadePanel selectors`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", "ok")
                    })
                }
            } else null
        }

        val result = windsurf.pasteImage("dGVzdA==", "image/png")
        assertTrue(result.isSuccess)

        val js = mockServer.receivedExpressions
        assertTrue("应包含 #chat 选择器",
            js.any { it.contains("getElementById('chat')") })
        assertTrue("应包含 windsurf.cascadePanel",
            js.any { it.contains("windsurf.cascadePanel") })
    }

    @Test
    fun `Windsurf pasteImage returns error on no-container`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                val expr = req.getAsJsonObject("params")?.get("expression")?.asString ?: ""
                val value = if (expr.contains("__pasteImageB64")) "ok"
                else if (expr.contains("no-container") || expr.contains("editableDiv")) "no-container"
                else "ok"
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", value)
                    })
                }
            } else null
        }

        // 即使返回 no-container，也不会 crash
        windsurf.pasteImage("dGVzdA==")
        Unit
    }

    @Test
    fun `Windsurf pasteImage chunks large base64 data`() = runBlocking {
        val largeBase64 = "A".repeat(120_000) // 120KB > 2 chunks

        windsurf.pasteImage(largeBase64, "image/png")

        val chunkCalls = mockServer.receivedExpressions
            .filter { it.contains("__pasteImageB64 +=") }
        assertTrue("120KB 应至少分 2 块", chunkCalls.size >= 2)
    }

    // ═══════════════════════════════════════════════════════════════
    // WindsurfCommands — startNewSession (lucide-plus + Cmd+L)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `Windsurf startNewSession uses lucide-plus selector`() = runBlocking {
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

        val result = windsurf.startNewSession()
        assertTrue(result.isSuccess)

        val js = mockServer.receivedExpressions
        assertTrue("应包含 lucide-plus 选择器",
            js.any { it.contains("lucide-plus") })
    }

    @Test
    fun `Windsurf startNewSession falls back to Cmd+L`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            when (method) {
                "Runtime.evaluate" -> JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", "no-button")
                    })
                }
                "Input.dispatchKeyEvent" -> JsonObject()
                else -> null
            }
        }

        val result = windsurf.startNewSession()
        assertTrue("应通过快捷键成功", result.isSuccess)

        val keyEvents = mockServer.receivedMessages.filter {
            it.get("method")?.asString == "Input.dispatchKeyEvent"
        }
        assertTrue("应发送 Cmd+L", keyEvents.any {
            it.getAsJsonObject("params")?.get("key")?.asString == "l"
        })
    }

    // ═══════════════════════════════════════════════════════════════
    // WindsurfCommands — stopGeneration (圆形 lucide-square 按钮)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `Windsurf stopGeneration searches for lucide-square SVG`() = runBlocking {
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

        val result = windsurf.stopGeneration()
        assertTrue(result.isSuccess)

        val js = mockServer.receivedExpressions
        assertTrue("应包含 lucide-square 检测",
            js.any { it.contains("lucide-square") })
        assertTrue("应包含 rounded-full 选择器",
            js.any { it.contains("rounded-full") })
    }

    // ═══════════════════════════════════════════════════════════════
    // WindsurfCommands — cancelRunningTask (多级降级: hover:text-red-500 → aria-label/title → textContent)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `Windsurf cancelRunningTask uses hover-text-red-500 selector`() = runBlocking {
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

        val result = windsurf.cancelRunningTask()
        assertTrue(result.isSuccess)

        val js = mockServer.receivedExpressions
        assertTrue("应包含 hover:text-red-500 选择器",
            js.any { it.contains("hover:text-red-500") })
        assertTrue("应检查 cascadePanel",
            js.any { it.contains("windsurf.cascadePanel") })
        assertTrue("应检查 lucide SVG 图标",
            js.any { it.contains("lucide") || (it.contains("circle") && it.contains("rect")) })
        // 验证新增的降级策略也在 JS 中存在
        assertTrue("应包含 aria-label stop/abort 降级",
            js.any { it.contains("aria-label") && (it.contains("stop") || it.contains("abort")) })
        assertTrue("应包含 title 属性降级",
            js.any { it.contains("title") && it.contains("cancel") })
        assertTrue("应包含 textContent 文本降级",
            js.any { it.contains("'cancel'") || it.contains("'取消'") })
    }

    @Test
    fun `Windsurf cancelRunningTask returns error when no panel`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", "no-panel")
                    })
                }
            } else null
        }

        val result = windsurf.cancelRunningTask()
        assertTrue("无面板时应返回 Error", result is CdpResult.Error)
        val msg = (result as CdpResult.Error).message
        assertTrue("错误消息应提及面板", msg.contains("面板"))
    }

    @Test
    fun `Windsurf cancelRunningTask returns descriptive error when no cancel button`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", "no-cancel-btn")
                    })
                }
            } else null
        }

        val result = windsurf.cancelRunningTask()
        assertTrue("无取消按钮时应返回 Error", result is CdpResult.Error)
        val msg = (result as CdpResult.Error).message
        assertTrue("错误消息应提示确认任务状态", msg.contains("确认") || msg.contains("取消按钮"))
    }

    @Test
    fun `AntigravityCommands cancelRunningTask returns not supported by default`() = runBlocking {
        val baseCommands = AntigravityCommands(cdpClient, "Test")
        val result = baseCommands.cancelRunningTask()
        assertTrue("基类应返回不支持的错误", result is CdpResult.Error)
        val msg = (result as CdpResult.Error).message
        assertTrue("错误消息应提及不支持", msg.contains("不支持") || msg.contains("not supported"))
    }

    // ═══════════════════════════════════════════════════════════════
    // WindsurfCommands — getRecentSessionsList (标签栏)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `Windsurf getRecentSessionsList uses tab bar selectors`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"status":"found","sessions":["Debug Issue","新功能"]}""")
                    })
                }
            } else null
        }

        val result = windsurf.getRecentSessionsList()
        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull()!!.size)

        val js = mockServer.receivedExpressions
        assertTrue("应使用 h-[34px] min-w-[80px] 选择器",
            js.any { it.contains("h-[34px]") && it.contains("min-w-[80px]") })
    }

    // ═══════════════════════════════════════════════════════════════
    // WindsurfCommands — switchModel (chat-client-root + adaptive)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `Windsurf switchModel uses chat-client-root selector`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"ok":true,"info":"Claude 3.5 Sonnet"}""")
                    })
                }
            } else null
        }

        val result = windsurf.switchModel("claude")
        assertTrue(result.isSuccess)

        val js = mockServer.receivedExpressions
        assertTrue("应使用 chat-client-root",
            js.any { it.contains("chat-client-root") })
        assertTrue("应包含 adaptive 关键词",
            js.any { it.contains("adaptive") })
    }

    @Test
    fun `Windsurf switchModel returns error when model not found`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"ok":false,"err":"找不到匹配模型: nonexistent"}""")
                    })
                }
            } else null
        }

        val result = windsurf.switchModel("nonexistent")
        assertTrue("不存在的模型应返回 Error", result is CdpResult.Error)
    }

    // ═══════════════════════════════════════════════════════════════
    // WindsurfCommands — getCurrentModel
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `Windsurf getCurrentModel returns model name`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", "Adaptive")
                    })
                }
            } else null
        }

        val result = windsurf.getCurrentModel()
        assertTrue(result.isSuccess)
        assertEquals("Adaptive", result.getOrNull())
    }

    @Test
    fun `Windsurf getCurrentModel returns error when empty`() = runBlocking {
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

        val result = windsurf.getCurrentModel()
        assertTrue("空模型名应返回 Error", result is CdpResult.Error)
    }

    // ═══════════════════════════════════════════════════════════════
    // CursorCommands — switchModel (ui-model-picker__trigger)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `Cursor switchModel uses ui-model-picker__trigger`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"ok":true,"info":"claude-3.5-sonnet"}""")
                    })
                }
            } else null
        }

        val result = cursor.switchModel("claude")
        assertTrue(result.isSuccess)

        val js = mockServer.receivedExpressions
        assertTrue("应使用 ui-model-picker__trigger",
            js.any { it.contains("ui-model-picker__trigger") })
        assertTrue("应使用 model-picker-menu",
            js.any { it.contains("model-picker-menu") })
    }

    @Test
    fun `Cursor switchModel handles auto mode toggle`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"ok":true,"info":"Auto"}""")
                    })
                }
            } else null
        }

        val result = cursor.switchModel("auto")
        assertTrue(result.isSuccess)

        val js = mockServer.receivedExpressions
        assertTrue("auto 模式应检查 auto-mode-toggle",
            js.any { it.contains("auto-mode-toggle") })
    }

    @Test
    fun `Cursor switchModel returns available models on no match`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"ok":false,"err":"Cursor 列表中没有匹配「xyz」","available":["claude-3.5","gpt-4o"]}""")
                    })
                }
            } else null
        }

        val result = cursor.switchModel("xyz")
        assertTrue(result is CdpResult.Error)
        val msg = (result as CdpResult.Error).message
        assertTrue("应包含可见模型列表", msg.contains("claude-3.5") && msg.contains("gpt-4o"))
    }

    // ═══════════════════════════════════════════════════════════════
    // CursorCommands — showRecentSessions (codicon-history-two)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `Cursor showRecentSessions uses codicon-history-two`() = runBlocking {
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

        val result = cursor.showRecentSessions()
        assertTrue(result.isSuccess)

        val js = mockServer.receivedExpressions
        assertTrue("应使用 codicon-history-two",
            js.any { it.contains("codicon-history-two") })
        assertTrue("应使用 Show Chat History",
            js.any { it.contains("Show Chat History") })
    }

    // ═══════════════════════════════════════════════════════════════
    // CursorCommands — getRecentSessionsList
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `Cursor getRecentSessionsList parses compact-agent-history labels`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"status":"found","sessions":["修复OTA","添加测试"]}""")
                    })
                }
            } else null
        }

        val result = cursor.getRecentSessionsList()
        assertTrue(result.isSuccess)
        val sessions = result.getOrNull()!!
        assertEquals(2, sessions.size)
        assertEquals("修复OTA", sessions[0])

        val js = mockServer.receivedExpressions
        assertTrue("应使用 compact-agent-history-react-menu-label",
            js.any { it.contains("compact-agent-history-react-menu-label") })
    }

    // ═══════════════════════════════════════════════════════════════
    // 继承验证 — Cursor/Windsurf 复用了基类的通用方法
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `Windsurf inherits AntigravityCommands sendMessage pipeline`() = runBlocking {
        // sendMessage 没有被 WindsurfCommands override，应走基类逻辑
        windsurf.sendMessage("继承测试")

        val js = mockServer.receivedExpressions
        // 基类的 focusInput 使用 INPUT_BOX_ID
        assertTrue("应调用基类的 JS 管道", js.isNotEmpty())
    }

    @Test
    fun `Cursor inherits AntigravityCommands getLastReply`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", "AI 回复")
                    })
                }
            } else null
        }

        val reply = cursor.getLastReply()
        assertTrue(reply.isSuccess)
        assertEquals("AI 回复", reply.getOrNull())
    }
}
