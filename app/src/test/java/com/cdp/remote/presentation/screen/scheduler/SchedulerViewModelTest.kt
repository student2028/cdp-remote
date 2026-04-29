package com.cdp.remote.presentation.screen.scheduler

import org.junit.Assert.*
import org.junit.Test

/**
 * SchedulerViewModel 数据解析纯函数测试 — 无网络、无 Robolectric、100% 确定性。
 * 覆盖：任务解析、IDE 解析、边界情况、错误处理。
 */
class SchedulerViewModelTest {

    // ═══════════════════════════════════════════════════════════════
    // 任务解析
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `parse interval task - all fields`() {
        val json = """{"tasks":[{
            "id":"task-1","targetIde":"Windsurf","targetPort":9444,
            "prompt":"设计表达式 回测 检查 提交三个 regular alpha",
            "scheduleType":"INTERVAL","intervalMinutes":30,
            "cronExpression":"","isRunning":true,"paused":false,"executionCount":15
        }]}"""
        val tasks = SchedulerViewModel.parseTasksJson(json)
        assertEquals(1, tasks.size)
        val t = tasks[0]
        assertEquals("task-1", t.id)
        assertEquals("Windsurf", t.targetIde)
        assertEquals(9444, t.targetPort)
        assertEquals("设计表达式 回测 检查 提交三个 regular alpha", t.prompt)
        assertEquals("每 30 分钟", t.ruleLabel)
        assertEquals(30, t.intervalMinutes)
        assertEquals("", t.cronExpression)
        assertEquals(ScheduleType.INTERVAL, t.scheduleType)
        assertTrue(t.isRunning)
        assertFalse(t.paused)
        assertEquals(15, t.executionCount)
    }

    @Test
    fun `parse cron task`() {
        val json = """{"tasks":[{
            "id":"task-c","targetIde":"Codex","targetPort":9666,
            "prompt":"run test","scheduleType":"CRON","intervalMinutes":5,
            "cronExpression":"*/15 * * * *","isRunning":true,"paused":false,"executionCount":3
        }]}"""
        val tasks = SchedulerViewModel.parseTasksJson(json)
        assertEquals(1, tasks.size)
        val t = tasks[0]
        assertEquals(ScheduleType.CRON, t.scheduleType)
        assertEquals("cron: */15 * * * *", t.ruleLabel)
        assertEquals("*/15 * * * *", t.cronExpression)
        assertEquals(3, t.executionCount)
    }

    @Test
    fun `parse paused task`() {
        val json = """{"tasks":[{
            "id":"task-p","targetIde":"Windsurf","targetPort":9444,
            "prompt":"hello","scheduleType":"INTERVAL","intervalMinutes":10,
            "isRunning":false,"paused":true,"executionCount":5
        }]}"""
        val tasks = SchedulerViewModel.parseTasksJson(json)
        assertTrue(tasks[0].paused)
        assertFalse(tasks[0].isRunning)
        assertEquals(5, tasks[0].executionCount)
        assertEquals(10, tasks[0].intervalMinutes)
    }

    @Test
    fun `parse multiple tasks`() {
        val json = """{"tasks":[
            {"id":"t1","targetIde":"Windsurf","targetPort":9444,"prompt":"a","scheduleType":"INTERVAL","intervalMinutes":30,"isRunning":true,"paused":false,"executionCount":10},
            {"id":"t2","targetIde":"Cursor","targetPort":9555,"prompt":"b","scheduleType":"INTERVAL","intervalMinutes":31,"isRunning":false,"paused":true,"executionCount":5},
            {"id":"t3","targetIde":"Codex","targetPort":9666,"prompt":"c","scheduleType":"CRON","cronExpression":"0 */2 * * *","intervalMinutes":5,"isRunning":true,"paused":false,"executionCount":0}
        ]}"""
        val tasks = SchedulerViewModel.parseTasksJson(json)
        assertEquals(3, tasks.size)
        assertEquals("t1", tasks[0].id)
        assertEquals("t2", tasks[1].id)
        assertEquals("t3", tasks[2].id)
        assertEquals("Windsurf", tasks[0].targetIde)
        assertEquals("Cursor", tasks[1].targetIde)
        assertEquals("Codex", tasks[2].targetIde)
        assertFalse(tasks[0].paused)
        assertTrue(tasks[1].paused)
        assertEquals(ScheduleType.CRON, tasks[2].scheduleType)
    }

    @Test
    fun `parse empty tasks array`() {
        val json = """{"tasks":[]}"""
        assertEquals(0, SchedulerViewModel.parseTasksJson(json).size)
    }

    @Test
    fun `parse missing tasks key`() {
        val json = """{"other":"value"}"""
        assertEquals(0, SchedulerViewModel.parseTasksJson(json).size)
    }

    @Test
    fun `parse invalid json`() {
        assertEquals(0, SchedulerViewModel.parseTasksJson("not json").size)
    }

