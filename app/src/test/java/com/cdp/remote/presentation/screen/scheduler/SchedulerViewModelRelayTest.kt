package com.cdp.remote.presentation.screen.scheduler

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SchedulerViewModelRelayTest {

    @Test
    fun `parseTasksJsonOrThrow rejects malformed payload`() {
        val error = expectThrows {
            SchedulerViewModel.parseTasksJsonOrThrow("not json")
        }
        assertTrue(error.message?.isNotBlank() == true)
    }

    @Test
    fun `parseTasksJsonOrThrow rejects payload without tasks key`() {
        val error = expectThrows {
            SchedulerViewModel.parseTasksJsonOrThrow("""{"hello":"world"}""")
        }
        assertEquals("缺少 tasks 字段", error.message)
    }

    @Test
    fun `requireSuccessOrThrow surfaces relay error message`() {
        val response = JsonParser.parseString("""{"success":false,"error":"任务不存在"}""").asJsonObject
        val error = expectThrows {
            SchedulerViewModel.requireSuccessOrThrow(response, "恢复失败")
        }
        assertEquals("任务不存在", error.message)
    }

    @Test
    fun `extractErrorMessage falls back for malformed body`() {
        assertEquals(
            "HTTP 500",
            SchedulerViewModel.extractErrorMessage("not json", "HTTP 500")
        )
    }

    @Test
    fun `parse scheduler model options response`() {
        val json = """{
            "success": true,
            "port": 9333,
            "appName": "DSME",
            "models": ["Gemini 3.1 Pro (High)", "Claude Sonnet 4.6 (Thinking)"]
        }"""

        assertEquals(
            listOf("Gemini 3.1 Pro (High)", "Claude Sonnet 4.6 (Thinking)"),
            SchedulerViewModel.parseModelOptionsJson(json)
        )
    }

    @Test
    fun `parse scheduler model options returns empty on error response`() {
        val json = """{"success": false, "error": "unsupported"}"""

        assertEquals(emptyList<String>(), SchedulerViewModel.parseModelOptionsJson(json))
    }

    private fun expectThrows(block: () -> Unit): Throwable {
        try {
            block()
            fail("Expected exception")
        } catch (t: Throwable) {
            return t
        }
        error("unreachable")
    }
}
