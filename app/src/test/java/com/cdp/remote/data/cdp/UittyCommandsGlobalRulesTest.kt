package com.cdp.remote.data.cdp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局规则：注入脚本须兼容仅有 [window.uitty]（preload）而无 [window.uittyAPI.readGlobalRules] 的旧页面。
 */
class UittyCommandsGlobalRulesTest {

    @Test
    fun `readGlobalRules passes awaitPromise and embeds uittyAPI then window uitty fallback`() = runBlocking {
        val fake = FakeCdpClient()
        fake.evaluateHandler = { expr ->
            assertTrue("应优先 uittyAPI 再回退 uitty", expr.contains("window.uittyAPI"))
            assertTrue("应含 window.uitty 回退", expr.contains("window.uitty"))
            assertTrue("应使用 readFn 统一调用", expr.contains("readFn"))
            CdpResult.Success("""{"ok":true,"kind":"claude","content":"# rule\n","missing":false}""")
        }

        val cmd = UittyCommands(fake)
        val r = cmd.readGlobalRules("claude")
        assertTrue("readGlobalRules 须 awaitPromise", fake.evaluateAwaitPromiseFlags.single())
        assertEquals(CdpResult.Success("# rule\n"), r)
    }

    @Test
    fun `writeGlobalRules embeds uitty fallback`() = runBlocking {
        val fake = FakeCdpClient()
        fake.evaluateHandler = { expr ->
            assertTrue(expr.contains("window.uittyAPI"))
            assertTrue(expr.contains("window.uitty"))
            assertTrue(expr.contains("writeFn"))
            CdpResult.Success("""{"ok":true,"kind":"claude","path":"/Users/x/.claude/CLAUDE.md"}""")
        }
        val cmd = UittyCommands(fake)
        val r = cmd.writeGlobalRules("claude", "x")
        assertEquals(CdpResult.Success(Unit), r)
    }
}
