package com.cdp.remote.presentation.screen.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkflowStatusParserTest {

    @Test
    fun parsesReviewProgressAndEventLog() {
        val dto = WorkflowViewModel.parseStatusJson(
            """
            {
              "state": "BRAIN_REVIEW",
              "elapsed_ms": 42000,
              "warned": false,
              "cwd": "/tmp/project",
              "brain": {"ide": "Antigravity", "port": 9333},
              "worker": {"ide": "Cursor", "port": 9555},
              "reviewRound": 2,
              "minReviewRounds": 3,
              "lastReviewVerdict": "NEEDS_REWORK",
              "eventLog": [
                {
                  "type": "pipeline",
                  "from": "WORKER_CODE",
                  "to": "BRAIN_REVIEW",
                  "verb": "COMMIT",
                  "hash": "abc1234",
                  "summary": "Implement workflow",
                  "time": 1710000000000
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("BRAIN_REVIEW", dto.state)
        assertEquals(2, dto.reviewRound)
        assertEquals(3, dto.minReviewRounds)
        assertEquals("NEEDS_REWORK", dto.lastReviewVerdict)
        assertEquals(1, dto.eventLog.size)
        assertEquals("COMMIT", dto.eventLog.first().verb)
        assertEquals("Implement workflow", dto.eventLog.first().summary)
        assertEquals("abc1234", dto.eventLog.first().hash)
    }

    @Test
    fun defaultsMissingWorkflowTelemetry() {
        val dto = WorkflowViewModel.parseStatusJson("""{"state":"IDLE"}""")

        assertEquals(0, dto.reviewRound)
        assertEquals(3, dto.minReviewRounds)
        assertNull(dto.lastReviewVerdict)
        assertEquals(emptyList<WorkflowEvent>(), dto.eventLog)
    }

    @Test
    fun doesNotSyncStaleInitialTaskFromIdleStatus() {
        val dto = WorkflowViewModel.parseStatusJson(
            """{"state":"IDLE","initialTask":"old completed task"}"""
        )

        assertEquals("", WorkflowViewModel.mergeInitialTask("", dto))
    }

    @Test
    fun syncsInitialTaskFromRunningStatusWhenLocalTaskIsBlank() {
        val dto = WorkflowViewModel.parseStatusJson(
            """{"state":"BRAIN_PLAN","initialTask":"externally started task"}"""
        )

        assertEquals("externally started task", WorkflowViewModel.mergeInitialTask("", dto))
    }
}
