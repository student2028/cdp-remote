package com.cdp.remote.data

import com.cdp.remote.data.cdp.UittyCliLaunchPreset

/**
 * 记录一次 uitty「新建 Tab」选择，用于下次一键复用（目录 + 预设或自定义命令）。
 */
data class UittyNewTabRecent(
    val path: String,
    /** 非 null 表示三选一预设；与 [customCommand] 互斥 */
    val presetOrdinal: Int? = null,
    val customCommand: String = ""
) {
    val isPreset: Boolean get() = presetOrdinal != null

    fun preset(): UittyCliLaunchPreset? =
        presetOrdinal?.let { ord -> UittyCliLaunchPreset.entries.getOrNull(ord) }

    /** 主标题一行（与聊天区提示风格接近） */
    fun primaryLabel(): String {
        val p = preset()
        val base = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
        return when {
            p != null -> "${p.emoji} ${p.displayLabel} · $base"
            customCommand.isNotBlank() -> "⚙️ 自定义 · $base"
            else -> base
        }
    }

    fun secondaryLabel(): String {
        if (customCommand.isNotBlank() && preset() == null) {
            val c = customCommand.trim()
            return if (c.length > 42) c.take(40) + "…" else c
        }
        return path
    }
}
