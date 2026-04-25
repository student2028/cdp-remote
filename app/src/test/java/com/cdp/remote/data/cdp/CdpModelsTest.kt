package com.cdp.remote.data.cdp

import org.junit.Assert.*
import org.junit.Test

/**
 * CdpResult、CdpPage、HostInfo 等模型类的单元测试
 */
class CdpModelsTest {

    // ─── CdpResult ──────────────────────────────────────────────────

    @Test
    fun `CdpResult Success stores data correctly`() {
        val result: CdpResult<String> = CdpResult.Success("hello")
        assertTrue(result.isSuccess)
        assertEquals("hello", result.getOrNull())
        assertEquals("hello", result.getOrThrow())
    }

    @Test
    fun `CdpResult Error stores message correctly`() {
        val result: CdpResult<String> = CdpResult.Error("failed")
        assertFalse(result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test(expected = RuntimeException::class)
    fun `CdpResult Error getOrThrow throws RuntimeException`() {
        val result: CdpResult<String> = CdpResult.Error("boom")
        result.getOrThrow()
    }

    @Test
    fun `CdpResult Error can be assigned to any generic type`() {
        // CdpResult.Error 是 CdpResult<Nothing>，应能赋值给任何 CdpResult<T>
        val strResult: CdpResult<String> = CdpResult.Error("e1")
        val intResult: CdpResult<Int> = CdpResult.Error("e2")
        val listResult: CdpResult<List<String>> = CdpResult.Error("e3")
        assertFalse(strResult.isSuccess)
        assertFalse(intResult.isSuccess)
        assertFalse(listResult.isSuccess)
    }

    @Test
    fun `CdpResult Success with null data`() {
        val result: CdpResult<String?> = CdpResult.Success(null)
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }

    // ─── CdpPage ────────────────────────────────────────────────────

    @Test
    fun `CdpPage isWorkbench detects workbench pages`() {
        val page = CdpPage(
            id = "1", type = "page",
            title = "Antigravity",
            url = "file:///workbench.html",
            webSocketDebuggerUrl = "ws://localhost:9333/devtools/page/1"
        )
        assertTrue(page.isWorkbench)
    }

    @Test
    fun `CdpPage isWorkbench rejects non-page types`() {
        val page = CdpPage(
            id = "1", type = "background_page",
            title = "Antigravity",
            url = "file:///workbench.html",
            webSocketDebuggerUrl = "ws://localhost:9333/devtools/page/1"
        )
        assertFalse(page.isWorkbench)
    }

    @Test
    fun `CdpPage isWorkbench rejects jetski URLs`() {
        val page = CdpPage(
            id = "1", type = "page",
            title = "Test",
            url = "file:///jetski/workbench.html",
            webSocketDebuggerUrl = "ws://localhost:9333/devtools/page/1"
        )
        assertFalse(page.isWorkbench)
    }

    @Test
    fun `CdpPage cdpPort extracts relay port`() {
        val page = CdpPage(
            id = "1", type = "page", title = "T", url = "",
            webSocketDebuggerUrl = "ws://192.168.1.5:19335/cdp/9333/devtools/page/abc"
        )
        assertEquals(9333, page.cdpPort)
    }

    @Test
    fun `CdpPage cdpPort extracts direct port`() {
        val page = CdpPage(
            id = "1", type = "page", title = "T", url = "",
            webSocketDebuggerUrl = "ws://127.0.0.1:9334/devtools/page/abc"
        )
        assertEquals(9334, page.cdpPort)
    }

    // ─── ElectronAppType.fromAppName：唯一允许「字符串 → IDE 类型」的入口 ─────

    @Test
    fun `fromAppName maps known names to enum values`() {
        assertEquals(ElectronAppType.ANTIGRAVITY, ElectronAppType.fromAppName("Antigravity"))
        assertEquals(ElectronAppType.CURSOR, ElectronAppType.fromAppName("Cursor"))
        assertEquals(ElectronAppType.WINDSURF, ElectronAppType.fromAppName("Windsurf"))
        assertEquals(ElectronAppType.CODEX, ElectronAppType.fromAppName("Codex"))
    }

    @Test
    fun `fromAppName is case-insensitive and trims whitespace`() {
        assertEquals(ElectronAppType.CURSOR, ElectronAppType.fromAppName("cursor"))
        assertEquals(ElectronAppType.CURSOR, ElectronAppType.fromAppName("CURSOR"))
        assertEquals(ElectronAppType.CURSOR, ElectronAppType.fromAppName("  Cursor  "))
    }

    @Test
    fun `fromAppName returns UNKNOWN for null, empty, blank, or unrecognized`() {
        assertEquals(ElectronAppType.UNKNOWN, ElectronAppType.fromAppName(null))
        assertEquals(ElectronAppType.UNKNOWN, ElectronAppType.fromAppName(""))
        assertEquals(ElectronAppType.UNKNOWN, ElectronAppType.fromAppName("   "))
        assertEquals(ElectronAppType.UNKNOWN, ElectronAppType.fromAppName("SomeFutureIDE"))
    }

    // ─── CdpPage.appType 是构造时钉死的字段 —— 自身不做任何推断 ────────────

    @Test
    fun `CdpPage stores appType verbatim - never inspects url or title`() {
        // 反复证明：哪怕 url/title 强烈暗示别的 IDE，CdpPage 也只信构造方给的 appType。
        // title 里写"Cursor"、url 里写"Windsurf.app"，构造方说 Antigravity 就是 Antigravity。
        val page = CdpPage(
            id = "1", type = "page",
            title = "CursorPresetModelsTest.kt — voice7",
            url = "file:///Applications/Windsurf.app/Contents/.../workbench.html",
            webSocketDebuggerUrl = "",
            appType = ElectronAppType.ANTIGRAVITY
        )
        assertEquals(ElectronAppType.ANTIGRAVITY, page.appType)
    }

    @Test
    fun `CdpPage appType defaults to UNKNOWN when caller did not specify`() {
        // 上游没告诉我们就老老实实是 UNKNOWN，绝不"猜"。
        val page = CdpPage(
            id = "1", type = "page",
            title = "CursorPresetModelsTest.kt — voice7",
            url = "file:///Applications/Windsurf.app/Contents/.../workbench.html",
            webSocketDebuggerUrl = ""
        )
        assertEquals(ElectronAppType.UNKNOWN, page.appType)
    }

    @Test
    fun `regression 9444 - Windsurf target with Cursor-named file is identified as Windsurf`() {
        // 2026-04 的 9444 事故还原：中继 /targets 给的权威 appName 是 "Windsurf"，
        // 但 page.title 因为用户打开了 CursorPresetModelsTest.kt 而含 "Cursor" 字样。
        // 数据入口走 ElectronAppType.fromAppName，下游 CdpPage.appType 一锤定音 → Windsurf。
        val targetAppName = "Windsurf"  // 中继按端口映射来的权威字段
        val page = CdpPage(
            id = "1", type = "page",
            title = "CursorPresetModelsTest.kt — voice7",
            url = "file:///Applications/Windsurf.app/Contents/.../workbench.html",
            webSocketDebuggerUrl = "",
            appType = ElectronAppType.fromAppName(targetAppName)
        )
        assertEquals(ElectronAppType.WINDSURF, page.appType)
    }

    // ─── HostInfo ────────────────────────────────────────────────────

    @Test
    fun `HostInfo address format`() {
        val host = HostInfo("192.168.1.10", 19335)
        assertEquals("192.168.1.10:19335", host.address)
    }

    @Test
    fun `HostInfo httpUrl format`() {
        val host = HostInfo("192.168.1.10", 19335)
        assertEquals("http://192.168.1.10:19335", host.httpUrl)
    }

    @Test
    fun `HostInfo otaHttpBaseUrl always uses RELAY_OTA_HTTP_PORT`() {
        val host1 = HostInfo("10.0.0.1", 19335)
        val host2 = HostInfo("10.0.0.1", 19336)
        // OTA 端口固定为 19336
        assertEquals("http://10.0.0.1:$RELAY_OTA_HTTP_PORT", host1.otaHttpBaseUrl)
        assertEquals("http://10.0.0.1:$RELAY_OTA_HTTP_PORT", host2.otaHttpBaseUrl)
    }

    @Test
    fun `HostInfo displayName uses name when available`() {
        val host = HostInfo("10.0.0.1", 19335, name = "MacBook")
        assertEquals("MacBook", host.displayName)
    }

    @Test
    fun `HostInfo displayName falls back to address`() {
        val host = HostInfo("10.0.0.1", 19335)
        assertEquals("10.0.0.1:19335", host.displayName)
    }

    // ─── RELAY_OTA_HTTP_PORT ────────────────────────────────────────

    @Test
    fun `RELAY_OTA_HTTP_PORT is 19336`() {
        assertEquals(19336, RELAY_OTA_HTTP_PORT)
    }

    // ─── parsePages ─────────────────────────────────────────────────

    @Test
    fun `parsePages parses valid JSON`() {
        val json = """[
            {"id":"1","type":"page","title":"Test","url":"file:///workbench.html","webSocketDebuggerUrl":"ws://localhost:9333/devtools/page/1"}
        ]"""
        val pages = parsePages(json)
        assertEquals(1, pages.size)
        assertEquals("1", pages[0].id)
        assertEquals("page", pages[0].type)
        assertEquals("Test", pages[0].title)
    }

    @Test
    fun `parsePages returns empty on invalid JSON`() {
        assertEquals(emptyList<CdpPage>(), parsePages("not json"))
        assertEquals(emptyList<CdpPage>(), parsePages(""))
        assertEquals(emptyList<CdpPage>(), parsePages("{}"))
    }

    @Test
    fun `parsePages handles missing fields gracefully`() {
        val json = """[{"id":"1"}]"""
        val pages = parsePages(json)
        assertEquals(1, pages.size)
        assertEquals("1", pages[0].id)
        assertEquals("", pages[0].type)
        assertEquals("", pages[0].title)
    }

    // ─── ChatMessage ────────────────────────────────────────────────

    @Test
    fun `ChatMessage defaults`() {
        val msg = ChatMessage(role = MessageRole.USER, content = "hello")
        assertEquals(MessageRole.USER, msg.role)
        assertEquals("hello", msg.content)
        assertFalse(msg.isStreaming)
        assertTrue(msg.images.isEmpty())
        assertTrue(msg.timestamp > 0)
    }

    // ─── AutoApprovePolicy ──────────────────────────────────────────

    @Test
    fun `AutoApprovePolicy only allows when connected`() {
        assertTrue(AutoApprovePolicy.shouldAttemptCdpAutoAccept(ConnectionState.CONNECTED))
        assertFalse(AutoApprovePolicy.shouldAttemptCdpAutoAccept(ConnectionState.DISCONNECTED))
        assertFalse(AutoApprovePolicy.shouldAttemptCdpAutoAccept(ConnectionState.CONNECTING))
        assertFalse(AutoApprovePolicy.shouldAttemptCdpAutoAccept(ConnectionState.RECONNECTING))
        assertFalse(AutoApprovePolicy.shouldAttemptCdpAutoAccept(ConnectionState.ERROR))
    }

    // ─── CdpResponse ────────────────────────────────────────────────

    @Test
    fun `CdpResponse isSuccess when no error`() {
        val resp = CdpResponse(id = 1, result = com.google.gson.JsonObject())
        assertTrue(resp.isSuccess)
    }

    @Test
    fun `CdpResponse isSuccess is false when error present`() {
        val resp = CdpResponse(id = 1, error = CdpError(code = -32600, message = "Invalid"))
        assertFalse(resp.isSuccess)
    }
}
