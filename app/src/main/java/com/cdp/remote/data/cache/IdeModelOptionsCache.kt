package com.cdp.remote.data.cache

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * IDE 模型下拉枚举结果缓存，降低 CDP `listModelOptions` 调用频率。
 *
 * **适用范围**：反重力（Antigravity）、Windsurf、Codex 等非 Cursor 连接；与桌面 **appName + 主机 IP + 中继/CDP 端口** 分桶，
 * 桌面端模型列表有更新时，**最长 24 小时内**会在下次打开「切换模型」时重新拉取并更新缓存。
 *
 * Cursor 不走枚举，不使用此缓存。
 */
object IdeModelOptionsCache {

    private const val PREFS = "ide_model_options_cache_v1"
    private const val TTL_MS = 24L * 60L * 60L * 1000L

    fun cacheKey(appName: String, hostIp: String, hostPort: Int, isCodex: Boolean): String =
        "${appName.trim()}_${hostIp.trim()}_${hostPort}_codex=$isCodex"

    /** 未过期则返回列表，否则 null */
    fun getValid(context: Context, key: String): List<String>? =
        read(context, key, respectTtl = true)?.takeIf { it.isNotEmpty() }

    /** 任意缓存（含过期），用于拉取失败时兜底展示 */
    fun getStale(context: Context, key: String): List<String>? =
        read(context, key, respectTtl = false)?.takeIf { it.isNotEmpty() }

    fun put(context: Context, key: String, models: List<String>) {
        if (models.isEmpty()) return
        val ctx = context.applicationContext
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val o = JSONObject()
        o.put("t", System.currentTimeMillis())
        o.put("items", JSONArray(models))
        sp.edit().putString(sanitizeKey(key), o.toString()).apply()
    }

    fun clear(context: Context, key: String) {
        val ctx = context.applicationContext
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(sanitizeKey(key))
            .apply()
    }

    private fun read(context: Context, key: String, respectTtl: Boolean): List<String>? {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(sanitizeKey(key), null) ?: return null
        return try {
            val o = JSONObject(raw)
            val t = o.optLong("t", 0L)
            if (respectTtl && (System.currentTimeMillis() - t > TTL_MS)) return null
            val arr = o.optJSONArray("items") ?: return null
            List(arr.length()) { i -> arr.getString(i) }
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeKey(key: String): String =
        key.replace(Regex("[^a-zA-Z0-9_.|=@]"), "_").take(200)
}
