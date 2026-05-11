package com.cdp.remote.data.cdp

import android.util.Log

/**
 * Uitty Terminal 专用 CDP 命令集
 *
 * uitty 是一个多 Tab 终端，内部运行 OpenCode、Claude Code、Aider 等 TUI 工具。
 * 交互方式：通过 CDP Runtime.evaluate 调用 window.uittyAPI 暴露的 JS 方法。
 *
 * 与 ClaudeCodeCommands 的区别：
 * - uitty 多了 Tab 管理（切换、创建、关闭）
 * - uitty 直接调用 JS API（而非 Input.dispatchKeyEvent）更可靠
 * - uitty 内置自动审批，这里只需要控制开关
 *
 * 关键 API：
 * - uittyAPI.sendMessage(text) — 写入文本 + Enter
 * - uittyAPI.getTerminalContent(lines) — 读取终端缓冲区
 * - uittyAPI.sendCtrlC() — 中断
 * - uittyAPI.setAutoApprove(bool) — 自动审批开关
 * - uittyAPI.getTabs() / switchTab() / createTab() — Tab 管理
 */
class UittyCommands(private val cdp: ICdpClient) {

    companion object {
        private const val TAG = "UittyCmds"
    }

    // ─────────────────── 消息发送 ───────────────────

    /**
     * 发送消息到 uitty 当前活动终端
     * 直接调用 uittyAPI.sendMessage，比 dispatchKeyEvent 更可靠
     */
    suspend fun sendMessage(text: String): CdpResult<Unit> {
        val escaped = text.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        val result = cdp.evaluate("window.uittyAPI.sendMessage('$escaped')")
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        val ok = result.getOrNull()?.toBooleanStrictOrNull() ?: false
        return if (ok) CdpResult.Success(Unit)
        else CdpResult.Error("发送失败：无活动终端")
    }

    /**
     * 直接写入文本（不追加 Enter）
     */
    suspend fun writeToTerminal(text: String): CdpResult<Unit> {
        val escaped = text.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        val result = cdp.evaluate("window.uittyAPI.writeToTerminal('$escaped')")
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return CdpResult.Success(Unit)
    }

    // ─────────────────── 终端内容读取 ───────────────────

    /**
     * 获取终端最新输出（xterm buffer 最后 N 行）
     */
    suspend fun getLastReply(): CdpResult<String> {
        val result = cdp.evaluate("window.uittyAPI.getTerminalContent(50)")
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return CdpResult.Success(result.getOrNull() ?: "")
    }

    // ─────────────────── 生成状态检测 ───────────────────

    /**
     * 检测终端是否正在生成输出
     *
     * uitty 的自动审批已内置在前端，这里只需检查终端是否有活跃的 PTY 进程。
     * 实际判断逻辑：比较两次轮询间的终端内容是否变化（由 ChatViewModel 管理）。
     * 这里简单返回 false，让 ChatViewModel 的 pollCount 超时机制自然结束。
     */
    suspend fun isGenerating(): Boolean {
        // uitty 终端模式下，无法精确判断 TUI 工具是否在生成。
        // 依赖 ChatViewModel 的 lastReplyText 变化检测和 pollCount 超时。
        return false
    }

    // ─────────────────── 自动审批 ───────────────────

    /**
     * 自动放行 — uitty 内置自动审批，这里触发一次手动检查
     */
    suspend fun autoAcceptActions(): Boolean {
        val result = cdp.evaluate("window.uittyAPI.triggerAutoApprove()")
        return result.getOrNull()?.toBooleanStrictOrNull() ?: false
    }

    /**
     * 设置自动审批状态
     */
    suspend fun setAutoApprove(enabled: Boolean): CdpResult<Boolean> {
        val result = cdp.evaluate("window.uittyAPI.setAutoApprove($enabled)")
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return CdpResult.Success(result.getOrNull()?.toBooleanStrictOrNull() ?: enabled)
    }

    /**
     * 获取自动审批状态
     */
    suspend fun isAutoApproveEnabled(): Boolean {
        val result = cdp.evaluate("window.uittyAPI.isAutoApproveEnabled()")
        return result.getOrNull()?.toBooleanStrictOrNull() ?: true
    }

