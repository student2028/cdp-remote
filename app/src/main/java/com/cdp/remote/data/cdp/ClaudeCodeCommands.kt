package com.cdp.remote.data.cdp

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.delay

/**
 * Claude Code (Simple Code GUI) 专用 CDP 命令集
 *
 * Claude Code 是一个纯终端（xterm.js）界面，不像其他 IDE 有富文本 DOM 结构。
 * 所有交互都通过向终端发送键盘事件（Input.dispatchKeyEvent）来完成：
 *
 * - 中断: Ctrl+C
 * - 接受: y + Enter
 * - 拒绝: n + Enter
 * - 发送消息: 直接打字 + Enter
 * - 翻历史: 方向键 ↑/↓
 * - 补全: Tab
 * - 取消: Esc
 * - 停止: Ctrl+C (与中断相同)
 *
 * 参考 Simple Code GUI 的 MobileTerminalBar 按钮设计：
 *   [^C] [↑] [↓] [Tab] [Esc] [⏎] | [Commands /] [GSD 📋] [Session ⚡] [Backend 🔧]
 */
class ClaudeCodeCommands(private val cdp: ICdpClient) {

    companion object {
        private const val TAG = "ClaudeCodeCmds"
    }

    // ─────────────────── 底层按键发送 ───────────────────

