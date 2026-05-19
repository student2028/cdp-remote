package com.cdp.remote.presentation.screen.scheduler

data class SchedulerModelOption(
    val label: String,
    val value: String
)

fun schedulerModelOptionsForIde(
    ideName: String,
    liveModels: List<String> = emptyList()
): List<SchedulerModelOption> {
    val key = ideName.substringBefore(":").trim().lowercase()
    val presetModels = when {
        key.contains("cursor") -> listOf(
            "Auto",
            "Premium",
            "Composer 2",
            "Composer 1.5",
            "GPT",
            "Codex",
            "Sonnet",
            "Opus"
        )
        key.contains("windsurf") -> listOf(
            "Claude Opus 4.7",
            "Claude Opus 4.6",
            "Claude Sonnet 4.6",
            "GPT-5.3-Codex",
            "GPT-5.4",
            "Kimi K2.6",
            "SWE-1.6",
            "Gemini 3.1 Pro",
            "Adaptive"
        )
        key.contains("codex") -> listOf(
            "Extra High",
            "High",
            "Medium",
            "Low",
            "Fast",
            "Standard",
            "GPT-5.5",
            "GPT-5.4"
        )
        key.contains("antigravity") || key.contains("dsme") || key.contains("deepseek") -> listOf(
            "Gemini 3.1 Pro",
            "Gemini 3 Flash",
            "Claude Sonnet 4.6",
            "Claude Opus 4.7",
            "GPT-5.4",
            "Kimi K2.6",
            "MiniMax"
        )
        key.contains("claude") -> listOf(
            "sonnet",
            "opus",
            "haiku"
        )
        else -> emptyList()
    }
    val models = liveModels
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .takeIf { it.isNotEmpty() }
        ?: presetModels

    return listOf(SchedulerModelOption("默认", "")) +
        models.distinct().map { SchedulerModelOption(it, it) }
}