    /**
     * 检查是否有待审批的对话框
     */
    suspend fun hasPendingApproval(): Boolean {
        val result = cdp.evaluate("window.uittyAPI.hasPendingApproval()")
        return result.getOrNull()?.toBooleanStrictOrNull() ?: false
    }

    // ─────────────────── 停止/中断 ───────────────────

    /** 发送 Ctrl+C 中断 */
    suspend fun stopGeneration(): CdpResult<Unit> {
        val result = cdp.evaluate("window.uittyAPI.sendCtrlC()")
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return CdpResult.Success(Unit)
    }

    /** 取消运行中的任务 — 同 Ctrl+C */
    suspend fun cancelRunningTask(): CdpResult<Unit> = stopGeneration()

    // ─────────────────── 接受 / 拒绝 ───────────────────

    /** 接受 — 发送 Enter（适用于大多数 TUI 确认框） */
    suspend fun acceptAll(): CdpResult<Boolean> {
        val result = cdp.evaluate("window.uittyAPI.sendEnter()")
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return CdpResult.Success(true)
    }

    /** 拒绝 — 发送 Escape */
    suspend fun rejectAll(): CdpResult<Boolean> {
        val result = cdp.evaluate("window.uittyAPI.sendEscape()")
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return CdpResult.Success(true)
    }

    // ─────────────────── 会话控制 ───────────────────

    /** 新建会话 — 在当前 Tab 发送 Ctrl+C 清除，重新开始 */
    suspend fun startNewSession(): CdpResult<Unit> {
        cdp.evaluate("window.uittyAPI.sendCtrlC()")
        return CdpResult.Success(Unit)
    }

    /** 显示最近会话列表（终端不支持，返回提示） */
    suspend fun showRecentSessions(): CdpResult<Unit> {
        return CdpResult.Error("uitty 终端请直接切换 Tab 查看不同工具会话")
    }

    /** 切换会话 — 映射为切换 Tab */
    suspend fun switchSession(isNext: Boolean): CdpResult<Unit> {
        val result = cdp.evaluate("""
            (function() {
                var tabs = window.uittyAPI.getTabs();
                if (!tabs || tabs.length <= 1) return false;
                var current = tabs.findIndex(function(t) { return t.isActive; });
                if (current < 0) return false;
                var target = ${if (isNext) "(current + 1) % tabs.length" else "(current - 1 + tabs.length) % tabs.length"};
                return window.uittyAPI.switchTab(tabs[target].id);
            })()
        """.trimIndent())
        return if (result is CdpResult.Error) CdpResult.Error(result.message)
        else CdpResult.Success(Unit)
    }

    /** 获取最近会话列表 — 返回 Tab 列表作为会话 */
    suspend fun getRecentSessionsList(): CdpResult<List<String>> {
        val result = cdp.evaluate("JSON.stringify(window.uittyAPI.getTabs())")
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return try {
            val jsonArray = com.google.gson.JsonParser.parseString(result.getOrNull() ?: "[]").asJsonArray
            val sessions = jsonArray.map { element ->
                val obj = element.asJsonObject
                val emoji = obj.get("emoji")?.asString ?: "🐚"
                val label = obj.get("label")?.asString ?: "shell"
                val cmd = obj.get("cmd")?.asString ?: ""
                val pid = obj.get("pid")?.asInt ?: 0
                val active = obj.get("isActive")?.asBoolean ?: false
                "$emoji $label${if (cmd.isNotEmpty()) " ($cmd)" else ""}${if (active) " ◀" else ""} PID:$pid"
            }
            CdpResult.Success(sessions)
        } catch (e: Exception) {
            CdpResult.Error("解析 Tab 列表失败: ${e.message}")
        }
    }

    /** 通过索引切换 Tab */
    suspend fun switchSessionByIndex(index: Int): CdpResult<Unit> {
        val result = cdp.evaluate("""
            (function() {
                var tabs = window.uittyAPI.getTabs();
                if (!tabs || $index >= tabs.length) return false;
                return window.uittyAPI.switchTab(tabs[$index].id);
            })()
        """.trimIndent())
        return if (result is CdpResult.Error) CdpResult.Error(result.message)
        else CdpResult.Success(Unit)
    }

