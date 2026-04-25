package com.cdp.remote.domain.scheduler

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class IdeSchedulerTest {
    @Test
    fun `rescheduling same task id cancels previous job`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val executions = AtomicInteger(0)
        val scheduler = IdeScheduler(
            dispatcher = dispatcher,
            parentScope = backgroundScope,
            taskExecutor = { executions.incrementAndGet() }
        )

        scheduler.schedule(
            TaskConfig(
                id = "task-1",
                targetIde = "Windsurf",
                scheduleRule = ScheduleRule.Interval(1000.milliseconds),
                prompt = "prompt"
            )
        )

        advanceTimeBy(1001)
        advanceUntilIdle()
        assertEquals(1, executions.get())

        scheduler.schedule(
            TaskConfig(
                id = "task-1",
                targetIde = "Windsurf",
                scheduleRule = ScheduleRule.Interval(2000.milliseconds),
                prompt = "prompt"
            )
        )

        advanceTimeBy(1001)
        advanceUntilIdle()
        assertEquals(1, executions.get())

        advanceTimeBy(1000)
        advanceUntilIdle()
        assertEquals(2, executions.get())

        scheduler.close()
    }

    @Test
    fun `close cancels tasks and prevents new schedules`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val executions = AtomicInteger(0)
        val scheduler = IdeScheduler(
            dispatcher = dispatcher,
            parentScope = backgroundScope,
            taskExecutor = { executions.incrementAndGet() }
        )

        scheduler.schedule(
            TaskConfig(
                id = "task-1",
                targetIde = "Codex",
                scheduleRule = ScheduleRule.Interval(1000.milliseconds),
                prompt = "prompt"
            )
        )

        scheduler.close()
        advanceTimeBy(5000)
        advanceUntilIdle()
        assertEquals(0, executions.get())

        assertThrows(IllegalStateException::class.java) {
            scheduler.schedule(
                TaskConfig(
                    id = "task-2",
                    targetIde = "Codex",
                    scheduleRule = ScheduleRule.Interval(1000.milliseconds),
                    prompt = "prompt"
                )
            )
        }
    }

    @Test
    fun `cron schedule is accepted and runs`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val executed = mutableListOf<String>()
        val scheduler = IdeScheduler(
            dispatcher = dispatcher,
            parentScope = backgroundScope,
            taskExecutor = { executed.add(it.id) }
        )

        // Should NOT throw — cron is now supported
        scheduler.schedule(
            TaskConfig(
                id = "task-cron",
                targetIde = "Antigravity",
                scheduleRule = ScheduleRule.Cron("*/5 * * * *"),
                prompt = "cron prompt"
            )
        )

        scheduler.close()
    }
}