    /** 发送单个按键（keyDown + keyUp） */
    private suspend fun sendKey(key: String, code: String, vkCode: Int, modifiers: Int = 0) {
        cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
            addProperty("type", "keyDown")
            addProperty("key", key)
            addProperty("code", code)
            addProperty("windowsVirtualKeyCode", vkCode)
            if (modifiers != 0) addProperty("modifiers", modifiers)
        })
        delay(30)
        cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
            addProperty("type", "keyUp")
            addProperty("key", key)
            addProperty("code", code)
            addProperty("windowsVirtualKeyCode", vkCode)
            if (modifiers != 0) addProperty("modifiers", modifiers)
        })
    }

    /** 发送 Ctrl+Key 组合键 */
    private suspend fun sendCtrlKey(key: String, code: String, vkCode: Int) {
        // modifiers: 2 = Ctrl (Windows/Linux), 4 = Meta/Cmd (macOS)
        // xterm.js 在 macOS Electron 里也识别 modifiers=2 作为 Ctrl
        cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
            addProperty("type", "keyDown")
            addProperty("key", key)
            addProperty("code", code)
            addProperty("windowsVirtualKeyCode", vkCode)
            addProperty("modifiers", 2)  // Ctrl
        })
        delay(30)
        cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
            addProperty("type", "keyUp")
            addProperty("key", key)
            addProperty("code", code)
            addProperty("windowsVirtualKeyCode", vkCode)
            addProperty("modifiers", 2)
        })
    }

    /** 逐字符输入文本到终端 */
    private suspend fun typeText(text: String) {
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            val charText = String(Character.toChars(codePoint))
            cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
                addProperty("type", "char")
                addProperty("text", charText)
                addProperty("unmodifiedText", charText)
            })
            delay(20)
            offset += Character.charCount(codePoint)
        }
    }

    /** 发送 Enter */
    private suspend fun sendEnter() {
        sendKey("Enter", "Enter", 13)
    }

    /** 发送 Ctrl+C（中断） */
    private suspend fun sendCtrlC() {
        sendCtrlKey("c", "KeyC", 67)
    }

    /** 发送 Escape */
    private suspend fun sendEscape() {
        sendKey("Escape", "Escape", 27)
    }

    /** 发送 Tab */
    private suspend fun sendTab() {
        sendKey("Tab", "Tab", 9)
    }

    /** 发送方向键 ↑ */
    private suspend fun sendArrowUp() {
        sendKey("ArrowUp", "ArrowUp", 38)
    }

    /** 发送方向键 ↓ */
    private suspend fun sendArrowDown() {
        sendKey("ArrowDown", "ArrowDown", 40)
    }

    // ─────────────────── 聚焦终端 ───────────────────

    /**
     * 聚焦 xterm.js 终端区域
     * Simple Code GUI 的终端是 canvas 元素，需要聚焦其容器
     */
    suspend fun focusInput(): CdpResult<Boolean> {
        val result = cdp.evaluate("""
            (function() {
                // xterm.js 终端的 textarea（用于接收键盘输入的隐藏元素）
                var xtermTextarea = document.querySelector('.xterm-helper-textarea');
                if (xtermTextarea) { xtermTextarea.focus(); return 'ok'; }
                // 回退: 找 xterm 容器
                var xtermContainer = document.querySelector('.xterm');
                if (xtermContainer) { xtermContainer.focus(); return 'ok'; }
                // 再回退: 任何 terminal 容器
                var terminal = document.querySelector('[class*="terminal"]');
                if (terminal) { terminal.focus(); return 'ok'; }
                return 'no-terminal';
            })()
        """.trimIndent())

        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return when (result.getOrNull()) {
            "ok" -> CdpResult.Success(true)
            else -> CdpResult.Error("聚焦终端失败: ${result.getOrNull()}")
        }
    }

    // ─────────────────── 设置输入文字（终端方式）───────────────────

    /**
     * 在终端中输入文字 - 逐字符发送
     * Claude Code 是终端，没有 DOM 输入框，直接往 xterm 打字
     */
    suspend fun setInputText(text: String): CdpResult<Unit> {
        focusInput().let { if (it is CdpResult.Error) return CdpResult.Error("聚焦失败: ${it.message}") }
        delay(50)
        typeText(text)
        return CdpResult.Success(Unit)
    }

    // ─────────────────── 发送消息（打字 + Enter）───────────────────

    suspend fun clickSendButton(): CdpResult<Boolean> {
        sendEnter()
        return CdpResult.Success(true)
    }

    /**
     * 发送完整消息到 Claude Code 终端
     * 等同于：聚焦终端 → 打字 → 按 Enter
     */
    suspend fun sendMessage(text: String): CdpResult<Unit> {
        focusInput().let { if (it is CdpResult.Error) return CdpResult.Error("聚焦失败: ${it.message}") }
        delay(100)
        typeText(text)
        delay(100)
        sendEnter()
        return CdpResult.Success(Unit)
    }

    // ─────────────────── 图片粘贴（终端不支持，返回提示）───────────────────

    suspend fun pasteImage(base64Data: String, mimeType: String = "image/png", fileName: String = "image.png"): CdpResult<Boolean> {
        return CdpResult.Error("Claude Code 终端不支持图片粘贴")
    }

    suspend fun sendImage(base64Data: String, mimeType: String = "image/png"): CdpResult<Unit> {
        return CdpResult.Error("Claude Code 终端不支持图片发送")
    }

    /**
     * 获取终端最后的输出内容
     *
     * xterm v5 使用 canvas 渲染，没有 .xterm-rows DOM 也没有 ._terminal 属性。
     * 实际上在 cdp-remote 架构中，手机端主要通过 CDP 截屏来查看终端内容，
     * 此方法作为辅助，尝试通过 accessibility DOM 或 xterm screen reader 获取文本。
     */
    suspend fun getLastReply(): CdpResult<String> {
        val result = cdp.evaluate("""
            (function() {
                try {
                    // 方式1: xterm accessibility addon 的 DOM（如果启用了 screen reader）
                    var accessRows = document.querySelectorAll('.xterm-accessibility div, .xterm-screen div[role]');
                    if (accessRows.length > 0) {
                        var lines = [];
                        var start = Math.max(0, accessRows.length - 50);
                        for (var i = start; i < accessRows.length; i++) {
                            var text = (accessRows[i].textContent || '').trimEnd();
                            if (text.length > 0) lines.push(text);
                        }
                        if (lines.length > 0) return lines.join('\n');
                    }
                    // 方式2: xterm-screen 中可能的文本层
                    var screen = document.querySelector('.xterm-screen');
                    if (screen) {
                        var allText = screen.innerText || screen.textContent || '';
                        if (allText.trim().length > 0) return allText.trim();
                    }
                    // xterm v5 canvas 渲染 — 无法直接读取文本
                    // 手机端通过 CDP 截屏查看终端内容即可
                    return '';
                } catch(e) { return 'error:' + e.message; }
            })()
        """.trimIndent())

        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        val text = result.getOrNull() ?: ""
        if (text.startsWith("error:")) return CdpResult.Error(text)
        return CdpResult.Success(text)
    }

    // ─────────────────── 获取聊天历史（终端内容解析）───────────────────

    /**
     * 从终端 buffer 解析对话历史
     * Claude Code 终端的提示符格式: "❯ " 或 "> " 后面跟用户输入
     * 助手回复是之间的所有文本
     */
    suspend fun getChatHistory(): CdpResult<List<ChatMessage>> {
        val result = cdp.evaluate("""
            (function() {
                try {
                    var allText = '';
                    // 优先使用 xterm buffer
                    var xtermEl = document.querySelector('.xterm');
                    if (xtermEl && xtermEl._terminal) {
                        var term = xtermEl._terminal;
                        var buf = term.buffer.active;
                        var lines = [];
                        for (var i = 0; i < buf.length; i++) {
                            var line = buf.getLine(i);
                            if (line) lines.push(line.translateToString(true));
                        }
                        allText = lines.join('\n');
                    } else {
                        var rows = document.querySelectorAll('.xterm-rows > div');
                        var lines2 = [];
                        for (var j = 0; j < rows.length; j++) {
                            lines2.push(rows[j].textContent || '');
                        }
                        allText = lines2.join('\n');
                    }
                    // 简单解析: 找到 ❯ 或 > 提示符分割用户/助手消息
                    var items = [];
                    var parts = allText.split(/^[❯>]\s/m);
                    for (var k = 1; k < parts.length; k++) {
                        var part = parts[k];
                        var nlIdx = part.indexOf('\n');
                        if (nlIdx > 0) {
                            var userText = part.substring(0, nlIdx).trim();
                            var assistText = part.substring(nlIdx + 1).trim();
                            if (userText) items.push({role: 'USER', content: userText});
                            if (assistText) items.push({role: 'ASSISTANT', content: assistText});
                        } else if (part.trim()) {
                            items.push({role: 'USER', content: part.trim()});
                        }
                    }
                    return JSON.stringify(items);
                } catch(e) { return '[]'; }
            })()
        """.trimIndent())

        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return try {
            val jsonArray = JsonParser.parseString(result.getOrNull() ?: "[]").asJsonArray
            val messages = jsonArray.map { element ->
                val obj = element.asJsonObject
                ChatMessage(
                    role = if (obj.get("role").asString == "USER") MessageRole.USER else MessageRole.ASSISTANT,
                    content = obj.get("content").asString
                )
            }
            CdpResult.Success(messages)
        } catch (e: Exception) {
            CdpResult.Error("解析历史记录失败: ${e.message}")
        }
    }

    // ─────────────────── 生成状态检测 ───────────────────

    /**
     * 检测 Claude Code 是否正在生成
     *
     * xterm v5 canvas 渲染无法直接读取文本，改用以下策略：
     * 1. 检查页面上是否有 loading/spinner 动画 CSS class
     * 2. 检查 xterm 的 cursor blink 状态
     * 3. 检查终端标题栏是否有 loading 指示
     */
    suspend fun isGenerating(): Boolean {
        val result = cdp.evaluate("""
            (function() {
                try {
                    // 策略1: 检查 Simple Code GUI 的 loading 指示器
                    var spinners = document.querySelectorAll('[class*="animate-spin"], [class*="loading"], [class*="spinner"], [class*="pulse"]');
                    for (var j = 0; j < spinners.length; j++) {
                        if (spinners[j].offsetParent) return true;
                    }
                    // 策略2: 检查 tab 标题是否包含 loading 状态
                    var tabs = document.querySelectorAll('[class*="tab"]');
                    for (var k = 0; k < tabs.length; k++) {
                        var t = (tabs[k].textContent || '').trim();
                        if (t.includes('⠋') || t.includes('⠙') || t.includes('⠹') ||
                            t.includes('Thinking') || t.includes('Working')) return true;
                    }
                    // 策略3: 检查 terminal indicator dot 颜色变化
                    var dots = document.querySelectorAll('[class*="dot"], [class*="indicator"], [class*="status"]');
                    for (var d = 0; d < dots.length; d++) {
                        var style = window.getComputedStyle(dots[d]);
                        // 闪烁/活跃状态的指示器
                        if (style.animation && style.animation !== 'none') return true;
                    }
                    return false;
                } catch(e) { return false; }
            })()
        """.trimIndent())
        return result.getOrNull()?.toBooleanStrictOrNull() ?: false
    }

    // ─────────────────── 自动放行（Claude CLI 的 y/n 提示）───────────────────

    /**
     * 自动放行 Claude Code 的工具执行确认
     *
     * Simple Code GUI 有内置的 auto-accept 功能（通过 electronAPI.setAutoAccept）。
     * 同时也通过 Shift+Tab 快捷键来 auto-accept。
     * 如果无法通过 API 判断，直接发送 Shift+Tab 作为 auto-accept 快捷键。
     */
    suspend fun autoAcceptActions(): Boolean {
        // 策略1: 检查并启用 Simple Code GUI 的自动放行
        val autoAcceptResult = cdp.evaluate("""
            (async function() {
                try {
                    if (window.electronAPI && window.electronAPI.getAutoAcceptStatus) {
                        var status = await window.electronAPI.getAutoAcceptStatus();
                        if (status && status.autoAccept) return 'auto-accept-on';
                        // 启用 auto-accept
                        if (window.electronAPI.setAutoAccept) {
                            await window.electronAPI.setAutoAccept(true);
                            return 'auto-accept-enabled';
                        }
                    }
                    return 'no-api';
                } catch(e) { return 'error:' + e.message; }
            })()
        """.trimIndent())

        val apiResult = autoAcceptResult.getOrNull() ?: "no-api"
        if (apiResult == "auto-accept-on" || apiResult == "auto-accept-enabled") {
            Log.d(TAG, "Claude Code 自动放行已启用 ✅ ($apiResult)")
            return true
        }

        // 策略2: 发送 Shift+Tab（Claude Code 的 auto-accept 快捷键）
        focusInput()
        delay(50)
        sendKey("Tab", "Tab", 9, 1)  // modifiers=1 是 Shift
        Log.d(TAG, "发送 Shift+Tab 自动放行快捷键 ✅")
        return true
    }

    // ─────────────────── Accept / Reject ───────────────────

    /** 接受 - 在终端中输入 'y' + Enter */
    suspend fun acceptAll(): CdpResult<Boolean> {
        focusInput().let { if (it is CdpResult.Error) return CdpResult.Error(it.message) }
        delay(50)
        typeText("y")
        delay(50)
        sendEnter()
        return CdpResult.Success(true)
    }

    /** 拒绝 - 在终端中输入 'n' + Enter */
    suspend fun rejectAll(): CdpResult<Boolean> {
        focusInput().let { if (it is CdpResult.Error) return CdpResult.Error(it.message) }
        delay(50)
        typeText("n")
        delay(50)
        sendEnter()
        return CdpResult.Success(true)
    }

    // ─────────────────── 会话控制 ───────────────────

    /**
     * 新建会话 - Claude CLI 中用 /clear 命令
     */
    suspend fun startNewSession(): CdpResult<Unit> {
        return sendMessage("/clear")
    }

    /**
     * 显示最近会话 - Claude CLI 中用 /resume 命令
     */
    suspend fun showRecentSessions(): CdpResult<Unit> {
        return sendMessage("/resume")
    }

    /**
     * 切换会话 - 终端不支持按索引切换，发送 /resume 后手动选择
     */
    suspend fun switchSession(isNext: Boolean): CdpResult<Unit> {
        // 在终端中用方向键选择
        focusInput().let { if (it is CdpResult.Error) return CdpResult.Error(it.message) }
        delay(50)
        if (isNext) sendArrowDown() else sendArrowUp()
        delay(100)
        sendEnter()
        return CdpResult.Success(Unit)
    }

    /**
     * 获取最近会话列表 - 发送 /resume 并读取输出
     */
    suspend fun getRecentSessionsList(): CdpResult<List<String>> {
        // 终端模式下难以精确解析会话列表，返回提示
        return CdpResult.Error("Claude Code 终端请使用 /resume 命令查看会话列表")
    }

    suspend fun switchSessionByIndex(index: Int): CdpResult<Unit> {
        // 终端不支持精确索引切换
        return CdpResult.Error("Claude Code 终端请使用 /resume 命令手动选择会话")
    }

    // ─────────────────── 停止生成 ───────────────────

    /** 停止生成 - 发送 Ctrl+C 中断终端 */
    suspend fun stopGeneration(): CdpResult<Unit> {
        focusInput()
        delay(50)
        sendCtrlC()
        return CdpResult.Success(Unit)
    }

    // ─────────────────── 取消任务 ───────────────────

    /** 取消运行中的任务 - 在终端中也是 Ctrl+C */
    suspend fun cancelRunningTask(): CdpResult<Unit> {
        return stopGeneration()
    }

    // ─────────────────── 模型切换 ───────────────────

    /**
     * 切换模型 - Claude CLI 用 /model 命令
     */
    suspend fun switchModel(modelName: String): CdpResult<Unit> {
        return sendMessage("/model $modelName")
    }

    /**
     * 获取当前模型 - 从终端中读取
     */
    suspend fun getCurrentModel(): CdpResult<String> {
        // 通过 /model 命令查看
        return CdpResult.Success("claude-sonnet-4") // 默认模型
    }

    /**
     * 列出可用模型
     */
    suspend fun listModelOptions(): CdpResult<List<String>> {
        return CdpResult.Success(listOf(
            "claude-sonnet-4",
            "claude-opus-4",
            "claude-haiku"
        ))
    }

    // ─────────────────── Claude CLI 专有命令 ───────────────────

    /** 发送 /help 命令 */
    suspend fun showHelp(): CdpResult<Unit> = sendMessage("/help")

    /** 发送 /compact 命令（压缩上下文） */
    suspend fun compactContext(): CdpResult<Unit> = sendMessage("/compact")

    /** 发送 /config 命令（查看配置） */
    suspend fun showConfig(): CdpResult<Unit> = sendMessage("/config")

    /** 发送 /cost 命令（查看花费） */
    suspend fun showCost(): CdpResult<Unit> = sendMessage("/cost")

    /** 发送 /doctor 命令（诊断） */
    suspend fun runDoctor(): CdpResult<Unit> = sendMessage("/doctor")

    /** 发送 /init 命令（初始化 CLAUDE.md） */
    suspend fun initProject(): CdpResult<Unit> = sendMessage("/init")

    /** 发送 /memory 命令（查看记忆） */
    suspend fun showMemory(): CdpResult<Unit> = sendMessage("/memory")

    /** 发送 /status 命令（查看状态） */
    suspend fun showStatus(): CdpResult<Unit> = sendMessage("/status")

    /** 发送 Ctrl+L（清屏） */
    suspend fun clearScreen(): CdpResult<Unit> {
        focusInput()
        delay(50)
        sendCtrlKey("l", "KeyL", 76)
        return CdpResult.Success(Unit)
    }

    // ─────────────────── 全局规则（不适用）───────────────────

    suspend fun setGlobalAgentRule(text: String): CdpResult<Unit> {
        return CdpResult.Error("Claude Code 使用 CLAUDE.md 文件管理规则，请使用 /init 命令")
    }

    // ─────────────────── 错误检测（简化版）───────────────────

    suspend fun checkAndRetryIfBusy(): CdpResult<Boolean> {
        // Claude Code 终端一般不会出现需要 Retry 按钮的情况
        return CdpResult.Success(false)
    }
}
