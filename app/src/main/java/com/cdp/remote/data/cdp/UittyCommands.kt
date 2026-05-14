package com.cdp.remote.data.cdp

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
 * - uittyAPI.sendEscape() — 中止当前 Claude Code / OpenCode 回合（勿用 Ctrl+C，会退出整个 CLI）
 * - uittyAPI.setAutoApprove(bool) — 自动审批开关
 * - uittyAPI.getTabs() / switchTab() / createTab() — **当前浏览器页内**的标签页（非系统浏览器 Tab）
 */
class UittyCommands(private val cdp: ICdpClient) {

    companion object {
        /** Bourne-style single-quoted path for `cd` */
        internal fun posixSingleQuotedPath(raw: String): String =
            "'" + raw.replace("'", "'\\''") + "'"

        internal fun shellCdThenRun(workingDirectory: String, cliLine: String): String =
            "cd ${posixSingleQuotedPath(workingDirectory)} && ${cliLine.trim()}"
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

    /** 中止当前生成/回合 — 发送 Esc（与 Claude Code、OpenCode TUI 一致；Ctrl+C 会杀掉 CLI 进程） */
    suspend fun stopGeneration(): CdpResult<Unit> {
        val result = cdp.evaluate("window.uittyAPI.sendEscape()")
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return CdpResult.Success(Unit)
    }

    /** 取消运行中的任务 — 同 [stopGeneration]（Esc，非 Ctrl+C） */
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

    // ─────────────────── 会话 / 标签页（当前 uitty 页内）───────────────────

    /**
     * @param cliInvocation `cd` 之后执行的一段 shell（不含 `cd`）
     */
    suspend fun launchCliInWorkspace(
        workingDirectory: String,
        cliInvocation: String,
        tabTitle: String,
        emoji: String
    ): CdpResult<Unit> {
        val cwd = workingDirectory.trim()
        if (cwd.isEmpty()) return CdpResult.Error("工作目录不能为空")
        val inv = cliInvocation.trim()
        if (inv.isEmpty()) return CdpResult.Error("命令不能为空")
        val fullShell = shellCdThenRun(cwd, inv)
        return launchTool(tabTitle.take(64), fullShell, emoji)
    }

    /**
     * 与 IDE「打开历史侧边栏」无等价物：uitty 的列表入口在远端工具栏 Tab。
     * 此处成功返回，交由 [ChatViewModel] 用 uitty 专用提示语。
     */
    suspend fun showRecentSessions(): CdpResult<Unit> = CdpResult.Success(Unit)

    /**
     * 切换会话 — 仅此 uitty **当前页面/窗口内**的标签页顺序切换（不改变浏览器 Tab / 不传 window）。
     */
    suspend fun switchSession(isNext: Boolean): CdpResult<Unit> {
        val dir = if (isNext) 1 else -1
        val result = cdp.evaluate(
            """
            (function() {
                var api = window.uittyAPI;
                if (!api || typeof api.getTabs !== 'function') return 'no-api';
                var tabs = api.getTabs();
                if (!Array.isArray(tabs) || tabs.length === 0) return 'no-tabs';
                if (tabs.length <= 1) return 'single';
                function resolveActiveIndex(ts) {
                    for (var i = 0; i < ts.length; i++) {
                        if (ts[i].isActive === true || ts[i].active === true) return i;
                    }
                    if (typeof api.getActiveTab === 'function') {
                        var active = api.getActiveTab();
                        if (active && active.id != null) {
                            for (var j = 0; j < ts.length; j++) {
                                if (ts[j].id === active.id) return j;
                            }
                        }
                    }
                    return 0;
                }
                var current = resolveActiveIndex(tabs);
                var len = tabs.length;
                var target = (((current + $dir) % len) + len) % len;
                var id = tabs[target].id;
                if (id == null) return 'bad-id';
                if (typeof api.switchTab !== 'function') return 'no-switch';
                var ok = api.switchTab(id);
                return ok === true || ok === undefined ? 'ok' : 'switch-failed';
            })()
            """.trimIndent()
        )
        return tabSwitchOutcomeToResult(result, "无法在 uitty 内切换标签页")
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
                val label = obj.get("label")?.asString
                    ?: obj.get("title")?.asString
                    ?: obj.get("name")?.asString
                    ?: "shell"
                val pid = obj.get("pid")?.asInt ?: 0
                val active = obj.get("isActive")?.asBoolean
                    ?: obj.get("active")?.asBoolean
                    ?: false
                "$emoji $label${if (active) " ◀" else ""} PID:$pid"
            }
            CdpResult.Success(sessions)
        } catch (e: Exception) {
            CdpResult.Error("解析 Tab 列表失败: ${e.message}")
        }
    }

    /** 通过索引切换到当前 uitty 页内的第 [index] 个标签页 */
    suspend fun switchSessionByIndex(index: Int): CdpResult<Unit> {
        if (index < 0) return CdpResult.Error("索引无效")
        val result = cdp.evaluate(
            """
            (function() {
                var api = window.uittyAPI;
                var idx = $index;
                if (!api || typeof api.getTabs !== 'function') return 'no-api';
                var tabs = api.getTabs();
                if (!Array.isArray(tabs) || tabs.length === 0) return 'no-tabs';
                if (idx < 0 || idx >= tabs.length) return 'bad-index';
                var id = tabs[idx].id;
                if (id == null) return 'bad-id';
                if (typeof api.switchTab !== 'function') return 'no-switch';
                var ok = api.switchTab(id);
                return ok === true || ok === undefined ? 'ok' : 'switch-failed';
            })()
            """.trimIndent()
        )
        return tabSwitchOutcomeToResult(result, "切换到该标签页失败")
    }

    /** 解析 [switchSession] / [switchSessionByIndex] 返回的状态字串 */
    private fun tabSwitchOutcomeToResult(
        evalResult: CdpResult<String?>,
        switchFailedFallback: String
    ): CdpResult<Unit> {
        if (evalResult is CdpResult.Error) return CdpResult.Error(evalResult.message)
        return when (val code = evalResult.getOrNull()) {
            "ok" -> CdpResult.Success(Unit)
            "single" ->
                CdpResult.Error("当前 uitty 窗口只有 1 个标签页，无需切换")
            "no-tabs" ->
                CdpResult.Error("未读取到 uitty 标签页列表（是否已就绪？）")
            "no-api" ->
                CdpResult.Error("页面未就绪：缺少 uittyAPI")
            "bad-index", "bad-id" ->
                CdpResult.Error("标签页索引或 id 无效")
            "no-switch" ->
                CdpResult.Error("uitty 未暴露 switchTab")
            "switch-failed" ->
                CdpResult.Error(switchFailedFallback)
            "true" -> CdpResult.Success(Unit)
            "false" ->
                CdpResult.Error(switchFailedFallback)
            null ->
                CdpResult.Error("uitty 未返回切换结果")
            else ->
                CdpResult.Error("uitty 切换异常：$code")
        }
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

    /** 关闭当前 uitty 页内的活动标签页（仅剩一个时由远端返回 `single`） */
    suspend fun closeCurrentTab(): CdpResult<Unit> {
        val result = cdp.evaluate(
            """
            (function() {
                var api = window.uittyAPI;
                if (!api || typeof api.closeTab !== 'function') return 'no-api';
                if (typeof api.getTabs === 'function') {
                    var tabs = api.getTabs();
                    if (!Array.isArray(tabs) || tabs.length <= 1) return 'single';
                }
                var tab = typeof api.getActiveTab === 'function' ? api.getActiveTab() : null;
                if (!tab || tab.id == null) return 'no-active';
                var ok = api.closeTab(tab.id);
                return (ok === true || ok === undefined) ? 'ok' : 'close-failed';
            })()
            """.trimIndent()
        )
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return when (val code = result.getOrNull()?.trim()) {
            "ok" -> CdpResult.Success(Unit)
            "single" ->
                CdpResult.Error("仅剩 1 个标签页，无法关闭（请直接在 uitty 里保留至少一个会话）")
            "no-api" ->
                CdpResult.Error("页面未就绪：缺少 uittyAPI.closeTab")
            "no-active" ->
                CdpResult.Error("未找到当前活动标签页")
            "close-failed" ->
                CdpResult.Error("uitty 拒绝关闭当前标签页")
            null ->
                CdpResult.Error("uitty 未返回关闭结果")
            else ->
                CdpResult.Error("关闭标签页异常：$code")
        }
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