    @Test
    fun `parse empty string`() {
        assertEquals(0, SchedulerViewModel.parseTasksJson("").size)
    }

    @Test
    fun `parse task with missing optional fields`() {
        val json = """{"tasks":[{
            "id":"t-minimal","targetIde":"Windsurf","prompt":"test"
        }]}"""
        val tasks = SchedulerViewModel.parseTasksJson(json)
        assertEquals(1, tasks.size)
        val t = tasks[0]
        assertEquals("t-minimal", t.id)
        assertEquals(0, t.targetPort)
        assertEquals(5, t.intervalMinutes) // 默认值
        assertEquals(ScheduleType.INTERVAL, t.scheduleType)
        assertFalse(t.isRunning)
        assertFalse(t.paused)
        assertEquals(0, t.executionCount)
        assertEquals("每 5 分钟", t.ruleLabel)
    }

    @Test
    fun `parse task paused field defaults to false`() {
        val json = """{"tasks":[{
            "id":"t1","targetIde":"W","prompt":"x","scheduleType":"INTERVAL",
            "intervalMinutes":10,"isRunning":true,"executionCount":3
        }]}"""
        val tasks = SchedulerViewModel.parseTasksJson(json)
        // 没有 paused 字段时默认 false
        assertFalse(tasks[0].paused)
    }

    // ═══════════════════════════════════════════════════════════════
    // IDE 解析
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `parse 3 IDEs`() {
        val json = """{"targets":[
            {"cdpPort":9333,"appName":"Antigravity","appEmoji":"🚀","pages":[{"type":"page","url":"file:///workbench.html","title":"Antigravity","webSocketDebuggerUrl":"ws://t/1"}]},
            {"cdpPort":9444,"appName":"Windsurf","appEmoji":"🏄","pages":[{"type":"page","url":"file:///workbench.html","title":"Windsurf","webSocketDebuggerUrl":"ws://t/2"}]},
            {"cdpPort":9555,"appName":"Cursor","appEmoji":"🖱️","pages":[{"type":"page","url":"file:///workbench.html","title":"Cursor","webSocketDebuggerUrl":"ws://t/3"}]}
        ]}"""
        val ides = SchedulerViewModel.parseIdesJson(json)
        assertEquals(3, ides.size)
        assertEquals("Antigravity", ides[0].name)
        assertEquals(9333, ides[0].port)
        assertEquals("🚀", ides[0].emoji)
        assertEquals("Windsurf", ides[1].name)
        assertEquals("Cursor", ides[2].name)
    }

    @Test
    fun `filter non-workbench pages`() {
        val json = """{"targets":[
            {"cdpPort":9333,"appName":"Antigravity","appEmoji":"🚀","pages":[
                {"type":"page","url":"file:///workbench.html","title":"Antigravity","webSocketDebuggerUrl":"ws://t/1"},
                {"type":"other","url":"devtools://devtools","title":"DevTools","webSocketDebuggerUrl":"ws://t/2"}
            ]}
        ]}"""
        val ides = SchedulerViewModel.parseIdesJson(json)
        assertEquals(1, ides.size) // DevTools 被过滤
        assertEquals("Antigravity", ides[0].title)
    }

    @Test
    fun `filter Launchpad pages`() {
        val json = """{"targets":[
            {"cdpPort":9444,"appName":"Windsurf","appEmoji":"🏄","pages":[
                {"type":"page","url":"file:///launchpad.html","title":"Windsurf Launchpad","webSocketDebuggerUrl":"ws://t/3"},
                {"type":"page","url":"file:///workbench.html","title":"Windsurf","webSocketDebuggerUrl":"ws://t/4"}
            ]}
        ]}"""
        val ides = SchedulerViewModel.parseIdesJson(json)
        assertEquals(1, ides.size) // Launchpad 被过滤
        assertEquals("Windsurf", ides[0].title)
    }

    @Test
    fun `filter non-page type`() {
        val json = """{"targets":[
            {"cdpPort":9333,"appName":"Test","appEmoji":"","pages":[
                {"type":"iframe","url":"file:///workbench.html","title":"Test","webSocketDebuggerUrl":"ws://t/1"}
            ]}
        ]}"""
        val ides = SchedulerViewModel.parseIdesJson(json)
        assertTrue(ides.isEmpty()) // iframe type 被过滤
    }

    @Test
    fun `IDE with multiple valid workbench pages is shown once per port`() {
        val json = """{"targets":[
            {"cdpPort":9444,"appName":"Windsurf","appEmoji":"🏄","pages":[
                {"type":"page","url":"file:///workbench-jetski-agent.html","title":"Settings","webSocketDebuggerUrl":"ws://t/1"},
                {"type":"page","url":"file:///workbench.html","title":"Windsurf Main","webSocketDebuggerUrl":"ws://t/2"}
            ]}
        ]}"""
        val ides = SchedulerViewModel.parseIdesJson(json)
        assertEquals(1, ides.size)
        assertEquals("Windsurf Main", ides[0].title)
    }

