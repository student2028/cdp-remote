package com.cdp.remote.domain.scheduler

import kotlin.time.Duration

data class TaskConfig(
    val id: String,
    val targetIde: String, // e.g., "Windsurf", "Codex", "Antigravity"
    val scheduleRule: ScheduleRule, // e.g., Interval(60000 milliseconds) or Cron("*/5 * * * *")
    val prompt: String
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(targetIde.isNotBlank()) { "targetIde must not be blank" }
        require(prompt.isNotBlank()) { "prompt must not be blank" }
    }
}

sealed interface ScheduleRule {
    data class Interval(val interval: Duration) : ScheduleRule {
        init {
            require(interval.isPositive()) { "interval must be positive" }
        }
    }

    data class Cron(val expression: String) : ScheduleRule {
        init {
            require(expression.isNotBlank()) { "expression must not be blank" }
        }
    }
}
