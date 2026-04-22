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

    suspend fun getRecentSessionsList(): CdpResult<List<String>> {
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

                function extractTitles(items) {
                    var res = [];
                    for(var i=0; i<items.length; i++) {
                        var el = items[i];
                        var text = el.innerText || el.textContent;
                        text = text.replace(/\n/g, ' ').replace(/\r/g, '').trim();
                        if (text.length > 50) text = text.substring(0, 50) + '...';
                        res.push(text);
                    }
                    return res;
                }
                
                var vItems = getItems();
                if (vItems.length > 0) {
                    return JSON.stringify({status: 'found', sessions: extractTitles(vItems)});
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
                    if (vItems2.length > 0) {
                        return JSON.stringify({status: 'found', sessions: extractTitles(vItems2)});
                    }
                }
                
                return JSON.stringify({status: 'no-items'});
            })()
        """.trimIndent()
        
        val result = cdp.evaluate(script, awaitPromise = true)
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        val jsonStr = result.getOrNull() ?: return CdpResult.Error("无返回结果")
        return try {
            val root = com.google.gson.JsonParser.parseString(jsonStr).asJsonObject
            if (root.get("status")?.asString == "found") {
                val sessionsArray = root.getAsJsonArray("sessions")
                val list = mutableListOf<String>()
                sessionsArray.forEach { list.add(it.asString) }
                CdpResult.Success(list)
            } else {
                CdpResult.Error("未找到会话列表")
            }
        } catch (e: Exception) {
            CdpResult.Error("解析会话列表失败")
        }
    }

    suspend fun switchSessionByIndex(index: Int): CdpResult<Unit> {
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
                
                var vItems = getItems();
                if (vItems.length > $index) {
                    vItems[$index].click();
                    return 'clicked';
                }
                return 'no-items';
            })()
        """.trimIndent()
        
        val result = cdp.evaluate(script, awaitPromise = true)
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return when (result.getOrNull()) {
            "clicked" -> CdpResult.Success(Unit)
            else -> CdpResult.Error("索引超出范围或未找到会话")
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

    // ─────────────────── 模型切换 ───────────────────

    suspend fun getCurrentModel(): CdpResult<String> {
        val result = cdp.evaluate("""
            (function() {
                var keywords = ['gpt', 'claude', 'gemini', 'o1', 'o3', 'o4', 'deepseek',
                    'sonnet', 'opus', 'haiku', 'kimi', 'swe', 'flash', 'thinking', 'preview'];
                var btns = document.querySelectorAll('button');
                for (var i = 0; i < btns.length; i++) {
                    if (!btns[i].offsetParent) continue;
                    var t = (btns[i].textContent || '').trim().toLowerCase();
                    for (var k = 0; k < keywords.length; k++) {
                        if (t.indexOf(keywords[k]) >= 0 && t.length < 60) return btns[i].textContent.trim();
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
     * 打开模型菜单并读取可见选项（与 [switchModel] 使用相同的按钮探测逻辑）。
     */
    suspend fun listModelOptions(): CdpResult<List<String>> {
        val params = JsonObject().apply {
            addProperty("expression", """
                (async function() {
                    try {
                        function findDropdown() {
                            var keywords = ['gpt', 'claude', 'gemini', 'o1', 'o3', 'o4', 'deepseek',
                                'sonnet', 'opus', 'haiku', 'kimi', 'swe', 'flash', 'thinking', 'preview'];
                            var btns = document.querySelectorAll('button');
                            for (var i = 0; i < btns.length; i++) {
                                if (!btns[i].offsetParent) continue;
                                var t = (btns[i].textContent || '').trim().toLowerCase();
                                for (var k = 0; k < keywords.length; k++) {
                                    if (t.indexOf(keywords[k]) >= 0 && t.length < 60) return btns[i];
                                }
                            }
                            return null;
                        }
                        function gatherFromRoot(root) {
                            var out = [];
                            var seen = new Set();
                            var skipRe = /^(search|filter|add|manage|close|cancel)/i;
                            var nodes = root.querySelectorAll('[role="option"], [role="menuitem"], button');
                            for (var i = 0; i < nodes.length; i++) {
                                var n = nodes[i];
                                if (!n.offsetParent) continue;
                                var txt = (n.textContent || '').replace(/\s+/g, ' ').trim();
                                if (txt.length < 2 || txt.length > 80) continue;
                                if (skipRe.test(txt)) continue;
                                if (seen.has(txt)) continue;
                                seen.add(txt);
                                out.push(txt);
                            }
                            return out;
                        }
                        var dropdownEl = findDropdown();
                        if (!dropdownEl) {
                            return JSON.stringify({ok:false, err:'找不到模型选择按钮', options:[]});
                        }
                        var od = dropdownEl.ownerDocument || document;
                        dropdownEl.click();
                        await new Promise(r => setTimeout(r, 500));
                        var opts = [];
                        var menus = od.querySelectorAll('[role="listbox"], [role="menu"]');
                        for (var mi = 0; mi < menus.length; mi++) {
                            opts = opts.concat(gatherFromRoot(menus[mi]));
                        }
                        if (opts.length === 0) opts = gatherFromRoot(od.body);
                        var uniq = []; var seen2 = new Set();
                        for (var j = 0; j < opts.length; j++) {
                            if (!seen2.has(opts[j])) { seen2.add(opts[j]); uniq.push(opts[j]); }
                        }
                        dropdownEl.click();
                        await new Promise(r => setTimeout(r, 120));
                        return JSON.stringify({ok:true, options: uniq});
                    } catch (e) {
                        return JSON.stringify({ok:false, err: e.message, options:[]});
                    }
                })()
            """.trimIndent())
            addProperty("awaitPromise", true)
            addProperty("returnByValue", true)
            addProperty("timeout", 20000)
        }
        val result = cdp.call("Runtime.evaluate", params)
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        val value = result.getOrThrow().getAsJsonObject("result")?.get("value")?.asString ?: ""
        return try {
            val json = JsonParser.parseString(value).asJsonObject
            if (json.get("ok")?.asBoolean != true) {
                return CdpResult.Error(json.get("err")?.asString ?: "枚举失败")
            }
            val arr = json.getAsJsonArray("options") ?: return CdpResult.Success(emptyList())
            val list = ArrayList<String>(arr.size())
            for (el in arr) {
                if (el.isJsonPrimitive) list.add(el.asString)
            }
            CdpResult.Success(list)
        } catch (e: Exception) {
            CdpResult.Error("解析模型列表失败: $value")
        }
    }

    suspend fun switchModel(modelName: String): CdpResult<Unit> {
        val params = JsonObject().apply {
            addProperty("expression", """
                (async function() {
                    try {
                        var input = ${com.google.gson.JsonPrimitive(modelName.lowercase())};
                        // 1. 找到并点击模型选择按钮
                        var keywords = ['gpt', 'claude', 'gemini', 'o1', 'o3', 'o4', 'deepseek',
                            'sonnet', 'opus', 'haiku', 'kimi', 'swe', 'flash', 'thinking', 'preview'];
                        var dropdownEl = null;
                        var btns = document.querySelectorAll('button');
                        for (var i = 0; i < btns.length; i++) {
                            if (!btns[i].offsetParent) continue;
                            var t = (btns[i].textContent || '').trim().toLowerCase();
                            for (var k = 0; k < keywords.length; k++) {
                                if (t.indexOf(keywords[k]) >= 0 && t.length < 60) { dropdownEl = btns[i]; break; }
                            }
                            if (dropdownEl) break;
                        }
                        if (!dropdownEl) return JSON.stringify({ok:false, err:'找不到模型选择按钮'});
                        dropdownEl.click();
                        await new Promise(r => setTimeout(r, 450));
                        // 2. 匹配目标模型
                        var allBtns = document.querySelectorAll('button, [role="menuitem"], [role="option"]');
                        for (var j = 0; j < allBtns.length; j++) {
                            if (!allBtns[j].offsetParent || allBtns[j] === dropdownEl) continue;
                            var label = (allBtns[j].textContent || '').trim().toLowerCase();
                            if (label.includes(input)) {
                                allBtns[j].click();
                                return JSON.stringify({ok:true, info: allBtns[j].textContent.trim()});
                            }
                        }
                        dropdownEl.click(); // 关闭菜单
                        return JSON.stringify({ok:false, err:'找不到匹配模型: ' + input});
                    } catch(e) { return JSON.stringify({ok:false, err: e.message}); }
                })()
            """.trimIndent())
            addProperty("awaitPromise", true)
            addProperty("returnByValue", true)
            addProperty("timeout", 15000)
        }

        val result = cdp.call("Runtime.evaluate", params)
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        val data = result.getOrThrow()
        val value = data.getAsJsonObject("result")?.get("value")?.asString ?: ""
        return try {
            val json = JsonParser.parseString(value).asJsonObject
            if (json.get("ok")?.asBoolean == true) CdpResult.Success(Unit)
            else CdpResult.Error(json.get("err")?.asString ?: "切换失败")
        } catch (e: Exception) {
            CdpResult.Error("解析切换结果失败: $value")
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
