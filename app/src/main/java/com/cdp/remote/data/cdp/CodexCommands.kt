package com.cdp.remote.data.cdp

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.delay

/**
 * OpenAI Codex 桌面端 CDP 命令集
 *
 * 基于实际 DOM dump 分析开发：
 * - 输入框: ProseMirror [data-codex-composer="true"]
 * - 消息角色: data-content-search-unit-key="{uuid}:{idx}:{user|assistant}"
 * - 助手内容: ._markdownContent_1rhk1_42
 * - 滚动区: [data-app-action-timeline-scroll]
 */
class CodexCommands(private val cdp: ICdpClient) {

    companion object {
        private const val TAG = "CodexCmds"
    }

    // ─────────────────── 聚焦输入框 ───────────────────

    suspend fun focusInput(): CdpResult<Boolean> {
        val result = cdp.evaluate("""
            (function() {
                var pm = document.querySelector('.ProseMirror[data-codex-composer="true"]');
                if (!pm) pm = document.querySelector('.ProseMirror[contenteditable="true"]');
                if (pm && pm.offsetParent) { pm.focus(); return 'ok'; }
                return 'no-input';
            })()
        """.trimIndent())
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return when (result.getOrNull()) {
            "ok" -> CdpResult.Success(true)
            else -> CdpResult.Error("聚焦失败: ${result.getOrNull()}")
        }
    }

    // ─────────────────── 设置输入文字 ───────────────────

    suspend fun setInputText(text: String): CdpResult<Unit> {
        val escaped = text.replace("\\", "\\\\").replace("'", "\\'")
            .replace("\n", "\\n").replace("\r", "\\r")

        val result = cdp.evaluate("""
            (function() {
                var pm = document.querySelector('.ProseMirror[data-codex-composer="true"]');
                if (!pm) pm = document.querySelector('.ProseMirror[contenteditable="true"]');
                if (!pm) return 'no-input';
                pm.focus();
                document.execCommand('selectAll', false, null);
                document.execCommand('delete', false, null);
                document.execCommand('insertText', false, '$escaped');
                return 'ok';
            })()
        """.trimIndent())

        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        if (result.getOrNull() == "no-input") return CdpResult.Error("找不到 ProseMirror 输入框")
        return CdpResult.Success(Unit)
    }

    // ─────────────────── 点击发送 ───────────────────

