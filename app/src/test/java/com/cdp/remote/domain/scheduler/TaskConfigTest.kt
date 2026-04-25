package com.cdp.remote.domain.scheduler

import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class TaskConfigTest {
    @Test
    fun `interval schedule must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            ScheduleRule.Interval(0.milliseconds)
        }
    }

    @Test
    fun `task config rejects blank required fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            TaskConfig(
                id = " ",
                targetIde = "Windsurf",
                scheduleRule = ScheduleRule.Interval(1000.milliseconds),
                prompt = "prompt"
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            TaskConfig(
                id = "task-1",
                targetIde = " ",
                scheduleRule = ScheduleRule.Interval(1000.milliseconds),
                prompt = "prompt"
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            TaskConfig(
                id = "task-1",
                targetIde = "Windsurf",
                scheduleRule = ScheduleRule.Interval(1000.milliseconds),
                prompt = " "
            )
        }
    }
}
