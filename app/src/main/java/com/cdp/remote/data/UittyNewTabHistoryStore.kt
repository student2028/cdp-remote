package com.cdp.remote.data

import android.content.Context
import com.cdp.remote.data.cdp.UittyCliLaunchPreset
import org.json.JSONArray
import org.json.JSONObject

/**
 * 持久化 uitty「新建 Tab」最近几次选择（目录 + CLI），应用重启后仍可用。
 */
object UittyNewTabHistoryStore {

    private const val PREFS = "uitty_new_tab_recents_v1"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 10

    fun list(context: Context): List<UittyNewTabRecent> {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val path = o.optString("path", "")
                    if (path.isBlank()) continue
                    val ord = if (o.has("presetOrdinal") && !o.isNull("presetOrdinal"))
                        o.optInt("presetOrdinal", -1) else -1
                    val cmd = o.optString("custom", "")
                    add(
                        UittyNewTabRecent(
                            path = path,
                            presetOrdinal = if (ord >= 0) ord else null,
                            customCommand = cmd
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun recordPreset(context: Context, path: String, preset: UittyCliLaunchPreset) {
        val p = path.trim()
        if (p.isEmpty()) return
        val ord = preset.ordinal
        mutate(context) { items ->
            val next = items.filterNot { it.path == p && it.presetOrdinal == ord }.toMutableList()
            next.add(0, UittyNewTabRecent(path = p, presetOrdinal = ord, customCommand = ""))
            next.take(MAX_ITEMS)
        }
    }

    fun recordCustom(context: Context, path: String, command: String) {
        val p = path.trim()
        val c = command.trim()
        if (p.isEmpty() || c.isEmpty()) return
        mutate(context) { items ->
            val next = items.filterNot {
                it.path == p && it.presetOrdinal == null && it.customCommand.trim() == c
            }.toMutableList()
            next.add(0, UittyNewTabRecent(path = p, presetOrdinal = null, customCommand = c))
            next.take(MAX_ITEMS)
        }
    }

    fun remove(context: Context, entry: UittyNewTabRecent) {
        mutate(context) { items ->
            items.filterNot {
                it.path == entry.path &&
                    it.presetOrdinal == entry.presetOrdinal &&
                    it.customCommand == entry.customCommand
            }
        }
    }

    private fun mutate(context: Context, block: (List<UittyNewTabRecent>) -> List<UittyNewTabRecent>) {
        val ctx = context.applicationContext
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = list(ctx)
        val updated = block(current)
        val arr = JSONArray()
        for (e in updated) {
            val o = JSONObject()
            o.put("path", e.path)
            if (e.presetOrdinal != null) o.put("presetOrdinal", e.presetOrdinal)
            if (e.customCommand.isNotBlank()) o.put("custom", e.customCommand)
            arr.put(o)
        }
        sp.edit().putString(KEY_ITEMS, arr.toString()).apply()
    }
}
