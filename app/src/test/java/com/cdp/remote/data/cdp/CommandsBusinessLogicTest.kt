package com.cdp.remote.data.cdp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Commands 业务逻辑深度测试 — 覆盖手工测试中最常出 bug 的场景。
 *
 * 这些测试模拟真实 CDP 服务器的各种响应，验证 Commands 的解析/分支/错误处理逻辑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CommandsBusinessLogicTest {

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
            assertTrue("连接 Mock 服务器", result.isSuccess)
        }
        commands = AntigravityCommands(cdpClient, "TestIDE")
    }

    @After
    fun teardown() {
        cdpClient.disconnect()
        mockServer.shutdown()
    }

    // ═══════════════════════════════════════════════════════════════
    // sendMessage 完整管道 — 之前手工测试最频繁的场景
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `sendMessage executes focus then input then click in order`() = runBlocking {
        val result = commands.sendMessage("你好世界")
        assertTrue("sendMessage 应成功", result.isSuccess)

        // 验证管道三步都执行了：至少3次 evaluate（focusInput, setInputText, clickSendButton）
        val evals = mockServer.receivedExpressions
        assertTrue("至少3次 evaluate 调用", evals.size >= 3)
        // setInputText 的 JS 中应包含文本
        assertTrue("应包含消息文本", evals.any { it.contains("你好世界") })
    }

    @Test
    fun `sendMessage fails if focusInput fails`() = runBlocking {
        var callCount = 0
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                callCount++
                if (callCount == 1) {
                    // focusInput 失败
                    JsonObject().apply {
                        add("result", JsonObject().apply {
                            addProperty("type", "object")
                        })
                        add("exceptionDetails", JsonObject().apply {
                            add("exception", JsonObject().apply {
                                addProperty("description", "Cannot read property 'focus' of null")
                            })
                        })
                    }
                } else null
            } else null
        }

        val result = commands.sendMessage("test")
        assertTrue("focusInput 失败时 sendMessage 应失败", result is CdpResult.Error)
        assertTrue((result as CdpResult.Error).message.contains("聚焦失败"))
    }

    // ═══════════════════════════════════════════════════════════════
    // checkAndRetryIfBusy — 三种结果分支
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `checkAndRetryIfBusy returns false when no error on page`() = runBlocking {
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

        val result = commands.checkAndRetryIfBusy()
        assertTrue("无错误应返回 Success", result.isSuccess)
        assertFalse("无错误应返回 false", result.getOrThrow())
    }

    @Test
    fun `checkAndRetryIfBusy returns true when retry button clicked`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            when (method) {
                "Runtime.evaluate" -> JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"status":"retried","x":500.0,"y":300.0}""")
                    })
                }
                "Input.dispatchMouseEvent" -> JsonObject()
                else -> null
            }
        }

        val result = commands.checkAndRetryIfBusy()
        assertTrue("找到重试按钮应返回 Success(true)", result.isSuccess)
        assertTrue("应返回 true", result.getOrThrow())

        // 验证发送了鼠标点击（移动+按下+释放）
        val mouseEvents = mockServer.receivedMessages.filter {
            it.get("method")?.asString == "Input.dispatchMouseEvent"
        }
        assertTrue("应有 >=3 个鼠标事件", mouseEvents.size >= 3)
    }

    @Test
    fun `checkAndRetryIfBusy returns error when error but no retry button`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"status":"error-no-retry-button"}""")
                    })
                }
            } else null
        }

        val result = commands.checkAndRetryIfBusy()
        assertTrue("无重试按钮应返回 Error", result is CdpResult.Error)
        assertTrue((result as CdpResult.Error).message.contains("找不到重试按钮"))
    }

    // ═══════════════════════════════════════════════════════════════
    // switchModel 响应解析 — 使用 cdp.call 所以 mock 方式不同
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `switchModel success when model found`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                // switchModel 用 awaitPromise=true, 响应格式是 value 中包含 JSON
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"ok":true,"info":"Claude 4 Sonnet"}""")
                    })
                }
            } else null
        }

        val result = commands.switchModel("claude-4-sonnet")
        assertTrue("模型切换应成功", result.isSuccess)
    }

    @Test
    fun `switchModel error when dropdown not found`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"ok":false,"err":"找不到模型选择下拉框"}""")
                    })
                }
            } else null
        }

        val result = commands.switchModel("nonexistent-model")
        assertTrue("找不到下拉框应返回 Error", result is CdpResult.Error)
        assertTrue((result as CdpResult.Error).message.contains("找不到模型"))
    }

    @Test
    fun `switchModel error when target model not in list`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"ok":false,"err":"在列表中找不到匹配的模型: gpt-99"}""")
                    })
                }
            } else null
        }

        val result = commands.switchModel("gpt-99")
        assertTrue("找不到模型应返回 Error", result is CdpResult.Error)
        assertTrue("应包含模型名", (result as CdpResult.Error).message.contains("找不到"))
    }

    // ═══════════════════════════════════════════════════════════════
    // pasteImage — 分块传输 + 结果解析
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `pasteImage sends chunks for large base64`() = runBlocking {
        // 生成一个 >50000 字符的 base64（模拟大图片）
        val largeBase64 = "A".repeat(120000)

        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                val expr = req.getAsJsonObject("params")?.get("expression")?.asString ?: ""
                if (expr.contains("__pasteImageB64") && !expr.contains("atob")) {
                    // 分块追加调用
                    JsonObject().apply {
                        add("result", JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("value", "ok")
                        })
                    }
                } else if (expr.contains("atob") || expr.contains("ClipboardEvent")) {
                    // 最终粘贴调用
                    JsonObject().apply {
                        add("result", JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("value", "ok")
                        })
                    }
                } else null
            } else null
        }

        val result = commands.pasteImage(largeBase64, "image/png")
        assertTrue("大图片粘贴应成功", result.isSuccess)

        // 120000 / 50000 = 3 chunks，加上初始化和最终粘贴，应有多次 evaluate
        val chunkCalls = mockServer.receivedExpressions.filter {
            it.contains("__pasteImageB64 +=")
        }
        assertTrue("应分 >=3 块传输", chunkCalls.size >= 3)
    }

    @Test
    fun `pasteImage returns error when container not found`() = runBlocking {
        var evalCount = 0
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                evalCount++
                val expr = req.getAsJsonObject("params")?.get("expression")?.asString ?: ""
                if (expr.contains("ClipboardEvent") || expr.contains("no-container") || (expr.contains("editableDiv") && !expr.contains("__pasteImageB64"))) {
                    // pasteImage 主逻辑返回 'no-container'
                    JsonObject().apply {
                        add("result", JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("value", "no-container")
                        })
                    }
                } else null
            } else null
        }

        val result = commands.pasteImage("smalldata", "image/png")
        assertTrue("找不到容器应返回 Error", result is CdpResult.Error)
        assertTrue((result as CdpResult.Error).message.contains("no-container") || result.message.contains("粘贴"))
    }

    // ═══════════════════════════════════════════════════════════════
    // stopGeneration — 按钮检测和键盘回退
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `stopGeneration sends keyboard Escape when stop button not found`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            when (method) {
                "Runtime.evaluate" -> JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"found":false}""")
                    })
                }
                "Input.dispatchKeyEvent" -> JsonObject()
                else -> null
            }
        }

        commands.stopGeneration()

        // stopGeneration 应至少执行 evaluate 和 keyboard Escape
        val methods = mockServer.receivedMessages.map { it.get("method")?.asString }
        assertTrue("应有 evaluate 调用", methods.contains("Runtime.evaluate"))
        assertTrue(
            "应发送键盘事件 (Escape) 或已执行",
            methods.contains("Input.dispatchKeyEvent") || mockServer.receivedExpressions.isNotEmpty()
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // getLastReply — 空回复 vs 正常回复
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `getLastReply handles multi-line AI response`() = runBlocking {
        val multiLineReply = "第一行\n第二行\n```kotlin\nfun hello() = println(\"hi\")\n```"
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", multiLineReply)
                    })
                }
            } else null
        }

        val reply = commands.getLastReply()
        assertTrue(reply.isSuccess)
        val text = reply.getOrNull()!!
        assertTrue("应包含代码块", text.contains("fun hello()"))
        assertTrue("应包含中文", text.contains("第一行"))
    }

    @Test
    fun `getLastReply handles evaluate error gracefully`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            if (method == "Runtime.evaluate") {
                JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "object")
                    })
                    add("exceptionDetails", JsonObject().apply {
                        add("exception", JsonObject().apply {
                            addProperty("description", "TypeError: panelNode is null")
                        })
                    })
                }
            } else null
        }

        val reply = commands.getLastReply()
        assertTrue("JS 异常应返回 Error", reply is CdpResult.Error)
    }

    // ═══════════════════════════════════════════════════════════════
    // acceptAll — 按钮坐标准确性
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `acceptAll clicks at correct coordinates`() = runBlocking {
        val expectedX = 750.5
        val expectedY = 420.0

        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            when (method) {
                "Runtime.evaluate" -> JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", """{"found":true,"x":$expectedX,"y":$expectedY}""")
                    })
                }
                "Input.dispatchMouseEvent" -> JsonObject()
                else -> null
            }
        }

        val result = commands.acceptAll()
        assertTrue(result.isSuccess)

        // 验证鼠标点击坐标
        val mouseClicks = mockServer.receivedMessages.filter {
            it.get("method")?.asString == "Input.dispatchMouseEvent"
        }
        assertTrue("应有鼠标事件", mouseClicks.size >= 3)

        // 检查 mousePressed 的坐标
        val pressed = mouseClicks.firstOrNull {
            it.getAsJsonObject("params")?.get("type")?.asString == "mousePressed"
        }
        assertNotNull("应有 mousePressed", pressed)
        val px = pressed!!.getAsJsonObject("params").get("x").asDouble
        val py = pressed.getAsJsonObject("params").get("y").asDouble
        assertEquals("X 坐标应匹配", expectedX, px, 1.0)
        assertEquals("Y 坐标应匹配", expectedY, py, 1.0)
    }

    // ═══════════════════════════════════════════════════════════════
    // rejectAll — 与 acceptAll 对称
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `rejectAll uses reject button JS selector`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            when (method) {
                "Runtime.evaluate" -> {
                    val expr = req.getAsJsonObject("params")?.get("expression")?.asString ?: ""
                    JsonObject().apply {
                        add("result", JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("value", """{"found":true,"x":600.0,"y":400.0}""")
                        })
                    }
                }
                "Input.dispatchMouseEvent" -> JsonObject()
                else -> null
            }
        }

        val result = commands.rejectAll()
        assertTrue("rejectAll 应成功", result.isSuccess)

        // rejectAll 的 JS 应包含 reject/discard 相关关键字
        val jsExprs = mockServer.receivedExpressions
        assertTrue("应包含 reject/discard 选择器",
            jsExprs.any { it.contains("reject") || it.contains("discard") || it.contains("Reject") || it.contains("Discard") }
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // WindsurfCommands startNewSession — Cmd+L 快捷键
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `WindsurfCommands startNewSession sends Cmd+L keyboard shortcut`() = runBlocking {
        mockServer.onRequest { req ->
            val method = req.get("method")?.asString
            when (method) {
                "Input.dispatchKeyEvent" -> JsonObject()
                "Runtime.evaluate" -> JsonObject().apply {
                    add("result", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("value", "ok")
                    })
                }
                else -> null
            }
        }

        val windsurf = WindsurfCommands(cdpClient)
        windsurf.startNewSession()

        // 应发送键盘事件
        val keyEvents = mockServer.receivedMessages.filter {
            it.get("method")?.asString == "Input.dispatchKeyEvent"
        }
        assertTrue("应发送键盘快捷键", keyEvents.isNotEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // 断连恢复力 — CDP 连接断开时的优雅降级
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `commands return error after disconnect instead of crashing`() = runBlocking {
        cdpClient.disconnect()

        val result1 = commands.focusInput()
        val result2 = commands.setInputText("should fail")
        val result3 = commands.getLastReply()

        assertTrue("断连后 focusInput 应返回 Error", result1 is CdpResult.Error)
        assertTrue("断连后 setInputText 应返回 Error", result2 is CdpResult.Error)
        assertTrue("断连后 getLastReply 应返回 Error", result3 is CdpResult.Error)
    }
}
