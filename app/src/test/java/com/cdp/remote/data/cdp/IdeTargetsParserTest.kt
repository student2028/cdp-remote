package com.cdp.remote.data.cdp

import org.junit.Assert.assertEquals
import org.junit.Test

class IdeTargetsParserTest {

    @Test
    fun `parsePages preserves page app type from target appName`() {
        val json = """{"targets":[
            {"cdpPort":9444,"appName":"Windsurf","appEmoji":"🏄","pages":[
                {"id":"p1","type":"page","url":"vscode-file://workbench/workbench.html","title":"CursorPresetModelsTest.kt","webSocketDebuggerUrl":"ws://t/windsurf","devtoolsFrontendUrl":""}
            ]},
            {"cdpPort":9666,"appName":"Codex","appEmoji":"📦","pages":[
                {"id":"p2","type":"page","url":"app://-/index.html","title":"Codex","webSocketDebuggerUrl":"ws://t/codex","devtoolsFrontendUrl":""}
            ]}
        ]}"""

        val pages = IdeTargetsParser.parsePages(json)

        assertEquals(2, pages.size)
        assertEquals(ElectronAppType.WINDSURF, pages[0].appType)
        assertEquals("CursorPresetModelsTest.kt", pages[0].title)
        assertEquals(ElectronAppType.CODEX, pages[1].appType)
    }

    @Test
    fun `parseInstances collapses multiple pages from same IDE port`() {
        val json = """{"targets":[
            {"cdpPort":9333,"appName":"Antigravity","appEmoji":"✨","pages":[
                {"type":"page","url":"vscode-file://workbench/workbench-jetski-agent.html","title":"Settings","webSocketDebuggerUrl":"ws://t/settings"},
                {"type":"page","url":"vscode-file://workbench/workbench.html","title":"tools1 - Project Analysis","webSocketDebuggerUrl":"ws://t/main"}
            ]},
            {"cdpPort":9334,"appName":"Antigravity","appEmoji":"✨","pages":[
                {"type":"page","url":"vscode-file://workbench/workbench-jetski-agent.html","title":"Settings","webSocketDebuggerUrl":"ws://t/settings2"},
                {"type":"page","url":"vscode-file://workbench/workbench.html","title":"mychat - Review Changes","webSocketDebuggerUrl":"ws://t/main2"}
            ]},
            {"cdpPort":9444,"appName":"Windsurf","appEmoji":"🏄","pages":[
                {"type":"page","url":"vscode-file://workbench/workbench.html","title":"tools1 - .env","webSocketDebuggerUrl":"ws://t/windsurf"}
            ]}
        ]}"""

        val instances = IdeTargetsParser.parseInstances(json)

        assertEquals(3, instances.size)
        assertEquals(listOf(9333, 9334, 9444), instances.map { it.port })
        assertEquals("tools1 - Project Analysis", instances[0].title)
        assertEquals("ws://t/main", instances[0].wsUrl)
        assertEquals("mychat - Review Changes", instances[1].title)
    }

    @Test
    fun `parseInstances includes Codex app pages`() {
        val json = """{"targets":[
            {"cdpPort":9666,"appName":"Codex","appEmoji":"📦","pages":[
                {"type":"page","url":"app://-/index.html?hostId=local","title":"Codex","webSocketDebuggerUrl":"ws://t/codex"},
                {"type":"worker","url":"","title":"","webSocketDebuggerUrl":""}
            ]}
        ]}"""

        val instances = IdeTargetsParser.parseInstances(json)

        assertEquals(1, instances.size)
        assertEquals("Codex", instances[0].name)
        assertEquals(9666, instances[0].port)
    }

    @Test
    fun `parseInstances filters Launchpad pages when workbench is present`() {
        val json = """{"targets":[
            {"cdpPort":9444,"appName":"Windsurf","appEmoji":"🏄","pages":[
                {"type":"page","url":"file:///launchpad.html","title":"Windsurf Launchpad","webSocketDebuggerUrl":"ws://t/launchpad"},
                {"type":"page","url":"file:///workbench.html","title":"Windsurf","webSocketDebuggerUrl":"ws://t/workbench"}
            ]}
        ]}"""

        val instances = IdeTargetsParser.parseInstances(json)

        assertEquals(1, instances.size)
        assertEquals("Windsurf", instances[0].title)
        assertEquals("ws://t/workbench", instances[0].wsUrl)
    }
}
