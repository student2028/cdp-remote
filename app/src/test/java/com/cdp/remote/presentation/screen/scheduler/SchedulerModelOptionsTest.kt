package com.cdp.remote.presentation.screen.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerModelOptionsTest {
    @Test
    fun `codex exposes codex model options`() {
        val options = schedulerModelOptionsForIde("Codex")

        assertEquals("默认", options.first().label)
        assertTrue(options.any { it.value == "GPT-5.5" })
        assertTrue(options.any { it.value == "Extra High" })
    }

    @Test
    fun `cursor exposes cursor model options`() {
        val options = schedulerModelOptionsForIde("Cursor")

        assertTrue(options.any { it.value == "Composer 2" })
        assertTrue(options.any { it.value == "Sonnet" })
    }

    @Test
    fun `uitty only exposes default because it has no global model switcher`() {
        val options = schedulerModelOptionsForIde("uitty")

        assertEquals(listOf(SchedulerModelOption("默认", "")), options)
    }
}