    @Test
    fun `parse empty targets`() {
        assertEquals(0, SchedulerViewModel.parseIdesJson("""{"targets":[]}""").size)
    }

    @Test
    fun `parse missing targets key`() {
        assertEquals(0, SchedulerViewModel.parseIdesJson("""{"other":1}""").size)
    }

    @Test
    fun `parse invalid IDE json`() {
        assertEquals(0, SchedulerViewModel.parseIdesJson("bad json").size)
    }

    @Test
    fun `IDE missing appName skips`() {
        val json = """{"targets":[
            {"cdpPort":9333,"appEmoji":"🚀","pages":[{"type":"page","url":"file:///workbench.html","title":"Test","webSocketDebuggerUrl":"ws://t/1"}]}
        ]}"""
        assertTrue(SchedulerViewModel.parseIdesJson(json).isEmpty())
    }

    @Test
    fun `IDE missing cdpPort skips`() {
        val json = """{"targets":[
            {"appName":"Test","appEmoji":"🚀","pages":[{"type":"page","url":"file:///workbench.html","title":"Test","webSocketDebuggerUrl":"ws://t/1"}]}
        ]}"""
        assertTrue(SchedulerViewModel.parseIdesJson(json).isEmpty())
    }

    @Test
    fun `IDE missing pages skips`() {
        val json = """{"targets":[
            {"cdpPort":9333,"appName":"Test","appEmoji":"🚀"}
        ]}"""
        assertTrue(SchedulerViewModel.parseIdesJson(json).isEmpty())
    }

    @Test
    fun `IDE emoji defaults to empty`() {
        val json = """{"targets":[
            {"cdpPort":9333,"appName":"Test","pages":[{"type":"page","url":"file:///workbench.html","title":"Test","webSocketDebuggerUrl":"ws://t/1"}]}
        ]}"""
        val ides = SchedulerViewModel.parseIdesJson(json)
        assertEquals(1, ides.size)
        assertEquals("", ides[0].emoji)
    }

    // ═══════════════════════════════════════════════════════════════
    // UI 状态逻辑（纯函数式验证）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `TaskDraft default values`() {
        val d = TaskDraft()
        assertEquals("", d.targetIde)
        assertEquals(0, d.targetPort)
        assertEquals("", d.prompt)
        assertEquals(ScheduleType.INTERVAL, d.scheduleType)
        assertEquals(5, d.intervalMinutes)
        assertEquals("*/30 * * * *", d.cronExpression)
    }

    @Test
    fun `ScheduledTaskUi copy for pause`() {
        val task = ScheduledTaskUi(
            id = "t1", targetIde = "W", prompt = "x", ruleLabel = "每 5 分钟",
            isRunning = true, paused = false, executionCount = 3
        )
        val paused = task.copy(paused = true, isRunning = false)
        assertTrue(paused.paused)
        assertFalse(paused.isRunning)
        assertEquals(3, paused.executionCount) // 不变
    }

    @Test
    fun `ScheduledTaskUi copy for resume`() {
        val task = ScheduledTaskUi(
            id = "t1", targetIde = "W", prompt = "x", ruleLabel = "每 5 分钟",
            isRunning = false, paused = true, executionCount = 10
        )
        val resumed = task.copy(paused = false, isRunning = true)
        assertFalse(resumed.paused)
        assertTrue(resumed.isRunning)
    }

    @Test
    fun `SchedulerUiState task list filter for cancel`() {
        val tasks = listOf(
            ScheduledTaskUi(id = "t1", targetIde = "A", prompt = "a", ruleLabel = "x", isRunning = true),
            ScheduledTaskUi(id = "t2", targetIde = "B", prompt = "b", ruleLabel = "y", isRunning = true),
            ScheduledTaskUi(id = "t3", targetIde = "C", prompt = "c", ruleLabel = "z", isRunning = false)
        )
        val afterCancel = tasks.filter { it.id != "t2" }
        assertEquals(2, afterCancel.size)
        assertEquals("t1", afterCancel[0].id)
        assertEquals("t3", afterCancel[1].id)
    }

    @Test
    fun `SchedulerUiState task list map for pause`() {
        val tasks = listOf(
            ScheduledTaskUi(id = "t1", targetIde = "A", prompt = "a", ruleLabel = "x", isRunning = true),
            ScheduledTaskUi(id = "t2", targetIde = "B", prompt = "b", ruleLabel = "y", isRunning = true)
        )
        val afterPause = tasks.map {
            if (it.id == "t1") it.copy(paused = true, isRunning = false) else it
        }
        assertTrue(afterPause[0].paused)
        assertFalse(afterPause[0].isRunning)
        assertFalse(afterPause[1].paused) // t2 不受影响
        assertTrue(afterPause[1].isRunning)
    }
}
