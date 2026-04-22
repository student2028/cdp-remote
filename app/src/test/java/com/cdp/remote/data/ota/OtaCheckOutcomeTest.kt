package com.cdp.remote.data.ota

import org.junit.Assert.*
import org.junit.Test

/**
 * OtaCheckOutcome 密封类的构造与模式匹配测试。
 *
 * 只保留 OtaIntegrationTest 不覆盖的部分：
 * sealed class 的类型安全模式匹配和数据携带。
 */
class OtaCheckOutcomeTest {

    @Test
    fun `UpdateAvailable carries version info and relay URL`() {
        val info = OtaUpdateManager.VersionInfo(20, "2.0", "fix bug")
        val outcome = OtaCheckOutcome.UpdateAvailable(info, "http://10.0.0.1:19336")
        assertTrue(outcome is OtaCheckOutcome.UpdateAvailable)
        assertEquals(20, outcome.info.versionCode)
        assertEquals("http://10.0.0.1:19336", outcome.relayBaseUrl)
        assertEquals("fix bug", outcome.info.updateMessage)
    }

    @Test
    fun `UpToDate carries remote info`() {
        val outcome = OtaCheckOutcome.UpToDate(
            OtaUpdateManager.VersionInfo(15, "1.5", ""), "http://relay"
        )
        assertEquals(15, outcome.remote.versionCode)
    }

    @Test
    fun `Failed carries detail message`() {
        val outcome = OtaCheckOutcome.Failed("全部中继都无法返回 /version")
        assertTrue(outcome.detail.contains("中继"))
    }

    @Test
    fun `when expression is exhaustive on all outcomes`() {
        val outcomes = listOf<OtaCheckOutcome>(
            OtaCheckOutcome.UpdateAvailable(OtaUpdateManager.VersionInfo(20, "2.0", ""), "http://a"),
            OtaCheckOutcome.UpToDate(OtaUpdateManager.VersionInfo(15, "1.5", ""), "http://b"),
            OtaCheckOutcome.Failed("err")
        )
        for (outcome in outcomes) {
            val label = when (outcome) {
                is OtaCheckOutcome.UpdateAvailable -> "update"
                is OtaCheckOutcome.UpToDate -> "current"
                is OtaCheckOutcome.Failed -> "failed"
            }
            assertTrue(label.isNotEmpty())
        }
    }
}
