package com.cdp.remote.data.cdp

import com.google.gson.JsonParser

data class IdeInstance(
    val name: String,
    val port: Int,
    val title: String,
    val emoji: String = "",
    val wsUrl: String = "",
    val workspace: String = ""
)

object IdeTargetsParser {
    fun parsePages(json: String): List<CdpPage> {
        val root = JsonParser.parseString(json).asJsonObject
        val targets = root.getAsJsonArray("targets")
            ?: throw IllegalStateException("缺少 targets 字段")
        val pages = mutableListOf<CdpPage>()

        for (targetElement in targets) {
            val target = targetElement.asJsonObject
            val appType = ElectronAppType.fromAppName(target.get("appName")?.asString)
            val targetPages = target.getAsJsonArray("pages") ?: continue
            for (pageElement in targetPages) {
                val page = pageElement.asJsonObject
                pages.add(CdpPage(
                    id = page.get("id")?.asString ?: "",
                    type = page.get("type")?.asString ?: "",
                    title = page.get("title")?.asString ?: "",
                    url = page.get("url")?.asString ?: "",
                    webSocketDebuggerUrl = page.get("webSocketDebuggerUrl")?.asString ?: "",
                    devtoolsFrontendUrl = page.get("devtoolsFrontendUrl")?.asString ?: "",
                    appType = appType
                ))
            }
        }

        return pages
    }

    fun parseInstances(json: String): List<IdeInstance> {
        val root = JsonParser.parseString(json).asJsonObject
        val targets = root.getAsJsonArray("targets")
            ?: throw IllegalStateException("缺少 targets 字段")
        val instances = linkedMapOf<Pair<String, Int>, IdeInstance>()

        for (targetElement in targets) {
            val target = targetElement.asJsonObject
            val appName = target.get("appName")?.asString ?: continue
            val cdpPort = target.get("cdpPort")?.asInt ?: continue
            val emoji = target.get("appEmoji")?.asString ?: ""
            val workspace = target.get("workspace")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val pages = target.getAsJsonArray("pages") ?: continue

            for (pageElement in pages) {
                val page = pageElement.asJsonObject
                val type = page.get("type")?.asString ?: ""
                val url = page.get("url")?.asString ?: ""
                val title = page.get("title")?.asString ?: ""
                if (!isVisiblePage(type, url, title)) continue

                val instance = IdeInstance(
                    name = appName,
                    port = cdpPort,
                    title = title,
                    emoji = emoji,
                    wsUrl = page.get("webSocketDebuggerUrl")?.asString ?: "",
                    workspace = workspace
                )

                if (isWorkbenchPage(url, title, appName)) {
                    // 主窗口：同端口只保留 rank 最高的
                    val key = appName to cdpPort
                    val current = instances[key]
                    if (current == null || pageRank(instance.title, url) > pageRank(current.title, "")) {
                        instances[key] = instance
                    }
                } else {
                    // 弹窗/对话框：每个 page 独立展示
                    val pageId = page.get("id")?.asString ?: url
                    val key = "$appName:$cdpPort:$pageId" to cdpPort
                    instances[key] = instance
                }
            }
        }

        return instances.values.toList()
    }

    /** 过滤掉完全不需要展示的 target（扩展进程、空白页等） */
    private fun isVisiblePage(type: String, url: String, title: String): Boolean {
        if (type != "page") return false
        // 过滤扩展 host 页面
        if (url.contains("extensionHost", ignoreCase = true)) return false
        // 过滤空白页
        if (url == "about:blank" && title.isBlank()) return false
        return true
    }

    /** 判断是否是主编辑器窗口 */
    private fun isWorkbenchPage(url: String, title: String, appName: String): Boolean {
        if (title.contains("Launchpad", ignoreCase = true)) return false
        return url.contains("workbench", ignoreCase = true) ||
            url.startsWith("app://") ||
            ElectronAppType.fromAppName(appName) == ElectronAppType.CLAUDE_CODE
    }

    private fun pageRank(title: String, url: String): Int {
        val t = title.trim()
        if (t.isBlank()) return 0
        if (t.equals("Settings", ignoreCase = true)) return 1
        if (t.equals("Launchpad", ignoreCase = true)) return 1
        if (url.contains("jetski", ignoreCase = true)) return 1
        return 2
    }
}