    // ─────────────────── Tab 管理（uitty 特有）───────────────────

    /** 新建 Tab — 启动指定工具 */
    suspend fun launchTool(toolName: String, cmd: String, emoji: String = "⚡"): CdpResult<Unit> {
        val escapedName = toolName.replace("'", "\\'")
        val escapedCmd = cmd.replace("'", "\\'")
        val escapedEmoji = emoji.replace("'", "\\'")
        val result = cdp.evaluate("JSON.stringify(window.uittyAPI.createTab('$escapedName', '$escapedCmd', '$escapedEmoji'))")
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return CdpResult.Success(Unit)
    }

    /** 关闭当前 Tab */
    suspend fun closeCurrentTab(): CdpResult<Unit> {
        val result = cdp.evaluate("""
            (function() {
                var tab = window.uittyAPI.getActiveTab();
                if (!tab) return false;
                return window.uittyAPI.closeTab(tab.id);
            })()
        """.trimIndent())
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return CdpResult.Success(Unit)
    }

    /** 获取可用工具列表 */
    suspend fun getAvailableTools(): CdpResult<List<String>> {
        val result = cdp.evaluate("""
            (async function() {
                var tools = await window.uittyAPI.getTools();
                return JSON.stringify(tools);
            })()
        """.trimIndent())
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return try {
            val jsonArray = com.google.gson.JsonParser.parseString(result.getOrNull() ?: "[]").asJsonArray
            val tools = jsonArray.map { element ->
                val obj = element.asJsonObject
                val emoji = obj.get("emoji")?.asString ?: ""
                val name = obj.get("name")?.asString ?: ""
                val installed = obj.get("installed")?.asBoolean ?: false
                val cmd = obj.get("cmd")?.asString ?: ""
                "$emoji $name${if (!installed) " ⚠ 未安装" else ""}${if (cmd.isNotEmpty()) " [$cmd]" else ""}"
            }
            CdpResult.Success(tools)
        } catch (e: Exception) {
            CdpResult.Error("解析工具列表失败: ${e.message}")
        }
    }

    // ─────────────────── 滚动 ───────────────────

    /** 上翻 — 发送 Page Up 或上箭头 */
    suspend fun scrollUp(): CdpResult<Unit> {
        cdp.evaluate("window.uittyAPI.sendArrow('up')")
        return CdpResult.Success(Unit)
    }

    /** 下翻 — 发送 Page Down 或下箭头 */
    suspend fun scrollDown(): CdpResult<Unit> {
        cdp.evaluate("window.uittyAPI.sendArrow('down')")
        return CdpResult.Success(Unit)
    }

    // ─────────────────── 聚焦 ───────────────────

    /** 聚焦终端输入 */
    suspend fun focusInput(): CdpResult<Boolean> {
        val result = cdp.evaluate("""
            (function() {
                var ta = document.querySelector('.xterm-helper-textarea');
                if (ta) { ta.focus(); return true; }
                return false;
            })()
        """.trimIndent())
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return CdpResult.Success(result.getOrNull()?.toBooleanStrictOrNull() ?: false)
    }

    // ─────────────────── 模型切换（不适用）───────────────────

    suspend fun switchModel(modelName: String): CdpResult<Unit> {
        return CdpResult.Error("uitty 终端内的工具各自管理模型，请在对应工具中切换")
    }

    suspend fun getCurrentModel(): CdpResult<String> {
        return CdpResult.Success("") // 终端模式无全局模型概念
    }

    // ─────────────────── 全局规则（不适用）───────────────────

    suspend fun setGlobalAgentRule(text: String): CdpResult<Unit> {
        return CdpResult.Error("uitty 终端不支持全局规则设置")
    }

    // ─────────────────── 错误检测 ───────────────────

    suspend fun checkAndRetryIfBusy(): CdpResult<Boolean> {
        // uitty 终端的 TUI 工具不会出现 Retry 按钮
        return CdpResult.Success(false)
    }
}
