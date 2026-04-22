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

    @Test
    fun `CdpPage appType identifies Antigravity`() {
        val page = CdpPage(
            id = "1", type = "page",
            title = "Antigravity - My Project",
            url = "file:///Antigravity.app/workbench.html",
            webSocketDebuggerUrl = ""
        )
        assertEquals(ElectronAppType.ANTIGRAVITY, page.appType)
    }

    @Test
    fun `CdpPage appType identifies Windsurf`() {
        val page = CdpPage(
            id = "1", type = "page",
            title = "Windsurf - voice7",
            url = "file:///Windsurf.app/workbench.html",
            webSocketDebuggerUrl = ""
        )
        assertEquals(ElectronAppType.WINDSURF, page.appType)
    }

    @Test
    fun `CdpPage appType identifies Cursor`() {
        val page = CdpPage(
            id = "1", type = "page",
            title = "Cursor - voice7",
            url = "file:///Cursor.app/workbench.html",
            webSocketDebuggerUrl = ""
        )
        assertEquals(ElectronAppType.CURSOR, page.appType)
    }

    @Test
    fun `CdpPage appType identifies Codex`() {
        val page = CdpPage(
            id = "1", type = "page",
            title = "Codex Editor",
            url = "file:///app/workbench.html",
            webSocketDebuggerUrl = ""
        )
        assertEquals(ElectronAppType.CODEX, page.appType)
    }

    @Test
    fun `CdpPage appType falls back to VSCODE_LIKE for generic workbench`() {
        val page = CdpPage(
            id = "1", type = "page",
            title = "My Editor",
            url = "file:///something/workbench.html",
            webSocketDebuggerUrl = ""
        )
        assertEquals(ElectronAppType.VSCODE_LIKE, page.appType)
    }

    @Test
    fun `CdpPage appType returns UNKNOWN for non-IDE pages`() {
        val page = CdpPage(
            id = "1", type = "page",
            title = "Google",
            url = "https://google.com",
            webSocketDebuggerUrl = ""
        )
        assertEquals(ElectronAppType.UNKNOWN, page.appType)
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
