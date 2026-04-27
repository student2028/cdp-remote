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
 * CodexCommands 集成测试 — 验证 Codex 特有的 DOM 选择器和响应解析。
 *
 * Codex 的 UI 结构与 Antigravity 完全不同：
 * - 输入框: ProseMirror[data-codex-composer="true"]
 * - 消息: [data-content-search-unit-key] 以 :assistant / :user 结尾
 * - 模型选择: 按钮文本关键字匹配（无固定 XPath）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CodexCommandsIntegrationTest {

    private lateinit var mockServer: MockCdpServer
    private lateinit var cdpClient: CdpClient
    private lateinit var codex: CodexCommands

    @Before
    fun setup() {
        mockServer = MockCdpServer()
        mockServer.start()
        cdpClient = CdpClient()
        runBlocking {
            assertTrue(cdpClient.connectDirect(mockServer.wsUrl).isSuccess)
        }
        codex = CodexCommands(cdpClient)
    }

    @After
    fun teardown() {
        cdpClient.disconnect()
        mockServer.shutdown()
    }

    // ═══════════════════════════════════════════════════════════════
    // focusInput — Codex 使用 ProseMirror 选择器
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `focusInput uses ProseMirror data-codex-composer selector`() = runBlocking {
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

        val result = codex.focusInput()
        assertTrue(result.isSuccess)

        val js = mockServer.receivedExpressions
        assertTrue("应使用 data-codex-composer 选择器",
            js.any { it.contains("data-codex-composer") })
    }

    @Test
    fun `focusInput returns error when ProseMirror not found`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", "no-input")
                    })
                }
            } else null
        }

        val result = codex.focusInput()
        assertTrue("ProseMirror 不存在应返回 Error", result is CdpResult.Error)
    }

    // ═══════════════════════════════════════════════════════════════
    // setInputText — Codex 使用 execCommand
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `setInputText uses execCommand insertText for ProseMirror`() = runBlocking {
        codex.setInputText("Hello Codex")

        val js = mockServer.receivedExpressions
        assertTrue("应使用 execCommand 插入文本",
            js.any { it.contains("execCommand") && it.contains("insertText") })
        assertTrue("应包含消息文本",
            js.any { it.contains("Hello Codex") })
    }

    @Test
    fun `setInputText escapes special characters for Codex`() = runBlocking {
        codex.setInputText("it's a test\\path\nnewline")

        val js = mockServer.receivedExpressions
        assertTrue("单引号应被转义",
            js.any { it.contains("\\'") })
        assertTrue("反斜杠应被转义",
            js.any { it.contains("\\\\") })
    }

    // ═══════════════════════════════════════════════════════════════
    // clickSendButton — Codex 使用 size-token-button-composer
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `clickSendButton searches for Codex-specific button class`() = runBlocking {
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

        val result = codex.clickSendButton()
        assertTrue(result.isSuccess)

        val js = mockServer.receivedExpressions
        assertTrue("应搜索 size-token-button-composer",
            js.any { it.contains("size-token-button-composer") })
    }

    @Test
    fun `clickSendButton falls back to Enter key when no button`() = runBlocking {
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

        codex.clickSendButton()

        // 应有 keyDown + keyUp for Enter
        val keyEvents = mockServer.receivedMessages.filter {
            it.get("method")?.asString == "Input.dispatchKeyEvent"
        }
        assertTrue("应发送 Enter 键", keyEvents.size >= 2)
    }

    // ═══════════════════════════════════════════════════════════════
    // getLastReply — Codex 使用 data-content-search-unit-key
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `getLastReply uses Codex data-content-search-unit-key selector`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", "This is Claude's answer from Codex")
                    })
                }
            } else null
        }

        val reply = codex.getLastReply()
        assertTrue(reply.isSuccess)
        assertEquals("This is Claude's answer from Codex", reply.getOrNull())

        val js = mockServer.receivedExpressions
        assertTrue("应使用 data-content-search-unit-key",
            js.any { it.contains("data-content-search-unit-key") })
    }

    // ═══════════════════════════════════════════════════════════════
    // getChatHistory — 解析 JSON 数组
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `getChatHistory parses Codex chat messages`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """[{"role":"USER","content":"帮我写代码"},{"role":"ASSISTANT","content":"好的"}]""")
                    })
                }
            } else null
        }

        val history = codex.getChatHistory()
        assertTrue(history.isSuccess)
        val messages = history.getOrNull()!!
        assertEquals(2, messages.size)
        assertEquals(MessageRole.USER, messages[0].role)
        assertEquals("帮我写代码", messages[0].content)
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
    }

    @Test
    fun `getChatHistory handles empty array`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", "[]")
                    })
                }
            } else null
        }

        val history = codex.getChatHistory()
        assertTrue(history.isSuccess)
        assertTrue(history.getOrNull()!!.isEmpty())
    }

    @Test
    fun `getChatHistory handles malformed JSON`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", "not valid json")
                    })
                }
            } else null
        }

        val history = codex.getChatHistory()
        assertTrue("畸形 JSON 应返回 Error", history is CdpResult.Error)
    }

    // ═══════════════════════════════════════════════════════════════
    // getRecentSessionsList — 会话列表 JSON 解析
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `getRecentSessionsList parses session titles`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"status":"found","sessions":["项目重构","Bug修复","新功能开发"]}""")
                    })
                }
            } else null
        }

        val result = codex.getRecentSessionsList()
        assertTrue(result.isSuccess)
        val sessions = result.getOrNull()!!
        assertEquals(3, sessions.size)
        assertEquals("项目重构", sessions[0])
    }

    @Test
    fun `getRecentSessionsList returns error when no items`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"status":"no-items"}""")
                    })
                }
            } else null
        }

        val result = codex.getRecentSessionsList()
        assertTrue("无会话应返回 Error", result is CdpResult.Error)
    }

    // ═══════════════════════════════════════════════════════════════
    // autoAcceptActions — 找到按钮后发鼠标事件
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `autoAcceptActions clicks allow button and dispatches mouse events`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            when (method) {
                "Runtime.evaluate" -> JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"found":true,"x":400.0,"y":250.0}""")
                    })
                }
                "Input.dispatchMouseEvent" -> JsonObject()
                else -> null
            }
        }

        val accepted = codex.autoAcceptActions()
        assertTrue("应找到 allow 按钮", accepted)

        val mouseEvents = mockServer.receivedMessages.filter {
            it.get("method")?.asString == "Input.dispatchMouseEvent"
        }
        assertTrue("应有 ≥3 个鼠标事件 (move+press+release)", mouseEvents.size >= 3)
    }

    // ═══════════════════════════════════════════════════════════════
    // startNewSession — 点击按钮或回退到 Cmd+N
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `startNewSession falls back to Cmd+N when button not found`() = runBlocking {
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

        val result = codex.startNewSession()
        assertTrue("应成功（通过快捷键）", result.isSuccess)

        // 应发送 Cmd+N 键事件
        val keyEvents = mockServer.receivedMessages.filter {
            it.get("method")?.asString == "Input.dispatchKeyEvent"
        }
        assertTrue("应有键盘事件", keyEvents.size >= 2)
        assertTrue("应包含 'n' 键", keyEvents.any {
            it.getAsJsonObject("params")?.get("key")?.asString == "n"
        })
    }

    // ═══════════════════════════════════════════════════════════════
    // checkAndRetryIfBusy — Codex 版三分支（与 Antigravity 独立实现）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `checkAndRetryIfBusy Codex returns false when no error`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"status":"ok"}""")
                    })
                }
            } else null
        }

        val result = codex.checkAndRetryIfBusy()
        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
    }

    @Test
    fun `checkAndRetryIfBusy Codex clicks retry button`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            when (method) {
                "Runtime.evaluate" -> JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"status":"retried","x":300.0,"y":400.0}""")
                    })
                }
                "Input.dispatchMouseEvent" -> JsonObject()
                else -> null
            }
        }

        val result = codex.checkAndRetryIfBusy()
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
    }

    @Test
    fun `switchProject escapes project name before embedding in JavaScript`() = runBlocking {
        codex.switchProject("a'b\\c")

        val expression = mockServer.receivedExpressions.last()
        assertTrue(
            "project name should be represented as a JS string literal",
            expression.contains("""var projectName = "a'b\\c";""")
        )
        assertFalse(
            "raw project name must not be interpolated inside a JS single-quoted string",
            expression.contains("=== 'a\\'b\\c'")
        )
    }
}
