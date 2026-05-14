package com.cdp.remote.data.cdp

/** uitty「新建 Tab」向导里可选的预设 CLI（`cd workspace && …`） */
enum class UittyCliLaunchPreset(val displayLabel: String, val shellCommand: String, val emoji: String) {
    CLAUDE_CODE(
        "Claude Code",
        "claude --permission-mode bypassPermissions",
        "💜"
    ),
    OPEN_CODE("OpenCode", "opencode", "🧠"),
    AIDER("Aider", "aider", "🔧")
}
