package com.cdp.remote.domain.scheduler

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.EmptyCoroutineContext

class IdeScheduler(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    parentScope: CoroutineScope? = null,
    private val taskExecutor: suspend (TaskConfig) -> Unit = { config ->
        Log.i(TAG, "Triggering IDE: [${config.targetIde}] with Prompt: [${config.prompt}]")
    }
) : Closeable {
    private val schedulerJob = SupervisorJob(parentScope?.coroutineContext?.get(Job))
    private val scope = CoroutineScope(
        (parentScope?.coroutineContext ?: EmptyCoroutineContext) +
            dispatcher +
            CoroutineName("IdeScheduler") +
            schedulerJob
    )
    private val scheduledTasks = ConcurrentHashMap<String, Job>()
    @Volatile
    private var closed = false

    @Synchronized
    fun schedule(config: TaskConfig) {
        check(!closed) { "IdeScheduler is already closed" }

        val scheduleDescription = when (val rule = config.scheduleRule) {
            is ScheduleRule.Interval -> scope.launch(CoroutineName("IdeScheduler-${config.id}")) {
                runIntervalTask(config, rule)
            } to "every ${rule.interval}"
            is ScheduleRule.Cron -> scope.launch(CoroutineName("IdeScheduler-cron-${config.id}")) {
                runCronTask(config, rule)
            } to "cron ${rule.expression}"
        }

        val (job, description) = scheduleDescription
        val previousJob = scheduledTasks.put(config.id, job)
        previousJob?.cancel()
        job.invokeOnCompletion {
            scheduledTasks.remove(config.id, job)
        }

        Log.d(TAG, "Task ${config.id} scheduled for IDE ${config.targetIde} $description")
    }

    @Synchronized
    fun cancel(taskId: String) {
        val job = scheduledTasks.remove(taskId) ?: return
        job.cancel()
        Log.d(TAG, "Task $taskId cancelled")
    }

    @Synchronized
    fun cancelAll() {
        val jobs = scheduledTasks.values.toList()
        scheduledTasks.clear()
        jobs.forEach { it.cancel() }
        Log.d(TAG, "All tasks cancelled")
    }

    @Synchronized
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        cancelAll()
        schedulerJob.cancel()
    }

    private suspend fun runIntervalTask(config: TaskConfig, rule: ScheduleRule.Interval) {
        while (coroutineContext.isActive) {
            delay(rule.interval)
            executeTaskSafely(config)
        }
    }

    private suspend fun runCronTask(config: TaskConfig, rule: ScheduleRule.Cron) {
        val parsed = parseCron(rule.expression)
            ?: throw IllegalArgumentException("Invalid cron: ${rule.expression}")
        while (coroutineContext.isActive) {
            val nowMs = System.currentTimeMillis()
            val nextMs = nextFireTime(parsed, nowMs)
            val waitMs = (nextMs - nowMs).coerceAtLeast(1000L)
            Log.d(TAG, "Cron ${config.id}: next fire in ${waitMs / 1000}s")
            delay(waitMs)
            executeTaskSafely(config)
            // 避免同一分钟连续触发
            delay(60_000L)
        }
    }

    private suspend fun executeTaskSafely(config: TaskConfig) {
        try {
            taskExecutor(config)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            Log.e(TAG, "Failed to execute task ${config.id}", throwable)
        }
    }

    // ─── 简易 Cron 解析 ────────────────────────────────────────────
    // 支持 5 字段：minute hour day-of-month month day-of-week
    // 支持 * 和 */N 语法

    internal data class CronSpec(
        val minutes: Set<Int>,   // 0-59
        val hours: Set<Int>,     // 0-23
        val daysOfMonth: Set<Int>, // 1-31
        val months: Set<Int>,    // 1-12
        val daysOfWeek: Set<Int> // 1-7 (Calendar: 1=SUN)
    )

    internal fun parseCron(expr: String): CronSpec? {
        val parts = expr.trim().split("\\s+".toRegex())
        if (parts.size != 5) return null
        return try {
            CronSpec(
                minutes = parseField(parts[0], 0, 59),
                hours = parseField(parts[1], 0, 23),
                daysOfMonth = parseField(parts[2], 1, 31),
                months = parseField(parts[3], 1, 12),
                daysOfWeek = parseField(parts[4], 0, 6).map { if (it == 0) 1 else it + 1 }.toSet()
                // cron 0=SUN → Calendar 1=SUN; cron 1=MON → Calendar 2=MON
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseField(field: String, min: Int, max: Int): Set<Int> {
        if (field == "*") return (min..max).toSet()
        if (field.startsWith("*/")) {
            val step = field.removePrefix("*/").toInt()
            if (step <= 0) throw IllegalArgumentException("step <= 0")
            return (min..max step step).toSet()
        }
        // 单值或逗号分隔
        return field.split(",").flatMap { part ->
            if (part.contains("-")) {
                val (a, b) = part.split("-").map { it.toInt() }
                (a..b).toList()
            } else {
                listOf(part.toInt())
            }
        }.filter { it in min..max }.toSet()
    }

    internal fun nextFireTime(spec: CronSpec, fromMs: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = fromMs }
        // 向前推最多 2 年寻找下一个匹配点
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.MINUTE, 1)
        for (i in 0 until 525600) { // 365 * 24 * 60
            val month = cal.get(Calendar.MONTH) + 1
            val dom = cal.get(Calendar.DAY_OF_MONTH)
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val minute = cal.get(Calendar.MINUTE)
            if (month in spec.months &&
                dom in spec.daysOfMonth &&
                dow in spec.daysOfWeek &&
                hour in spec.hours &&
                minute in spec.minutes
            ) {
                return cal.timeInMillis
            }
            cal.add(Calendar.MINUTE, 1)
        }
        // 兜底：1 小时后
        return fromMs + 3600_000L
    }

    private companion object {
        const val TAG = "IdeScheduler"
    }
}
