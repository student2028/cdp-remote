package com.cdp.remote.presentation.screen.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cursor 预置模型清单回归测试。
 *
 * 守住三件事：
 * 1. 预置 key **一律不写死小版本号**（如 `GPT-5.5`、`Sonnet 4.6`），只用品牌/系列级关键词
 *    （`GPT`、`Sonnet`、`Opus`…），由 switchModel 的子串匹配自动对上最新一代。
 *    唯一允许带数字的是 `Composer N`（Composer 同时存在 2.x 与 1.5 两档，必须保留区分号）。
 *    2026-04 的事故正是因为预置写死 `GPT-5.4`，菜单上新 `GPT-5.5` 后切换即失败。
 * 2. 每个预置项都是某条真实菜单条目（[CURSOR_PRESET_CANONICAL_NAMES]）的子串，
 *    与 `CursorCommands.switchModel` 内 `pick()` 的子串匹配语义保持一致。
 * 3. 每个预置项**恰好**命中 1 行，避免一词歧义匹配多个模型。
 */
class CursorPresetModelsTest {

    @Test
    fun `preset keys are version-agnostic - no hard-coded minor versions`() {
        // 只允许 "Composer N" 这种形态（用来区分 Composer 2 / Composer 1.5）；
        // 其他形如 "GPT-5.5"、"Sonnet 4.6"、"Opus 4.7" 的写法都属于小版本号硬编码，
        // Cursor 每次升版本都会导致匹配失败。
        val versionPattern = Regex("""\d+\.\d+""")
        val composerAllowlist = Regex("""^Composer \d+(\.\d+)?${'$'}""")
        for (preset in CURSOR_PRESET_MODELS) {
            if (versionPattern.containsMatchIn(preset)) {
                assertTrue(
                    "预置项「$preset」写死了小版本号；请改成品牌级关键词（如 'GPT' 而不是 'GPT-5.5'），" +
                        "仅 'Composer N' 形态被允许，用于区分 Composer 2 / Composer 1.5。",
                    composerAllowlist.matches(preset)
                )
            }
        }
        // 同时拒绝之前事故现场的具体字面量，防止 revert。
        assertFalse(
            "预置列表不应再出现 2026-04 事故前的 'GPT-5.4' 字面量",
            CURSOR_PRESET_MODELS.any { it.equals("GPT-5.4", ignoreCase = true) }
        )
        assertFalse(
            "GPT 条目应改为通用 'GPT' 子串，由 switchModel 自动命中最新 GPT-x.x",
            CURSOR_PRESET_MODELS.any { it.contains("GPT-", ignoreCase = true) }
        )
    }

    @Test
    fun `each preset key matches a real menu row via case-insensitive substring`() {
        // 与 CursorCommands.switchModel 中 JS pick() 的匹配规则保持一致：
        // lower(row).contains(lower(input)) → 命中。
        for (preset in CURSOR_PRESET_MODELS) {
            val needle = preset.lowercase().trim()
            val match = CURSOR_PRESET_CANONICAL_NAMES.firstOrNull { row ->
                row.lowercase().contains(needle)
            }
            assertNotNull(
                "预置项「$preset」在 Cursor 当前菜单中找不到匹配行，将导致切换失败",
                match
            )
        }
    }

    @Test
    fun `preset keys are unambiguous - each maps to exactly one canonical row`() {
        // 防止把过短关键词放进预置（例如只写 "5"），导致一次匹配多行、行为不确定。
        for (preset in CURSOR_PRESET_MODELS) {
            val needle = preset.lowercase().trim()
            val hits = CURSOR_PRESET_CANONICAL_NAMES.count { row ->
                row.lowercase().contains(needle)
            }
            assertTrue(
                "预置项「$preset」在菜单中匹配到 $hits 行，应当恰好 1 行才能稳定切换",
                hits == 1
            )
        }
    }
}
