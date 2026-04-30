package com.cdp.remote.presentation.screen.workflow

import com.cdp.remote.presentation.screen.scheduler.IdeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowIdeDefaultsTest {

    @Test
    fun includesDefaultLaunchableIdesWhenNoInstancesAreOnline() {
        val ides = WorkflowViewModel.mergeWorkflowDefaultIdes(emptyList())

        assertEquals(
            listOf(
                "Antigravity" to 9333,
                "Cursor" to 9555,
                "Windsurf" to 9444,
                "Codex" to 9666,
            ),
            ides.map { it.name to it.port }
        )
    }

    @Test
    fun onlineInstanceOverridesMatchingDefaultPort() {
        val ides = WorkflowViewModel.mergeWorkflowDefaultIdes(
            listOf(IdeInfo("Antigravity", 9333, "online", workspace = "/tmp/project"))
        )

        val antigravity = ides.first { it.name == "Antigravity" && it.port == 9333 }
        assertEquals("online", antigravity.title)
        assertEquals("/tmp/project", antigravity.workspace)
    }

    @Test
    fun keepsAdditionalOnlineInstances() {
        val ides = WorkflowViewModel.mergeWorkflowDefaultIdes(
            listOf(IdeInfo("Antigravity", 9334, "online"))
        )

        assertTrue(ides.any { it.name == "Antigravity" && it.port == 9333 })
        assertTrue(ides.any { it.name == "Antigravity" && it.port == 9334 })
    }
}
