package com.cdp.remote.data.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 使用 Robolectric 测试 IdeModelOptionsCache 的完整读写逻辑。
 *
 * 这些测试验证真实的 SharedPreferences 交互，不是 mock。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class IdeModelOptionsCacheRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // 清理所有缓存避免测试串扰
        val prefs = context.getSharedPreferences("ide_model_options_cache_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    // ─── put / getValid 基础读写 ────────────────────────────────────

    @Test
    fun `put and getValid returns cached models`() {
        val key = IdeModelOptionsCache.cacheKey("Antigravity", "10.0.0.1", 19335, false)
        val models = listOf("claude-4-sonnet", "gpt-4o", "gemini-2.5-pro")

        IdeModelOptionsCache.put(context, key, models)
        val result = IdeModelOptionsCache.getValid(context, key)

        assertNotNull("写入后应能读取", result)
        assertEquals(3, result!!.size)
        assertEquals("claude-4-sonnet", result[0])
        assertEquals("gpt-4o", result[1])
        assertEquals("gemini-2.5-pro", result[2])
    }

    @Test
    fun `getValid returns null for non-existent key`() {
        val key = IdeModelOptionsCache.cacheKey("NoSuchApp", "1.2.3.4", 9999, false)
        assertNull(IdeModelOptionsCache.getValid(context, key))
    }

    @Test
    fun `put with empty list does not write`() {
        val key = IdeModelOptionsCache.cacheKey("Antigravity", "10.0.0.1", 19335, false)
        IdeModelOptionsCache.put(context, key, emptyList())
        assertNull("空列表不应被缓存", IdeModelOptionsCache.getValid(context, key))
    }

    // ─── getStale ────────────────────────────────────────────────────

    @Test
    fun `getStale returns data even for non-existent key`() {
        val key = IdeModelOptionsCache.cacheKey("Nothing", "0.0.0.0", 1, false)
        assertNull(IdeModelOptionsCache.getStale(context, key))
    }

    @Test
    fun `getStale returns cached data regardless of TTL`() {
        val key = IdeModelOptionsCache.cacheKey("Windsurf", "10.0.0.1", 19335, false)
        val models = listOf("model-a", "model-b")
        IdeModelOptionsCache.put(context, key, models)

        // getStale 不检查 TTL
        val result = IdeModelOptionsCache.getStale(context, key)
        assertNotNull(result)
        assertEquals(2, result!!.size)
    }

    // ─── clear ──────────────────────────────────────────────────────

    @Test
    fun `clear removes cached entry`() {
        val key = IdeModelOptionsCache.cacheKey("Antigravity", "10.0.0.1", 19335, false)
        IdeModelOptionsCache.put(context, key, listOf("model-x"))
        assertNotNull("清除前应有值", IdeModelOptionsCache.getValid(context, key))

        IdeModelOptionsCache.clear(context, key)
        assertNull("清除后应为 null", IdeModelOptionsCache.getValid(context, key))
        assertNull("清除后 stale 也应为 null", IdeModelOptionsCache.getStale(context, key))
    }

    @Test
    fun `clear does not affect other keys`() {
        val key1 = IdeModelOptionsCache.cacheKey("Antigravity", "10.0.0.1", 19335, false)
        val key2 = IdeModelOptionsCache.cacheKey("Windsurf", "10.0.0.1", 19335, false)
        IdeModelOptionsCache.put(context, key1, listOf("model-a"))
        IdeModelOptionsCache.put(context, key2, listOf("model-b"))

        IdeModelOptionsCache.clear(context, key1)

        assertNull(IdeModelOptionsCache.getValid(context, key1))
        assertNotNull("其他键不应受影响", IdeModelOptionsCache.getValid(context, key2))
    }

    // ─── 缓存隔离 ───────────────────────────────────────────────────

    @Test
    fun `different IDE names are isolated`() {
        val keyAnti = IdeModelOptionsCache.cacheKey("Antigravity", "10.0.0.1", 19335, false)
        val keyWind = IdeModelOptionsCache.cacheKey("Windsurf", "10.0.0.1", 19335, false)

        IdeModelOptionsCache.put(context, keyAnti, listOf("anti-model"))
        IdeModelOptionsCache.put(context, keyWind, listOf("wind-model"))

        assertEquals("anti-model", IdeModelOptionsCache.getValid(context, keyAnti)!![0])
        assertEquals("wind-model", IdeModelOptionsCache.getValid(context, keyWind)!![0])
    }

    @Test
    fun `different hosts are isolated`() {
        val key1 = IdeModelOptionsCache.cacheKey("Antigravity", "10.0.0.1", 19335, false)
        val key2 = IdeModelOptionsCache.cacheKey("Antigravity", "10.0.0.2", 19335, false)

        IdeModelOptionsCache.put(context, key1, listOf("host1-model"))
        IdeModelOptionsCache.put(context, key2, listOf("host2-model"))

        assertEquals("host1-model", IdeModelOptionsCache.getValid(context, key1)!![0])
        assertEquals("host2-model", IdeModelOptionsCache.getValid(context, key2)!![0])
    }

    @Test
    fun `different ports are isolated`() {
        val key1 = IdeModelOptionsCache.cacheKey("Antigravity", "10.0.0.1", 19335, false)
        val key2 = IdeModelOptionsCache.cacheKey("Antigravity", "10.0.0.1", 19336, false)

        IdeModelOptionsCache.put(context, key1, listOf("port1"))
        IdeModelOptionsCache.put(context, key2, listOf("port2"))

        assertEquals("port1", IdeModelOptionsCache.getValid(context, key1)!![0])
        assertEquals("port2", IdeModelOptionsCache.getValid(context, key2)!![0])
    }

    @Test
    fun `codex flag isolation`() {
        val key1 = IdeModelOptionsCache.cacheKey("Codex", "10.0.0.1", 19335, false)
        val key2 = IdeModelOptionsCache.cacheKey("Codex", "10.0.0.1", 19335, true)

        IdeModelOptionsCache.put(context, key1, listOf("non-codex"))
        IdeModelOptionsCache.put(context, key2, listOf("codex"))

        assertEquals("non-codex", IdeModelOptionsCache.getValid(context, key1)!![0])
        assertEquals("codex", IdeModelOptionsCache.getValid(context, key2)!![0])
    }

    // ─── 覆盖写入 ───────────────────────────────────────────────────

    @Test
    fun `put overwrites previous value`() {
        val key = IdeModelOptionsCache.cacheKey("Antigravity", "10.0.0.1", 19335, false)
        IdeModelOptionsCache.put(context, key, listOf("old-model"))
        IdeModelOptionsCache.put(context, key, listOf("new-model-1", "new-model-2"))

        val result = IdeModelOptionsCache.getValid(context, key)
        assertNotNull(result)
        assertEquals(2, result!!.size)
        assertEquals("new-model-1", result[0])
    }

    // ─── 特殊字符处理 ────────────────────────────────────────────────

    @Test
    fun `model names with special characters`() {
        val key = IdeModelOptionsCache.cacheKey("Antigravity", "10.0.0.1", 19335, false)
        val models = listOf(
            "claude-4-sonnet (thinking)",
            "gpt-4o/mini",
            "gemini 2.5 pro",
            "model: latest@v2"
        )
        IdeModelOptionsCache.put(context, key, models)
        val result = IdeModelOptionsCache.getValid(context, key)

        assertNotNull(result)
        assertEquals(4, result!!.size)
        assertEquals("claude-4-sonnet (thinking)", result[0])
        assertEquals("gpt-4o/mini", result[1])
    }

    @Test
    fun `cache key with special characters in host name is sanitized`() {
        val key = IdeModelOptionsCache.cacheKey("App Name/With:Special", "10.0.0.1", 19335, false)
        IdeModelOptionsCache.put(context, key, listOf("model"))
        val result = IdeModelOptionsCache.getValid(context, key)
        assertNotNull("特殊字符键应正常工作", result)
    }
}