    suspend fun clickSendButton(): CdpResult<Boolean> {
        val result = cdp.evaluate("""
            (function() {
                // Codex 发送按钮: 输入框容器内 size-token-button-composer 类的圆形按钮
                var btn = document.querySelector('button[class*="size-token-button-composer"]');
                if (btn && btn.offsetParent) { btn.click(); return 'clicked'; }
                // 回退: 输入框附近最后一个按钮
                var composer = document.querySelector('.ProseMirror[data-codex-composer]');
                if (composer) {
                    var container = composer.closest('[class*="rounded-3xl"]');
                    if (container) {
                        var btns = container.querySelectorAll('button');
                        if (btns.length > 0) { btns[btns.length-1].click(); return 'clicked'; }
                    }
                }
                return 'no-button';
            })()
        """.trimIndent())

        if (result.getOrNull() == "clicked") return CdpResult.Success(true)

        // 降级：Enter 键
        Log.d(TAG, "未找到发送按钮，使用 Enter")
        cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
            addProperty("type", "keyDown"); addProperty("key", "Enter")
            addProperty("code", "Enter"); addProperty("windowsVirtualKeyCode", 13)
        })
        delay(50)
        cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
            addProperty("type", "keyUp"); addProperty("key", "Enter")
            addProperty("code", "Enter"); addProperty("windowsVirtualKeyCode", 13)
        })
        return CdpResult.Success(true)
    }

    // ─────────────────── 发送完整消息 ───────────────────

    suspend fun sendMessage(text: String): CdpResult<Unit> {
        focusInput().let { if (it is CdpResult.Error) return CdpResult.Error("聚焦失败: ${it.message}") }
        delay(100)
        setInputText(text).let { if (it is CdpResult.Error) return CdpResult.Error("输入失败: ${it.message}") }
        delay(200)
        clickSendButton().let { if (it is CdpResult.Error) return CdpResult.Error("发送失败: ${it.message}") }
        return CdpResult.Success(Unit)
    }

    // ─────────────────── 图片粘贴 ───────────────────

    suspend fun pasteImage(base64Data: String, mimeType: String = "image/png", fileName: String = "image.png"): CdpResult<Boolean> {
        focusInput().let { if (it is CdpResult.Error) return CdpResult.Error("聚焦失败: ${it.message}") }
        delay(100)

        val chunkSize = 50000
        val chunks = base64Data.chunked(chunkSize)
        cdp.evaluate("window.__pasteImageB64 = '';")
        for (chunk in chunks) {
            cdp.evaluate("window.__pasteImageB64 += '${chunk.replace("'", "\\'")}';")
        }

        val result = cdp.evaluate("""
            (function() {
                try {
                    var pm = document.querySelector('.ProseMirror[data-codex-composer="true"]');
                    if (!pm) pm = document.querySelector('.ProseMirror[contenteditable="true"]');
                    if (!pm) return 'no-input';
                    pm.focus();
                    var base64 = window.__pasteImageB64;
                    if (!base64) return 'no-data';
                    var binary = atob(base64);
                    var bytes = new Uint8Array(binary.length);
                    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
                    var blob = new Blob([bytes], {type: '$mimeType'});
                    var file = new File([blob], '$fileName', {type: '$mimeType', lastModified: Date.now()});
                    var dt = new DataTransfer();
                    dt.items.add(file);
                    try {
                        pm.dispatchEvent(new ClipboardEvent('paste', {clipboardData: dt, bubbles: true, cancelable: true}));
                    } catch(e1) {
                        pm.dispatchEvent(new DragEvent('drop', {dataTransfer: dt, bubbles: true, cancelable: true}));
                    }
                    window.__pasteImageB64 = null;
                    return 'ok';
                } catch(e) { window.__pasteImageB64 = null; return 'error:' + e.message; }
            })()
        """.trimIndent())

        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        val value = result.getOrNull() ?: ""
        return when {
            value == "ok" -> CdpResult.Success(true)
            value.startsWith("error:") -> CdpResult.Error("粘贴图片失败: ${value.removePrefix("error:")}")
            else -> CdpResult.Error("粘贴图片失败: $value")
        }
    }

    suspend fun sendImage(base64Data: String, mimeType: String = "image/png"): CdpResult<Unit> {
        pasteImage(base64Data, mimeType).let { if (it is CdpResult.Error) return CdpResult.Error(it.message) }
        delay(500)
        clickSendButton().let { if (it is CdpResult.Error) return CdpResult.Error("发送失败: ${it.message}") }
        return CdpResult.Success(Unit)
    }

    // ─────────────────── 获取最后回复 ───────────────────

    suspend fun getLastReply(): CdpResult<String> {
        val result = cdp.evaluate("""
            (function() {
                try {
                    // Codex 用 data-content-search-unit-key 标识消息角色
                    // 格式: "{uuid}:{idx}:assistant" 或 "{uuid}:{idx}:user"
                    var units = document.querySelectorAll('[data-content-search-unit-key]');
                    var lastAssistant = null;
                    for (var i = 0; i < units.length; i++) {
                        var key = units[i].getAttribute('data-content-search-unit-key') || '';
                        if (key.endsWith(':assistant')) lastAssistant = units[i];
                    }
                    if (lastAssistant) {
                        // 助手内容在 ._markdownContent_1rhk1_42 中
                        var md = lastAssistant.querySelector('[class*="_markdownContent_"]');
                        if (md) { var t = (md.innerText || '').trim(); if (t) return t; }
                        // 回退到整个元素
                        var t2 = (lastAssistant.innerText || '').trim();
                        if (t2) return t2;
                    }
                    return '';
                } catch(e) { return ''; }
            })()
        """.trimIndent())
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return CdpResult.Success(result.getOrNull() ?: "")
    }

    // ─────────────────── 获取聊天历史 ───────────────────

    suspend fun getChatHistory(): CdpResult<List<ChatMessage>> {
        val result = cdp.evaluate("""
            (function() {
                try {
                    var units = document.querySelectorAll('[data-content-search-unit-key]');
                    var items = [];
                    for (var i = 0; i < units.length; i++) {
                        var key = units[i].getAttribute('data-content-search-unit-key') || '';
                        var parts = key.split(':');
                        var role = parts[parts.length - 1];
                        if (role !== 'user' && role !== 'assistant') continue;
                        var text = '';
                        if (role === 'assistant') {
                            var md = units[i].querySelector('[class*="_markdownContent_"]');
                            text = md ? (md.innerText || '').trim() : (units[i].innerText || '').trim();
                        } else {
                            text = (units[i].innerText || '').trim();
                        }
                        if (text) items.push({role: role === 'user' ? 'USER' : 'ASSISTANT', content: text});
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

    suspend fun isGenerating(): Boolean {
        val result = cdp.evaluate("""
            (function() {
                // 1. 检查 stop/cancel 按钮
                var btns = document.querySelectorAll('button');
                for (var i = 0; i < btns.length; i++) {
                    if (!btns[i].offsetParent) continue;
                    var t = (btns[i].textContent || '').trim().toLowerCase();
                    var a = (btns[i].getAttribute('aria-label') || '').toLowerCase();
                    if (t === 'stop' || t === 'cancel' || a.includes('stop') || a.includes('cancel')) return true;
                }
                // 2. 检查 loading/spinner 动画
                var spinners = document.querySelectorAll('[class*="animate-spin"], [class*="loading"], [class*="spinner"]');
                for (var j = 0; j < spinners.length; j++) {
                    if (spinners[j].offsetParent) return true;
                }
                return false;
            })()
        """.trimIndent())
        return result.getOrNull()?.toBooleanStrictOrNull() ?: false
    }

    // ─────────────────── 自动放行 ───────────────────

    suspend fun autoAcceptActions(): Boolean {
        val result = cdp.evaluate("""
            (function() {
                var actionKeywords = ['run', 'allow', 'approve', 'continue', 'yes', 'always allow', 'allow once'];
                var btns = document.querySelectorAll('button, [role="button"]');
                for (var i = 0; i < btns.length; i++) {
                    if (!btns[i].offsetParent) continue;
                    var t = (btns[i].textContent || '').trim().toLowerCase();
                    if (t.length > 25 || t.indexOf('esc') >= 0 || t.indexOf('cancel') >= 0) continue;
                    for (var k = 0; k < actionKeywords.length; k++) {
                        if (t === actionKeywords[k] || (t.startsWith(actionKeywords[k]) && t.indexOf('(') >= 0)) {
                            btns[i].click();
                            var r = btns[i].getBoundingClientRect();
                            return JSON.stringify({found:true, x:r.x+r.width/2, y:r.y+r.height/2});
                        }
                    }
                }
                return JSON.stringify({found:false});
            })()
        """.trimIndent())

        val evalStr = result.getOrNull() ?: return false
        try {
            val json = JsonParser.parseString(evalStr).asJsonObject
            if (json.get("found")?.asBoolean == true) {
                val x = json.get("x").asDouble
                val y = json.get("y").asDouble
                cdp.dispatchMouseEvent("mouseMoved", x, y)
                delay(50)
                cdp.dispatchMouseEvent("mousePressed", x, y, "left", 1)
                delay(50)
                cdp.dispatchMouseEvent("mouseReleased", x, y, "left", 1)
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "autoAcceptActions 解析失败", e)
        }
        return false
    }

    // ─────────────────── Accept / Reject ───────────────────

    suspend fun acceptAll(): CdpResult<Boolean> = findAndClickButton("accept all", "accept")
    suspend fun rejectAll(): CdpResult<Boolean> = findAndClickButton("reject all", "reject")

    private suspend fun findAndClickButton(vararg keywords: String): CdpResult<Boolean> {
        val kwJson = keywords.joinToString(",") { "\"$it\"" }
        val result = cdp.evaluate("""
            (function() {
                var kw = [$kwJson];
                var btns = document.querySelectorAll('button, [role="button"]');
                for (var i = 0; i < btns.length; i++) {
                    var t = (btns[i].textContent || '').trim().toLowerCase();
                    for (var k = 0; k < kw.length; k++) {
                        if (t === kw[k]) {
                            var r = btns[i].getBoundingClientRect();
                            if (r.width > 0 && r.height > 0) {
                                return JSON.stringify({found:true, x:r.x+r.width/2, y:r.y+r.height/2});
                            }
                        }
                    }
                }
                return JSON.stringify({found:false});
            })()
        """.trimIndent())

        val evalStr = result.getOrNull() ?: return CdpResult.Error("评估失败")
        try {
            val json = JsonParser.parseString(evalStr).asJsonObject
            if (json.get("found")?.asBoolean == true) {
                val x = json.get("x").asDouble; val y = json.get("y").asDouble
                cdp.dispatchMouseEvent("mouseMoved", x, y)
                delay(50)
                cdp.dispatchMouseEvent("mousePressed", x, y, "left", 1)
                delay(50)
                cdp.dispatchMouseEvent("mouseReleased", x, y, "left", 1)
                return CdpResult.Success(true)
            }
        } catch (e: Exception) {
            return CdpResult.Error("解析失败: ${e.message}")
        }
        return CdpResult.Error("未找到按钮: ${keywords.joinToString()}")
    }

    // ─────────────────── 会话控制 ───────────────────

    suspend fun startNewSession(): CdpResult<Unit> {
        val result = cdp.evaluate("""
            (function() {
                // Codex: 侧栏 "New chat" 按钮
                var btns = document.querySelectorAll('a, button, [role="button"]');
                for (var i = 0; i < btns.length; i++) {
                    if (!btns[i].offsetParent) continue;
                    var aria = (btns[i].getAttribute('aria-label') || '').toLowerCase();
                    var text = (btns[i].textContent || '').trim().toLowerCase();
                    if (aria.includes('new chat') || text === 'new chat') {
                        btns[i].click(); return 'clicked';
                    }
                }
                return 'no-button';
            })()
        """.trimIndent())

        if (result.getOrNull() == "clicked") return CdpResult.Success(Unit)

        // 降级: Cmd+N (Codex 新建聊天快捷键)
        Log.d(TAG, "未找到新建按钮，使用 Cmd+N")
        cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
            addProperty("type", "keyDown"); addProperty("key", "n")
            addProperty("code", "KeyN"); addProperty("modifiers", 4)
            addProperty("windowsVirtualKeyCode", 78)
        })
        delay(50)
        cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
            addProperty("type", "keyUp"); addProperty("key", "n")
            addProperty("code", "KeyN"); addProperty("modifiers", 4)
            addProperty("windowsVirtualKeyCode", 78)
        })
        return CdpResult.Success(Unit)
    }

    suspend fun showRecentSessions(): CdpResult<Unit> {
        val result = cdp.evaluate("""
            (function() {
                var btns = document.querySelectorAll('a, button, [role="button"]');
                for (var i = 0; i < btns.length; i++) {
                    if (!btns[i].offsetParent) continue;
                    var aria = (btns[i].getAttribute('aria-label') || '').toLowerCase();
                    var text = (btns[i].textContent || '').trim().toLowerCase();
                    if (aria.includes('history') || text === 'history' ||
                        aria.includes('recent') || text.includes('recent sessions') ||
                        aria.includes('历史') || text === '历史记录') {
                        btns[i].click(); return 'clicked';
                    }
                }
                return 'no-button';
            })()
        """.trimIndent())

        if (result.getOrNull() == "clicked") return CdpResult.Success(Unit)
        return CdpResult.Error("未找到历史记录按钮")
    }

    suspend fun switchSession(isNext: Boolean): CdpResult<Unit> {
        val script = """
            (async function() {
                function getDocs(d) {
                    var out = [d];
                    var ifr = d.querySelectorAll('iframe');
                    for (var i = 0; i < ifr.length; i++) {
                        try { if (ifr[i].contentDocument) out = out.concat(getDocs(ifr[i].contentDocument)); } catch (e) {}
                    }
                    return out;
                }

                function getItems() {
                    var docs = getDocs(document);
                    var visibleItems = [];
                    for(var d=0; d<docs.length; d++) {
                        if (!docs[d]) continue;
                        var items = docs[d].querySelectorAll('[role="listitem"], [class*="history-item"], [class*="session-item"], .history-entry');
                        for(var i=0; i<items.length; i++) {
                            if(items[i].offsetParent) visibleItems.push(items[i]);
                        }
                    }
                    return visibleItems;
                }

                function findAndClickItem(items, isNext) {
                    var activeIdx = -1;
                    for(var i=0; i<items.length; i++) {
                        var el = items[i];
                        var aria = el.getAttribute('aria-selected');
                        var cls = (typeof el.className === 'string') ? el.className : '';
                        if(aria === 'true' || cls.includes('active') || cls.includes('selected') || cls.includes('current')) {
                            activeIdx = i; break;
                        }
                    }
                    if(activeIdx === -1) activeIdx = 0;
                    var targetIdx = isNext ? activeIdx + 1 : activeIdx - 1;
                    if(targetIdx < 0) targetIdx = items.length - 1;
                    if(targetIdx >= items.length) targetIdx = 0;

                    items[targetIdx].click();
                    return true;
                }

                var vItems = getItems();
                if (vItems.length > 1) {
                    findAndClickItem(vItems, ${isNext});
                    return 'clicked';
                }

                var docs = getDocs(document);
                var btns = [];
                for(var di=0; di<docs.length; di++) {
                    var bs = docs[di].querySelectorAll('a, button, [role="button"]');
                    for(var i=0; i<bs.length; i++) btns.push(bs[i]);
                }

                var historyBtn = null;
                for (var i = 0; i < btns.length; i++) {
                    if (!btns[i].offsetParent) continue;
                    var aria = (btns[i].getAttribute('aria-label') || '').toLowerCase();
                    var text = (btns[i].textContent || '').trim().toLowerCase();
                    if (aria.includes('history') || text === 'history' ||
                        aria.includes('recent') || text.includes('recent sessions') ||
                        aria.includes('历史') || text === '历史记录') {
                        historyBtn = btns[i]; break;
                    }
                }

                if (historyBtn) {
                    historyBtn.click();
                    await new Promise(r => setTimeout(r, 600));

                    var vItems2 = getItems();
                    if (vItems2.length > 1) {
                        findAndClickItem(vItems2, ${isNext});
                        return 'clicked';
                    }
                }

                return 'no-items';
            })()
        """.trimIndent()

        val result = cdp.evaluate(script, awaitPromise = true)
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return when (result.getOrNull()) {
            "clicked" -> CdpResult.Success(Unit)
            else -> CdpResult.Error("未找到可切换的历史会话")
        }
    }

    /**
     * 获取**当前项目**下的会话列表（侧边栏中该项目展开后的 chat 列表）。
     * Codex 侧边栏结构:
     *   <div role="listitem" aria-label="{project}">
     *     <div role="button" aria-label="{project}">…</div>      <- 项目头
     *     <div>                                                  <- 容器
     *       <div role="listitem">                                <- chat
     *         <div role="button">…title…</div>
     *       </div>
     *       …
     *     </div>
     *   </div>
     * 当前选中的 chat 带 aria-current="page"。
     */
    suspend fun getRecentSessionsList(): CdpResult<List<String>> {
        ensureSidebarOpen()
        val script = """
            (function() {
                try {
                    // 1. 找到当前项目对应的 listitem (优先用 aria-current 定位; 否则取第一个项目)
                    var current = document.querySelector('[role="listitem"][aria-label] [aria-current="page"]');
                    var projectLi = null;
                    if (current) {
                        projectLi = current.closest('[role="listitem"][aria-label]');
                    }
                    if (!projectLi) {
                        var lis = document.querySelectorAll('[role="listitem"][aria-label]');
                        for (var i = 0; i < lis.length; i++) {
                            // 项目级 listitem: 其内部有 [role="button"][aria-expanded][aria-label] 与本身同名
                            var name = lis[i].getAttribute('aria-label') || '';
                            var hdr = lis[i].querySelector('[role="button"][aria-expanded][aria-label="' + name.replace(/"/g,'\\"') + '"]');
                            if (hdr && lis[i].offsetParent) { projectLi = lis[i]; break; }
                        }
                    }
                    if (!projectLi) return JSON.stringify({status:'no-project'});

                    var projectName = projectLi.getAttribute('aria-label') || '';
                    var sessions = [];
                    // chat = 内部 role=listitem 但 aria-label 与项目名不同（即非项目本身）
                    var chats = projectLi.querySelectorAll('[role="listitem"]');
                    for (var j = 0; j < chats.length; j++) {
                        if (chats[j] === projectLi) continue;
                        var aria = chats[j].getAttribute('aria-label') || '';
                        if (aria === projectName) continue;
                        // 标题: 取第一个 .truncate 的纯文本（避开尾部时间标签）
                        var titleEl = chats[j].querySelector('.truncate, [class*="truncate"]');
                        var text = titleEl ? (titleEl.textContent || '').trim()
                                           : (chats[j].textContent || '').trim();
                        text = text.replace(/\s+/g, ' ');
                        if (text.length > 60) text = text.substring(0, 60) + '...';
                        if (text) sessions.push(text);
                    }
                    return JSON.stringify({status:'found', project: projectName, sessions: sessions});
                } catch(e) {
                    return JSON.stringify({status:'error', error: e.message});
                }
            })()
        """.trimIndent()

        val result = cdp.evaluate(script)
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        val jsonStr = result.getOrNull() ?: return CdpResult.Error("无返回结果")
        return try {
            val root = com.google.gson.JsonParser.parseString(jsonStr).asJsonObject
            when (root.get("status")?.asString) {
                "found" -> {
                    val list = root.getAsJsonArray("sessions").map { it.asString }
                    if (list.isEmpty()) CdpResult.Error("当前项目暂无会话")
                    else CdpResult.Success(list)
                }
                "no-project" -> CdpResult.Error("未找到当前项目")
                else -> CdpResult.Error("未找到会话列表")
            }
        } catch (e: Exception) {
            CdpResult.Error("解析会话列表失败")
        }
    }

    suspend fun switchSessionByIndex(index: Int): CdpResult<Unit> {
        ensureSidebarOpen()
        val script = """
            (function() {
                try {
                    var current = document.querySelector('[role="listitem"][aria-label] [aria-current="page"]');
                    var projectLi = current ? current.closest('[role="listitem"][aria-label]') : null;
                    if (!projectLi) {
                        var lis = document.querySelectorAll('[role="listitem"][aria-label]');
                        for (var i = 0; i < lis.length; i++) {
                            var name = lis[i].getAttribute('aria-label') || '';
                            var hdr = lis[i].querySelector('[role="button"][aria-expanded][aria-label="' + name.replace(/"/g,'\\"') + '"]');
                            if (hdr && lis[i].offsetParent) { projectLi = lis[i]; break; }
                        }
                    }
                    if (!projectLi) return 'no-project';

                    var projectName = projectLi.getAttribute('aria-label') || '';
                    var chats = [];
                    var all = projectLi.querySelectorAll('[role="listitem"]');
                    for (var j = 0; j < all.length; j++) {
                        if (all[j] === projectLi) continue;
                        var aria = all[j].getAttribute('aria-label') || '';
                        if (aria === projectName) continue;
                        chats.push(all[j]);
                    }
                    if (chats.length <= $index) return 'out-of-range';

                    var target = chats[$index];
                    var btn = target.querySelector('[role="button"]') || target;
                    btn.click();
                    return 'clicked';
                } catch(e) { return 'error:' + e.message; }
            })()
        """.trimIndent()

        val result = cdp.evaluate(script)
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return when (val v = result.getOrNull()) {
            "clicked" -> CdpResult.Success(Unit)
            "no-project" -> CdpResult.Error("未找到当前项目")
            "out-of-range" -> CdpResult.Error("索引超出当前项目会话数")
            else -> CdpResult.Error("切换失败: $v")
        }
    }

    suspend fun stopGeneration(): CdpResult<Unit> {
        cdp.evaluate("""
            (function() {
                var btns = document.querySelectorAll('button');
                for (var i = 0; i < btns.length; i++) {
                    if (!btns[i].offsetParent) continue;
                    var t = (btns[i].textContent || '').trim().toLowerCase();
                    var a = (btns[i].getAttribute('aria-label') || '').toLowerCase();
                    if (t === 'stop' || t === 'cancel' || a.includes('stop') || a.includes('cancel')) {
                        btns[i].click(); return 'clicked';
                    }
                }
                return 'no-button';
            })()
        """.trimIndent())
        return CdpResult.Success(Unit)
    }

    // ─────────────────── 项目管理 ───────────────────
    // Codex 侧边栏结构:
    //   项目 = [role="button"][aria-expanded][aria-label="{name}"]
    //   聊天 = 项目 parent 下的 .truncate.select-none span
    //   当前项目判定:
    //     1. 顶部标题栏的项目名(有聊天时)
    //     2. composer 底部的项目选择器(新聊天时)
    //     3. 侧边栏 aria-expanded + 聊天项高亮
    //
    // 注意: 当 Codex 窗口较窄或用户折叠了侧边栏时，
    // 项目相关 DOM 节点会被卸载，必须先点击 "Show sidebar"。

    /**
     * 确保侧边栏已展开（项目 DOM 仅在侧边栏打开时存在）。
     * 如果当前是 "Show sidebar" 状态则点击展开并等待 DOM 就绪。
     */
    private suspend fun ensureSidebarOpen() {
        val state = cdp.evaluate("""
            (function() {
                // 已展开的判据：能找到 "Start new chat in ..." 按钮，
                // 或存在 aria-label="Hide sidebar" 的按钮
                if (document.querySelector('button[aria-label^="Start new chat in "]')) return 'open';
                if (document.querySelector('button[aria-label="Hide sidebar"]')) return 'open';
                var show = document.querySelector('button[aria-label="Show sidebar"]');
                if (show && show.offsetParent) { show.click(); return 'opened'; }
                return 'unknown';
            })()
        """.trimIndent()).getOrNull() ?: ""
        if (state == "opened") delay(500)
    }

    /**
     * 获取 Codex 侧边栏的项目列表
     * 返回 [{name, isCurrent, isExpanded}] 数组
     */
    suspend fun listProjects(): CdpResult<List<CodexProject>> {
        ensureSidebarOpen()
        val result = cdp.evaluate("""
            (function() {
                try {
                    var projects = [];
                    // 从 "Start new chat in {name}" 按钮提取项目名
                    var btns = document.querySelectorAll('button[aria-label^="Start new chat in "]');
                    for (var i = 0; i < btns.length; i++) {
                        var aria = btns[i].getAttribute('aria-label') || '';
                        var name = aria.replace('Start new chat in ', '');
                        if (name) projects.push(name);
                    }

                    // 判定当前活跃项目:
                    // 方法1: 顶部栏有项目名的链接/按钮
                    var topBtns = document.querySelectorAll('button[type="button"]');
                    var current = '';
                    for (var j = 0; j < topBtns.length; j++) {
                        var r = topBtns[j].getBoundingClientRect();
                        // 顶部栏区域(y<30)、非工具按钮(aria-label为空)、宽度适中
                        if (r.y > 0 && r.y < 30 && r.height < 40) {
                            var t = (topBtns[j].textContent || '').trim();
                            var a = topBtns[j].getAttribute('aria-label') || '';
                            if (t && !a && projects.indexOf(t) >= 0) {
                                current = t; break;
                            }
                        }
                    }

                    // 方法2: 如果顶部栏没有(新聊天界面)，用 composer 下方的项目选择器
                    if (!current) {
                        var allBtns = document.querySelectorAll('button');
                        for (var k = 0; k < allBtns.length; k++) {
                            var r2 = allBtns[k].getBoundingClientRect();
                            if (r2.y > 800 && r2.y < 870) {
                                var t2 = (allBtns[k].textContent || '').trim();
                                if (projects.indexOf(t2) >= 0) {
                                    current = t2; break;
                                }
                            }
                        }
                    }

                    // 各项目 expanded 状态
                    var expanded = {};
                    var roleBtns = document.querySelectorAll('[role="button"][aria-expanded]');
                    for (var m = 0; m < roleBtns.length; m++) {
                        var a2 = (roleBtns[m].getAttribute('aria-label') || '');
                        if (a2) expanded[a2] = roleBtns[m].getAttribute('aria-expanded') === 'true';
                    }

                    return JSON.stringify({projects: projects, current: current, expanded: expanded});
                } catch(e) { return JSON.stringify({projects: [], current: '', expanded: {}, error: e.message}); }
            })()
        """.trimIndent())
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return try {
            val json = com.google.gson.JsonParser.parseString(result.getOrNull() ?: "{}").asJsonObject
            val names = json.getAsJsonArray("projects")?.map { it.asString } ?: emptyList()
            val current = json.get("current")?.asString ?: ""
            val expanded = json.getAsJsonObject("expanded")
            val list = names.map { name ->
                CodexProject(
                    name = name,
                    isCurrent = name == current,
                    isExpanded = expanded?.get(name)?.asBoolean ?: false
                )
            }
            CdpResult.Success(list)
        } catch (e: Exception) {
            CdpResult.Error("解析项目列表失败: ${e.message}")
        }
    }

    /**
     * 获取当前活跃的项目名
     * 三层检测: 顶部标题栏 → composer 下方选择器 → 侧边栏展开项
     */
    suspend fun getCurrentProject(): CdpResult<String> {
        ensureSidebarOpen()
        val result = cdp.evaluate("""
            (function() {
                // 先获取项目名列表
                var projects = [];
                var btns = document.querySelectorAll('button[aria-label^="Start new chat in "]');
                for (var i = 0; i < btns.length; i++) {
                    var name = (btns[i].getAttribute('aria-label') || '').replace('Start new chat in ', '');
                    if (name) projects.push(name);
                }

                // 1. 顶部标题栏(有聊天时显示聊天标题旁的项目名)
                var topBtns = document.querySelectorAll('button[type="button"]');
                for (var j = 0; j < topBtns.length; j++) {
                    var r = topBtns[j].getBoundingClientRect();
                    if (r.y > 0 && r.y < 30 && r.height < 40) {
                        var t = (topBtns[j].textContent || '').trim();
                        var aria = topBtns[j].getAttribute('aria-label') || '';
                        // 排除工具按钮(有 aria-label 的如 Commit, Back 等)
                        if (t && !aria && projects.indexOf(t) >= 0) return t;
                    }
                }

                // 2. composer 下方项目选择器(新聊天界面)
                var allBtns = document.querySelectorAll('button');
                for (var k = 0; k < allBtns.length; k++) {
                    var r2 = allBtns[k].getBoundingClientRect();
                    if (r2.y > 800 && r2.y < 870) {
                        var t2 = (allBtns[k].textContent || '').trim();
                        if (projects.indexOf(t2) >= 0) return t2;
                    }
                }

                // 3. 侧边栏展开的项目(有聊天项目可见)
                var roleBtns = document.querySelectorAll('[role="button"][aria-expanded="true"]');
                for (var m = 0; m < roleBtns.length; m++) {
                    var a = (roleBtns[m].getAttribute('aria-label') || '');
                    if (a && projects.indexOf(a) >= 0) return a;
                }

                return '';
            })()
        """.trimIndent())
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        val name = result.getOrNull() ?: ""
        return if (name.isNotEmpty()) CdpResult.Success(name) else CdpResult.Error("无法获取当前项目")
    }

    /**
     * 切换到指定项目:
     * 1. 先确保项目展开(aria-expanded=true)
     * 2. 然后点击该项目下第一个聊天(真正切换视图)
     * 如果项目没有聊天，则新建一个
     */
    suspend fun switchProject(projectName: String): CdpResult<Unit> {
        if (projectName.isBlank()) return CdpResult.Error("项目名不能为空")
        ensureSidebarOpen()
        val projectLiteral = com.google.gson.JsonPrimitive(projectName).toString()
        val result = cdp.evaluate("""
            (async function() {
                var projectName = $projectLiteral;
                // 找到项目的 role=button 元素
                var roleBtns = document.querySelectorAll('[role="button"][aria-expanded]');
                var projectEl = null;
                for (var i = 0; i < roleBtns.length; i++) {
                    if ((roleBtns[i].getAttribute('aria-label') || '') === projectName) {
                        projectEl = roleBtns[i]; break;
                    }
                }
                if (!projectEl) return 'not-found';

                // 先展开项目(如果折叠)
                if (projectEl.getAttribute('aria-expanded') !== 'true') {
                    projectEl.click();
                    await new Promise(function(resolve) { setTimeout(resolve, 300); });
                }

                // 然后查找该项目下的第一个聊天并点击
                var parent = projectEl.parentElement;
                if (!parent) return 'expanded-only';

                // 等待展开后查找聊天项(需要点击后 DOM 可能异步更新)
                var chatEls = parent.querySelectorAll('[role="button"]');
                for (var j = 0; j < chatEls.length; j++) {
                    var el = chatEls[j];
                    if (el === projectEl) continue;
                    // 聊天项有 .truncate.select-none 子元素
                    if (el.querySelector('.truncate.select-none')) {
                        el.click();
                        return 'switched';
                    }
                }

                // 没有聊天，点击 "Start new chat in {name}"
                var newChatLabel = 'Start new chat in ' + projectName;
                var buttons = document.querySelectorAll('button');
                for (var k = 0; k < buttons.length; k++) {
                    if ((buttons[k].getAttribute('aria-label') || '') === newChatLabel) {
                        buttons[k].click();
                        return 'new-chat';
                    }
                }
                return 'expanded-only';
            })()
        """.trimIndent(), awaitPromise = true)
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return when (result.getOrNull()) {
            "switched", "new-chat", "expanded-only" -> CdpResult.Success(Unit)
            else -> CdpResult.Error("未找到项目: $projectName")
        }
    }

    /**
     * 在指定项目内新建聊天 - 点击 "Start new chat in {name}" 按钮
     */
    suspend fun startNewChatInProject(projectName: String): CdpResult<Unit> {
        if (projectName.isBlank()) return CdpResult.Error("项目名不能为空")
        ensureSidebarOpen()
        val projectLiteral = com.google.gson.JsonPrimitive(projectName).toString()
        val result = cdp.evaluate("""
            (function() {
                var projectName = $projectLiteral;
                var newChatLabel = 'Start new chat in ' + projectName;
                var buttons = document.querySelectorAll('button');
                for (var i = 0; i < buttons.length; i++) {
                    if ((buttons[i].getAttribute('aria-label') || '') === newChatLabel) {
                        buttons[i].click();
                        return 'clicked';
                    }
                }
                return 'not-found';
            })()
        """.trimIndent())
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return when (result.getOrNull()) {
            "clicked" -> CdpResult.Success(Unit)
            else -> CdpResult.Error("未找到项目: $projectName")
        }
    }

    /**
     * 通过绝对路径添加新项目到 Codex 侧边栏
     * 绕过原生文件选择器 (手机上不可见), 直接通过 Codex IPC 注入
     */
    suspend fun addProject(absolutePath: String): CdpResult<Unit> {
        if (absolutePath.isBlank()) return CdpResult.Error("路径不能为空")
        val pathLiteral = com.google.gson.JsonPrimitive(absolutePath).toString()
        val result = cdp.evaluate("""
            (function() {
                var rootPath = $pathLiteral;
                try {
                    return window.electronBridge.sendMessageFromView({
                        type: 'electron-add-new-workspace-root-option',
                        root: rootPath
                    }).then(function() { return 'ok'; })
                     .catch(function(e) { return 'error:' + e.message; });
                } catch(e) { return 'error:' + e.message; }
            })()
        """.trimIndent(), awaitPromise = true)
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        val v = result.getOrNull() ?: ""
        return if (v == "ok") CdpResult.Success(Unit)
               else CdpResult.Error("添加项目失败: $v")
    }

    /**
     * 点击 "Add new project" 按钮打开添加项目对话框
     * 注意: 这会触发系统原生文件选择器(CDP 不可控)
     */
    suspend fun addNewProject(): CdpResult<Unit> {
        ensureSidebarOpen()
        val result = cdp.evaluate("""
            (function() {
                var btn = document.querySelector('button[aria-label="Add new project"]');
                if (btn) { btn.click(); return 'clicked'; }
                return 'not-found';
            })()
        """.trimIndent())
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return when (result.getOrNull()) {
            "clicked" -> CdpResult.Success(Unit)
            else -> CdpResult.Error("未找到添加项目按钮")
        }
    }

    /**
     * 获取指定项目下的聊天列表
     */
    suspend fun getProjectChats(projectName: String): CdpResult<List<String>> {
        if (projectName.isBlank()) return CdpResult.Success(emptyList())
        ensureSidebarOpen()
        val projectLiteral = com.google.gson.JsonPrimitive(projectName).toString()
        val result = cdp.evaluate("""
            (function() {
                var projectName = $projectLiteral;
                try {
                    var roleBtns = document.querySelectorAll('[role="button"][aria-expanded]');
                    var projectEl = null;
                    for (var i = 0; i < roleBtns.length; i++) {
                        if ((roleBtns[i].getAttribute('aria-label') || '') === projectName) {
                            projectEl = roleBtns[i]; break;
                        }
                    }
                    if (!projectEl) return JSON.stringify([]);
                    var parent = projectEl.parentElement;
                    if (!parent) return JSON.stringify([]);
                    var chats = [];
                    var spans = parent.querySelectorAll('.truncate.select-none');
                    for (var j = 0; j < spans.length; j++) {
                        var t = (spans[j].textContent || '').trim();
                        if (t) chats.push(t);
                    }
                    return JSON.stringify(chats);
                } catch(e) { return JSON.stringify([]); }
            })()
        """.trimIndent())
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return try {
            val arr = com.google.gson.JsonParser.parseString(result.getOrNull() ?: "[]").asJsonArray
            CdpResult.Success(arr.map { it.asString })
        } catch (e: Exception) {
            CdpResult.Error("解析聊天列表失败: ${e.message}")
        }
    }

    // ─────────────────── 模型切换 ───────────────────
    // Codex 使用 Radix UI，JS .click() 无法触发菜单。
    // 必须通过 CDP Input.dispatchMouseEvent 模拟真实鼠标事件。

    /** CDP 鼠标点击 */
    private suspend fun cdpMouseClick(x: Double, y: Double) {
        val press = JsonObject().apply {
            addProperty("type", "mousePressed"); addProperty("x", x); addProperty("y", y)
            addProperty("button", "left"); addProperty("clickCount", 1)
        }
        cdp.call("Input.dispatchMouseEvent", press)
        val release = JsonObject().apply {
            addProperty("type", "mouseReleased"); addProperty("x", x); addProperty("y", y)
            addProperty("button", "left"); addProperty("clickCount", 1)
        }
        cdp.call("Input.dispatchMouseEvent", release)
    }

    /** CDP 鼠标移动(hover) */
    private suspend fun cdpMouseMove(x: Double, y: Double) {
        val move = JsonObject().apply {
            addProperty("type", "mouseMoved"); addProperty("x", x); addProperty("y", y)
        }
        cdp.call("Input.dispatchMouseEvent", move)
    }

    /** CDP 按键(Escape) */
    private suspend fun cdpEscape() {
        cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
            addProperty("type", "keyDown"); addProperty("key", "Escape")
        })
        cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
            addProperty("type", "keyUp"); addProperty("key", "Escape")
        })
    }

    /** 获取模型按钮的中心坐标 */
    private suspend fun getModelBtnCenter(): Pair<Double, Double>? {
        val r = cdp.evaluate("""
            (function(){
                var btns = document.querySelectorAll('button[aria-haspopup="menu"]');
                for (var i = 0; i < btns.length; i++) {
                    if (!btns[i].offsetParent) continue;
                    var t = (btns[i].textContent || '').toLowerCase();
                    if (t.indexOf('5.') >= 0 || t.indexOf('gpt') >= 0 || t.indexOf('high') >= 0 || t.indexOf('low') >= 0 || t.indexOf('medium') >= 0) {
                        var r = btns[i].getBoundingClientRect();
                        return r.x + r.width/2 + ',' + (r.y + r.height/2);
                    }
                }
                return '';
            })()
        """.trimIndent())
        val v = r.getOrNull() ?: return null
        val parts = v.split(",")
        if (parts.size != 2) return null
        val x = parts[0].toDoubleOrNull() ?: return null
        val y = parts[1].toDoubleOrNull() ?: return null
        return Pair(x, y)
    }

    suspend fun getCurrentModel(): CdpResult<String> {
        val result = cdp.evaluate("""
            (function() {
                var btns = document.querySelectorAll('button[aria-haspopup="menu"]');
                for (var i = 0; i < btns.length; i++) {
                    if (!btns[i].offsetParent) continue;
                    var t = (btns[i].textContent || '').toLowerCase();
                    if (t.indexOf('5.') >= 0 || t.indexOf('gpt') >= 0 || t.indexOf('high') >= 0 || t.indexOf('low') >= 0 || t.indexOf('medium') >= 0) {
                        return btns[i].textContent.replace(/\s+/g, ' ').trim();
                    }
                }
                return '';
            })()
        """.trimIndent())
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        val model = result.getOrNull() ?: ""
        return if (model.isNotEmpty()) CdpResult.Success(model) else CdpResult.Error("无法获取当前模型")
    }

    /**
     * 打开模型菜单并读取选项。使用 CDP 鼠标事件打开 Radix UI 三级菜单。
     * 菜单结构: 主菜单(Intelligence等级) → hover当前模型 → 子菜单(Change model: GPT-5.5/GPT-5.4/Other models)
     */
    suspend fun listModelOptions(): CdpResult<List<String>> {
        val btnCenter = getModelBtnCenter() ?: return CdpResult.Error("找不到模型按钮")
        // 1. CDP 点击打开主菜单
        cdpMouseClick(btnCenter.first, btnCenter.second)
        delay(600)
        // 2. 在主菜单中找当前模型项(GPT-x.x)并 hover 展开子菜单
        val modelItemPos = cdp.evaluate("""
            (function(){
                var menus = document.querySelectorAll('[role=menu]');
                for (var i = 0; i < menus.length; i++) {
                    var spans = menus[i].querySelectorAll('span');
                    for (var j = 0; j < spans.length; j++) {
                        var t = (spans[j].textContent||'').trim();
                        if (t.match(/^GPT-\d/) || t.match(/^gpt-\d/i) || t.match(/^o\d-/i) || t.match(/^codex/i)) {
                            var r = spans[j].getBoundingClientRect();
                            return (r.x+r.width/2)+','+(r.y+r.height/2);
                        }
                    }
                }
                return '';
            })()
        """.trimIndent()).getOrNull() ?: ""

        val models = mutableListOf<String>()
        if (modelItemPos.contains(",")) {
            val (mx, my) = modelItemPos.split(",").map { it.toDouble() }
            cdpMouseMove(mx, my)
            delay(500)
            // 3. 读子菜单中的模型列表
            val subItems = cdp.evaluate("""
                (function(){
                    var menus = document.querySelectorAll('[role=menu]');
                    if (menus.length < 2) return '';
                    var sub = menus[menus.length-1];
                    var out = [];
                    var spans = sub.querySelectorAll('span');
                    for (var i = 0; i < spans.length; i++) {
                        var t = (spans[i].textContent||'').trim();
                        if (t.length > 2 && t.length < 40 && t !== 'Change model' && t !== 'Other models') out.push(t);
                    }
                    return out.join('|');
                })()
            """.trimIndent()).getOrNull() ?: ""
            if (subItems.isNotEmpty()) models.addAll(subItems.split("|"))
        }
        // 4. 也加入 Intelligence 等级
        val levels = cdp.evaluate("""
            (function(){
                var menus = document.querySelectorAll('[role=menu]');
                if (menus.length < 1) return '';
                var first = menus[0];
                var out = [];
                var spans = first.querySelectorAll('span');
                for (var i = 0; i < spans.length; i++) {
                    var t = (spans[i].textContent||'').trim();
                    if (['Low','Medium','High','Extra High','Speed'].indexOf(t) >= 0) out.push(t);
                }
                return out.join('|');
            })()
        """.trimIndent()).getOrNull() ?: ""
        if (levels.isNotEmpty()) models.addAll(levels.split("|"))
        // 5. 关闭菜单
        cdpEscape(); delay(100); cdpEscape()
        return CdpResult.Success(models)
    }

    /**
     * 切换模型 — 通过 CDP 鼠标事件操作 Radix UI 三级菜单。
     * 支持切换模型(GPT-5.5/5.4)和 Intelligence 等级(Low/Medium/High/Extra High/Speed)。
     */
    suspend fun switchModel(modelName: String): CdpResult<Unit> {
        val input = modelName.lowercase().trim()
        val inputLiteral = com.google.gson.JsonPrimitive(input).toString()
        Log.d(TAG, "switchModel: $input")
        val btnCenter = getModelBtnCenter() ?: return CdpResult.Error("找不到模型按钮")

        // 1. CDP 点击打开主菜单
        cdpMouseClick(btnCenter.first, btnCenter.second)
        delay(600)

        // 2. 检查是切换 Intelligence 等级，还是 Speed/模型
        val isLevel = input in listOf("low", "medium", "high", "extra high")
        val isSpeed = input in listOf("fast", "standard")

        if (isLevel) {
            // 在主菜单中直接找等级项并点击
            val pos = cdp.evaluate("""
                (function(){
                    var input = $inputLiteral;
                    var menus = document.querySelectorAll('[role=menu]');
                    if (!menus.length) return '';
                    var spans = menus[0].querySelectorAll('span');
                    for (var i = 0; i < spans.length; i++) {
                        if ((spans[i].textContent||'').trim().toLowerCase() === input) {
                            var r = spans[i].getBoundingClientRect();
                            return (r.x+r.width/2)+','+(r.y+r.height/2);
                        }
                    }
                    return '';
                })()
            """.trimIndent()).getOrNull() ?: ""
            if (pos.contains(",")) {
                val (x, y) = pos.split(",").map { it.toDouble() }
                cdpMouseClick(x, y)
                delay(200)
                Log.d(TAG, "切换 Intelligence 等级: $input")
                return CdpResult.Success(Unit)
            }
            cdpEscape()
            return CdpResult.Error("找不到等级: $input")
        }

        // 3. 切换模型或速度：hover 对应的菜单项（模型名或 "Speed"）以展开子菜单
        val hoverTargetCode = if (isSpeed) {
            """
            for (var j = 0; j < spans.length; j++) {
                if ((spans[j].textContent||'').trim().toLowerCase() === 'speed') {
                    var r = spans[j].getBoundingClientRect();
                    return (r.x+r.width/2)+','+(r.y+r.height/2);
                }
            }
            """
        } else {
            """
            for (var j = 0; j < spans.length; j++) {
                var t = (spans[j].textContent||'').trim();
                if (t.match(/^GPT-\d/) || t.match(/^gpt-\d/i) || t.match(/^o\d-/i) || t.match(/^codex/i)) {
                    var r = spans[j].getBoundingClientRect();
                    return (r.x+r.width/2)+','+(r.y+r.height/2);
                }
            }
            """
        }

        val hoverItemPos = cdp.evaluate("""
            (function(){
                var menus = document.querySelectorAll('[role=menu]');
                for (var i = 0; i < menus.length; i++) {
                    var spans = menus[i].querySelectorAll('span');
                    $hoverTargetCode
                }
                return '';
            })()
        """.trimIndent()).getOrNull() ?: ""

        if (!hoverItemPos.contains(",")) {
            cdpEscape()
            return CdpResult.Error("主菜单中找不到 Hover 目标项")
        }
        val (mx, my) = hoverItemPos.split(",").map { it.toDouble() }
        cdpMouseMove(mx, my)
        delay(600) // 等待子菜单动画展开

        // 4. 在子菜单中找目标模型
        val targetPos = cdp.evaluate("""
            (function(){
                var input = $inputLiteral;
                var menus = document.querySelectorAll('[role=menu]');
                if (menus.length < 2) return JSON.stringify({err:'子菜单未展开',count:menus.length});
                var sub = menus[menus.length-1];
                var spans = sub.querySelectorAll('span');
                for (var i = 0; i < spans.length; i++) {
                    var t = (spans[i].textContent||'').trim().toLowerCase();
                    if (t.indexOf(input) >= 0 && t !== 'change model' && t !== 'other models') {
                        var r = spans[i].getBoundingClientRect();
                        return JSON.stringify({ok:true, x:r.x+r.width/2, y:r.y+r.height/2, text:t});
                    }
                }
                // 列出可用选项做诊断
                var avail = [];
                for (var i = 0; i < spans.length; i++) {
                    var t = (spans[i].textContent||'').trim();
                    if (t.length > 1 && t !== 'Change model') avail.push(t);
                }
                return JSON.stringify({err:'子菜单中无匹配', avail:avail});
            })()
        """.trimIndent()).getOrNull() ?: ""

        return try {
            val json = JsonParser.parseString(targetPos).asJsonObject
            if (json.has("ok")) {
                val tx = json.get("x").asDouble
                val ty = json.get("y").asDouble
                cdpMouseClick(tx, ty)
                delay(200)
                Log.d(TAG, "模型切换成功: ${json.get("text")?.asString}")
                CdpResult.Success(Unit)
            } else {
                val err = json.get("err")?.asString ?: "未知错误"
                val avail = json.getAsJsonArray("avail")?.joinToString { it.asString } ?: ""
                Log.e(TAG, "模型切换失败: $err | 可选: $avail")
                cdpEscape(); delay(100); cdpEscape()
                CdpResult.Error("$err (可选: $avail)")
            }
        } catch (e: Exception) {
            cdpEscape()
            CdpResult.Error("解析失败: $targetPos")
        }
    }

    // ─────────────────── 自动重试 ───────────────────

    suspend fun checkAndRetryIfBusy(): CdpResult<Boolean> {
        val result = cdp.evaluate("""
            (function() {
                var errorPatterns = ['server is busy','rate limit','overloaded','too many requests',
                    'something went wrong','an error occurred','internal error','service unavailable',
                    'terminated due to error','agent terminated','服务器繁忙','出现错误'];
                var bodyText = (document.body.innerText || '').toLowerCase();
                var hasError = false;
                for (var i = 0; i < errorPatterns.length; i++) {
                    if (bodyText.includes(errorPatterns[i])) { hasError = true; break; }
                }
                if (!hasError) return JSON.stringify({status:'ok'});
                var retryKw = ['retry','重试','try again','regenerate','resend'];
                var btns = document.querySelectorAll('button');
                for (var j = 0; j < btns.length; j++) {
                    if (!btns[j].offsetParent) continue;
                    var t = (btns[j].textContent || '').toLowerCase().trim();
                    for (var k = 0; k < retryKw.length; k++) {
                        if (t.includes(retryKw[k])) {
                            btns[j].click();
                            var r = btns[j].getBoundingClientRect();
                            return JSON.stringify({status:'retried', x:r.x+r.width/2, y:r.y+r.height/2});
                        }
                    }
                }
                return JSON.stringify({status:'error-no-retry-button'});
            })()
        """.trimIndent())

        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        val evalStr = result.getOrNull() ?: return CdpResult.Success(false)
        return try {
            val json = JsonParser.parseString(evalStr).asJsonObject
            when (json.get("status")?.asString) {
                "retried" -> {
                    if (json.has("x") && json.has("y")) {
                        val x = json.get("x").asDouble; val y = json.get("y").asDouble
                        cdp.dispatchMouseEvent("mouseMoved", x, y); delay(50)
                        cdp.dispatchMouseEvent("mousePressed", x, y, "left", 1); delay(50)
                        cdp.dispatchMouseEvent("mouseReleased", x, y, "left", 1)
                    }
                    CdpResult.Success(true)
                }
                "error-no-retry-button" -> CdpResult.Error("检测到错误但找不到重试按钮")
                else -> CdpResult.Success(false)
            }
        } catch (e: Exception) {
            CdpResult.Error("解析重试结果失败: ${e.message}")
        }
    }
}
