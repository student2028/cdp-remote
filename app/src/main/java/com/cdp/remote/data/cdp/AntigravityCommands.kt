package com.cdp.remote.data.cdp

import android.util.Log
import com.cdp.remote.CdpRemoteApp
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.delay

/**
 * Antigravity IDE 专用 CDP 命令集
 *
 * 直接移植 ~/.agents/skills/antigravity/scripts/ 下经过实战验证的 JS 逻辑。
 * 每个方法对应一个已验证的 Node.js 脚本。
 */
open class AntigravityCommands(protected val cdp: ICdpClient, private val appName: String = "") {

    companion object {
        private const val TAG = "AntigravityCmds"
        internal const val INPUT_BOX_ID = "antigravity.agentSidePanelInputBox"
        private val companionLock = Any()
        @Volatile
        private var cdpHelpersScriptCache: String? = null
    }

    // 排序后的显示索引到原始 DOM 索引的映射
    private var sessionIndexMap: List<Int> = emptyList()

    private suspend fun ensureCdpHelpersInjected(): CdpResult<Unit> {
        val script = cdpHelpersScriptCache ?: synchronized(companionLock) {
            cdpHelpersScriptCache ?: CdpRemoteApp.readAssetText("js/cdp_helpers.js")?.also {
                cdpHelpersScriptCache = it
            }
        }

        if (script.isNullOrBlank()) {
            return CdpResult.Error("无法加载 CDP helpers 资源")
        }

        val result = cdp.evaluate(script)
        return when (result) {
            is CdpResult.Error -> CdpResult.Error("注入 CDP helpers 失败: ${result.message}")
            is CdpResult.Success -> CdpResult.Success(Unit)
        }
    }

    // ─────────────────── 发送消息 ───────────────────
    // 移植自: antigravity_send_msg_optimized.js

    /**
     * 聚焦输入框 - 支持 Antigravity 和 Cursor/VSCode
     */
    suspend fun focusInput(): CdpResult<Boolean> {
        val result = cdp.evaluate("""
            (function() {
                function docs(d) {
                    var out = [d];
                    var ifr = d.querySelectorAll('iframe');
                    for (var i = 0; i < ifr.length; i++) {
                        try {
                            var id = ifr[i].contentDocument;
                            if (id) out = out.concat(docs(id));
                        } catch (e) {}
                    }
                    return out;
                }
                function vh(doc) {
                    try { return doc.defaultView && doc.defaultView.innerHeight ? doc.defaultView.innerHeight : 800; } 
                    catch (e) { return 800; }
                }
                var all = docs(document);
                var selectors = [
                    '#antigravity\\.agentSidePanelInputBox [contenteditable="true"]',
                    'div.aislash-editor-input',
                    '.aislash-editor-input',
                    'textarea[aria-label*="chat" i]',
                    'textarea[aria-label*="message" i]',
                    'textarea[aria-label*="composer" i]',
                    'textarea[aria-label*="agent" i]',
                    'textarea[placeholder*="Ask" i]',
                    'textarea[placeholder*="Plan" i]',
                    'textarea[placeholder*="Composer" i]',
                    '[class*="composer"] textarea',
                    '[class*="ChatInput"] textarea',
                    '[class*="chat-input"] textarea',
                    '[class*="aichat"] textarea',
                    '.interactive-session textarea',
                    'textarea.monaco-mouse-cursor-text'
                ];
                for (var di = 0; di < all.length; di++) {
                    var doc = all[di];
                    if (!doc || !doc.querySelector) continue;
                    for (var s = 0; s < selectors.length; s++) {
                        try {
                            var el = doc.querySelector(selectors[s]);
                            if (el && el.offsetParent) {
                                el.focus();
                                return 'ok';
                            }
                        } catch (e) {}
                    }
                    var tas = doc.querySelectorAll('textarea');
                    for (var t = 0; t < tas.length; t++) {
                        var ta = tas[t];
                        if (!ta.offsetParent) continue;
                        var r = ta.getBoundingClientRect();
                        if (r.bottom > vh(doc) * 0.32) {
                            ta.focus();
                            return 'ok';
                        }
                    }
                    var ce = doc.querySelectorAll('div[contenteditable="true"]');
                    for (var c = 0; c < ce.length; c++) {
                        var e = ce[c];
                        if (!e.offsetParent) continue;
                        var r2 = e.getBoundingClientRect();
                        if (r2.bottom > vh(doc) * 0.32) {
                            var cls = e.className || '';
                            var parent = e.closest('[class*="chat"], [class*="composer"], [class*="interactive"], [class*="aichat"]');
                            // Windsurf 用 min-h-[2rem] outline-none 的 contenteditable
                            if (parent || cls.indexOf('min-h-') >= 0 || cls.indexOf('outline-none') >= 0) {
                                e.focus();
                                return 'ok';
                            }
                        }
                    }
                }
                return 'no-container';
            })()
        """.trimIndent())

        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return when (result.getOrNull()) {
            "ok" -> CdpResult.Success(true)
            else -> CdpResult.Error("聚焦失败: ${result.getOrNull()}")
        }
    }

    /**
     * 设置输入框文字 - 完美兼容 React / Lexical 框架的输入模拟以及 Cursor
     */
    suspend fun setInputText(text: String): CdpResult<Unit> {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        val result = cdp.evaluate("""
            (function() {
                // 1. Antigravity 专用路径
                var container = document.getElementById('$INPUT_BOX_ID');
                if (container) {
                    var editableDiv = container.querySelector('[contenteditable="true"]');
                    if (editableDiv) {
                        editableDiv.focus();
                        document.execCommand('selectAll', false, null);
                        document.execCommand('delete', false, null);
                        document.execCommand('insertText', false, '$escaped');
                        return 'ok';
                    }
                }
                // 2. Windsurf / 通用 contenteditable (role=textbox 或 min-h- 类名)
                var ces = document.querySelectorAll('div[contenteditable="true"]');
                for (var i = 0; i < ces.length; i++) {
                    var el = ces[i];
                    if (!el.offsetParent) continue;
                    var cls = el.className || '';
                    var role = el.getAttribute('role') || '';
                    if (role === 'textbox' || cls.indexOf('min-h-') >= 0 || cls.indexOf('outline-none') >= 0) {
                        el.focus();
                        document.execCommand('selectAll', false, null);
                        document.execCommand('delete', false, null);
                        document.execCommand('insertText', false, '$escaped');
                        return 'ok';
                    }
                }
                return 'fallback';
            })()
        """.trimIndent())

        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        
        if (result.getOrNull() == "fallback") {
            // 对于 Cursor 等其他 IDE，利用 Input.insertText 模拟全选并覆盖
            cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
                addProperty("type", "keyDown")
                addProperty("key", "a")
                addProperty("code", "KeyA")
                addProperty("modifiers", 4) // Cmd
                addProperty("windowsVirtualKeyCode", 65)
            })
            cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
                addProperty("type", "keyUp")
                addProperty("key", "a")
                addProperty("code", "KeyA")
                addProperty("modifiers", 4)
                addProperty("windowsVirtualKeyCode", 65)
            })
            delay(50)
            cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
                addProperty("type", "keyDown")
                addProperty("key", "Backspace")
                addProperty("code", "Backspace")
                addProperty("windowsVirtualKeyCode", 8)
            })
            cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
                addProperty("type", "keyUp")
                addProperty("key", "Backspace")
                addProperty("code", "Backspace")
                addProperty("windowsVirtualKeyCode", 8)
            })
            delay(100)
            
            val insertRes = cdp.call("Input.insertText", JsonObject().apply {
                addProperty("text", text)
            })
            if (insertRes is CdpResult.Error) return CdpResult.Error(insertRes.message)
            return CdpResult.Success(Unit)
        }
        
        return CdpResult.Success(Unit)
    }

    /**
     * 点击发送按钮 - 更智能的查找或回车模拟
     */
    open suspend fun clickSendButton(): CdpResult<Boolean> {
        val result = cdp.evaluate("""
            (function() {
                var container = document.getElementById('$INPUT_BOX_ID');
                if (container) {
                    // 1. 尝试找带有 send 或 发送 语义的按钮
                    var btns = container.querySelectorAll('button');
                    for (var i = 0; i < btns.length; i++) {
                        var aria = (btns[i].getAttribute('aria-label') || '').toLowerCase();
                        var title = (btns[i].title || '').toLowerCase();
                        if (aria.includes('send') || aria.includes('发送') || 
                            title.includes('send') || title.includes('发送')) {
                            btns[i].disabled = false;
                            btns[i].click();
                            return 'clicked';
                        }
                    }
                    
                    // 2. 尝试找 data-testid="send-button"
                    var sendBtn = container.querySelector('[data-testid="send-button"]');
                    if (sendBtn) {
                        sendBtn.disabled = false;
                        sendBtn.click();
                        return 'clicked';
                    }
                }
                // 3. Windsurf: button[type=submit] (圆形发送按钮)
                var submitBtn = document.querySelector('button[type="submit"]');
                if (submitBtn && submitBtn.offsetParent) {
                    submitBtn.click();
                    return 'clicked';
                }
                return 'no-button';
            })()
        """.trimIndent())

        if (result.getOrNull() == "clicked") {
            return CdpResult.Success(true)
        }

        // 降级：模拟完整的 Enter 键事件
        Log.d(TAG, "未找到显式发送按钮，使用 Enter 键提交")
        cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
            addProperty("type", "keyDown")
            addProperty("key", "Enter")
            addProperty("code", "Enter")
            addProperty("windowsVirtualKeyCode", 13)
        })
        delay(50)
        cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
            addProperty("type", "keyUp")
            addProperty("key", "Enter")
            addProperty("code", "Enter")
            addProperty("windowsVirtualKeyCode", 13)
        })

        return CdpResult.Success(true)
    }

    /**
     * 发送完整消息（聚焦 + 设置文字 + 点击发送）
     */
    open suspend fun sendMessage(text: String): CdpResult<Unit> {
        focusInput().let { if (it is CdpResult.Error) return CdpResult.Error("聚焦失败: ${it.message}") }
        delay(100)
        setInputText(text).let { if (it is CdpResult.Error) return CdpResult.Error("输入失败: ${it.message}") }
        delay(200)
        clickSendButton().let { if (it is CdpResult.Error) return CdpResult.Error("发送失败: ${it.message}") }
        return CdpResult.Success(Unit)
    }

    // ─────────────────── 图片粘贴 ───────────────────

    /**
     * 将 base64 编码的图片通过 CDP 粘贴到输入框
     * 模拟浏览器剪贴板粘贴事件，将图片作为文件注入
     */
    open suspend fun pasteImage(base64Data: String, mimeType: String = "image/png", fileName: String = "image.png"): CdpResult<Boolean> {
        // 先聚焦输入框
        focusInput().let { if (it is CdpResult.Error) return CdpResult.Error("聚焦失败: ${it.message}") }
        delay(100)

        // 分块传输 base64 数据（避免 JS 字符串过大）
        // 先把 base64 存到一个全局变量
        val chunkSize = 50000
        val chunks = base64Data.chunked(chunkSize)

        // 初始化全局变量
        val initResult = cdp.evaluate("window.__pasteImageB64 = '';")
        if (initResult is CdpResult.Error) return CdpResult.Error("初始化图片传输失败: ${initResult.message}")
        
        // 分块追加
        for (chunk in chunks) {
            val literal = com.google.gson.JsonPrimitive(chunk).toString()
            val appendResult = cdp.evaluate("window.__pasteImageB64 += $literal;")
            if (appendResult is CdpResult.Error) {
                cdp.evaluate("window.__pasteImageB64 = null;")
                return CdpResult.Error("图片分块传输失败: ${appendResult.message}")
            }
        }

        // 执行粘贴
        val result = cdp.evaluate("""
            (function() {
                try {
                    var editableDiv = null;
                    // 1. Antigravity 专用路径
                    var container = document.getElementById('$INPUT_BOX_ID');
                    if (container) {
                        editableDiv = container.querySelector('[contenteditable="true"]');
                    }
                    // 2. Windsurf: #chat 内的 contenteditable
                    if (!editableDiv) {
                        var chatDiv = document.getElementById('chat');
                        if (chatDiv) editableDiv = chatDiv.querySelector('[contenteditable="true"]');
                    }
                    // 3. Windsurf: #windsurf.cascadePanel
                    if (!editableDiv) {
                        var panel = document.getElementById('windsurf.cascadePanel');
                        if (panel) editableDiv = panel.querySelector('[contenteditable="true"]');
                    }
                    // 4. 通用: role=textbox 或 min-h- 类名的 contenteditable
                    if (!editableDiv) {
                        var ces = document.querySelectorAll('div[contenteditable="true"]');
                        for (var i = 0; i < ces.length; i++) {
                            var el = ces[i];
                            if (!el.offsetParent) continue;
                            var cls = el.className || '';
                            var role = el.getAttribute('role') || '';
                            var parent = el.closest('[class*="chat"], [class*="composer"], [class*="interactive"], [class*="aichat"]');
                            if (role === 'textbox' || parent || cls.indexOf('min-h-') >= 0 || cls.indexOf('outline-none') >= 0) {
                                editableDiv = el;
                                break;
                            }
                        }
                    }
                    if (!editableDiv) return 'no-container';
                    
                    editableDiv.focus();
                    
                    var base64 = window.__pasteImageB64;
                    if (!base64 || base64.length === 0) return 'no-data';
                    base64 = String(base64).replace(/^data:[^,]+,/, '').replace(/\s/g, '');
                    if (!/^[A-Za-z0-9+/]*={0,2}$/.test(base64) || base64.length % 4 === 1) {
                        return 'bad-base64:' + base64.length;
                    }
                    
                    // base64 -> Uint8Array
                    var binary = atob(base64);
                    var bytes = new Uint8Array(binary.length);
                    for (var i = 0; i < binary.length; i++) {
                        bytes[i] = binary.charCodeAt(i);
                    }
                    
                    // 创建 Blob 和 File
                    var blob = new Blob([bytes], {type: '$mimeType'});
                    var file = new File([blob], '$fileName', {type: '$mimeType', lastModified: Date.now()});
                    
                    // 创建 DataTransfer
                    var dt = new DataTransfer();
                    dt.items.add(file);
                    
                    // 方式1: 尝试 ClipboardEvent paste
                    try {
                        var pasteEvent = new ClipboardEvent('paste', {
                            clipboardData: dt,
                            bubbles: true,
                            cancelable: true
                        });
                        editableDiv.dispatchEvent(pasteEvent);
                    } catch(e1) {
                        // 方式2: 回退到 drop 事件
                        var dropEvent = new DragEvent('drop', {
                            dataTransfer: dt,
                            bubbles: true,
                            cancelable: true
                        });
                        editableDiv.dispatchEvent(dropEvent);
                    }
                    
                    // 清理
                    window.__pasteImageB64 = null;
                    
                    return 'ok';
                } catch(e) {
                    window.__pasteImageB64 = null;
                    return 'error:' + e.message;
                }
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

    /**
     * 发送图片消息（粘贴图片 + 点击发送）
     */
    suspend fun sendImage(base64Data: String, mimeType: String = "image/png"): CdpResult<Unit> {
        pasteImage(base64Data, mimeType).let { 
            if (it is CdpResult.Error) return CdpResult.Error(it.message) 
        }
        delay(500) // 等待 UI 渲染图片预览
        clickSendButton().let { 
            if (it is CdpResult.Error) return CdpResult.Error("发送失败: ${it.message}") 
        }
        return CdpResult.Success(Unit)
    }

    // ─────────────────── 获取回复 ───────────────────
    // 完整移植自: antigravity_get_last_reply.js extractExpression()
    // 包含 Shadow DOM 遍历、iframe 递归、多策略回退

    /**
     * 获取最后一条助手回复 - 完整移植 skill 脚本的 extractExpression
     */
    open suspend fun getLastReply(): CdpResult<String> {
        val result = cdp.evaluate("""
            (function(){
                try {
                    function inChatInputArea(el) {
                        if (!el || !el.closest) return false;
                        var inputBox = document.getElementById('$INPUT_BOX_ID');
                        if (inputBox && inputBox.contains(el)) return true;

                        var ta = el.closest('textarea');
                        if (ta) {
                            var ar = (ta.getAttribute('aria-label') || '').toLowerCase();
                            var ph = (ta.getAttribute('placeholder') || '').toLowerCase();
                            if (ar.indexOf('chat') >= 0 || ar.indexOf('message') >= 0 || ar.indexOf('composer') >= 0) return true;
                            if (ph.indexOf('ask') >= 0 || ph.indexOf('plan') >= 0) return true;
                            var r = ta.getBoundingClientRect();
                            if (r.bottom > (window.innerHeight || 600) * 0.3) return true;
                        }
                        var ce = el.closest('[contenteditable="true"]');
                        if (ce) {
                            var p = ce.closest('[class*="composer"], [class*="chat-input"], [class*="interactive"], [class*="aichat"]');
                            if (p) return true;
                        }
                        return false;
                    }
                    function inUserTurn(el) {
                        return el.closest && el.closest('[data-message-author-role="user"]');
                    }
                    function isAssistantCandidate(el) {
                        if (inChatInputArea(el) || inUserTurn(el)) return false;
                        return true;
                    }
                    function visible(el) {
                        if (!el) return false;
                        var r = el.getBoundingClientRect();
                        return r.width > 0 && r.height > 0;
                    }
                    function flattenElements(root) {
                        var out = [];
                        function walk(node) {
                            if (!node) return;
                            if (node.nodeType === 1) {
                                out.push(node);
                                if (node.shadowRoot) walk(node.shadowRoot);
                            }
                            for (var c = node.firstChild; c; c = c.nextSibling) walk(c);
                        }
                        walk(root);
                        return out;
                    }
                    function classStr(el) {
                        var c = el.className;
                        return (typeof c === 'string' ? c : (el.getAttribute && el.getAttribute('class')) || '') || '';
                    }
                    function matchesProse(el) {
                        var c = classStr(el);
                        return c.indexOf('select-text') >= 0 && c.indexOf('leading-relaxed') >= 0;
                    }
                    function hasSelectText(el) {
                        return classStr(el).indexOf('select-text') >= 0;
                    }
                    function allRootNodes() {
                        var roots = [document];
                        function walk(d) {
                            if (!d || !d.querySelectorAll) return;
                            var ifr = d.querySelectorAll('iframe');
                            for (var i = 0; i < ifr.length; i++) {
                                try {
                                    var inner = ifr[i].contentDocument;
                                    if (inner) { roots.push(inner); walk(inner); }
                                } catch (e) {}
                            }
                        }
                        walk(document);
                        return roots;
                    }
                    function tryInPanel(panelNode) {
                        var flat = flattenElements(panelNode);
                        var combo = flat.filter(function(el) {
                            return matchesProse(el) && visible(el) && isAssistantCandidate(el);
                        });
                        if (combo.length) {
                            var lastC = combo[combo.length - 1];
                            var t = (lastC.innerText || '').trim();
                            if (t.length) return t;
                        }
                        var all = flat.filter(function(el) {
                            return hasSelectText(el) && visible(el) && isAssistantCandidate(el);
                        });
                        if (all.length) {
                            var lastS = all[all.length - 1];
                            var t3 = (lastS.innerText || '').trim();
                            if (t3.length) return t3;
                        }
                        var articles = panelNode.querySelectorAll('[role="article"], [class*="markdown"], [class*="rendered-markdown"]');
                        for (var j = articles.length - 1; j >= 0; j--) {
                            var ar = articles[j];
                            if (inChatInputArea(ar) || !visible(ar)) continue;
                            var txt = (ar.innerText || '').trim();
                            if (txt.length > 2) return txt;
                        }
                        var weak = flat.filter(function(el) {
                            var c = classStr(el);
                            if (!visible(el) || !isAssistantCandidate(el)) return false;
                            return c.indexOf('break-words') >= 0 || (c.indexOf('text-sm') >= 0 && c.indexOf('flex-col') >= 0);
                        });
                        if (weak.length) {
                            var lastW = weak[weak.length - 1];
                            var tw = (lastW.innerText || '').trim();
                            if (tw.length > 5) return tw;
                        }
                        return null;
                    }

                    var docs = allRootNodes();
                    for (var di = 0; di < docs.length; di++) {
                        var doc = docs[di];
                        var asst = doc.querySelectorAll('[data-message-author-role="assistant"]');
                        if (asst.length) {
                            var lastA = asst[asst.length - 1];
                            var prose = lastA.querySelector('[class*="select-text"]') || lastA;
                            if (prose && visible(prose) && !inChatInputArea(prose)) {
                                var ta = (prose.innerText || '').trim();
                                if (ta.length) return ta;
                            }
                        }
                        var p = doc.querySelector('.antigravity-agent-side-panel')
                          || doc.querySelector('[class*="antigravity-agent"]')
                          || doc.querySelector('[class*="interactive-session"]')
                          || doc.querySelector('[class*="aichat"]')
                          || doc.querySelector('[class*="composer"]')
                          || doc.querySelector('[class*="chat-view"]')
                          || doc.querySelector('[class*="cascade-scrollbar"]')
                          || doc.querySelector('[class*="chat-client-root"]')
                          || doc.body || doc;
                        var r0 = tryInPanel(p);
                        if (r0) return r0;
                    }

                    return '';
                } catch (e) {
                    return '';
                }
            })()
        """.trimIndent())

        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        val text = result.getOrNull() ?: ""
        return CdpResult.Success(text)
    }

    /**
     * 获取完整聊天记录（用于后台自动同步）
     * 使用与 getLastReply 相同的 skill 级别 DOM 遍历
     */
    suspend fun getChatHistory(): CdpResult<List<ChatMessage>> {
        val result = cdp.evaluate("""
            (function() {
                try {
                    function inChatInputArea(el) {
                        if (!el || !el.closest) return false;
                        var inputBox = document.getElementById('$INPUT_BOX_ID');
                        if (inputBox && inputBox.contains(el)) return true;

                        var ta = el.closest('textarea');
                        if (ta) {
                            var ar = (ta.getAttribute('aria-label') || '').toLowerCase();
                            var ph = (ta.getAttribute('placeholder') || '').toLowerCase();
                            if (ar.indexOf('chat') >= 0 || ar.indexOf('message') >= 0 || ar.indexOf('composer') >= 0) return true;
                            if (ph.indexOf('ask') >= 0 || ph.indexOf('plan') >= 0) return true;
                            var r = ta.getBoundingClientRect();
                            if (r.bottom > (window.innerHeight || 600) * 0.3) return true;
                        }
                        var ce = el.closest('[contenteditable="true"]');
                        if (ce) {
                            var p = ce.closest('[class*="composer"], [class*="chat-input"], [class*="interactive"], [class*="aichat"]');
                            if (p) return true;
                        }
                        return false;
                    }
                    function visible(el) {
                        if (!el) return false;
                        var r = el.getBoundingClientRect();
                        return r.width > 0 && r.height > 0;
                    }
                    function classStr(el) {
                        var c = el.className;
                        return (typeof c === 'string' ? c : (el.getAttribute && el.getAttribute('class')) || '') || '';
                    }
                    function flattenElements(root) {
                        var out = [];
                        function walk(node) {
                            if (!node) return;
                            if (node.nodeType === 1) {
                                out.push(node);
                                if (node.shadowRoot) walk(node.shadowRoot);
                            }
                            for (var c = node.firstChild; c; c = c.nextSibling) walk(c);
                        }
                        walk(root);
                        return out;
                    }
                    function allRootNodes() {
                        var roots = [document];
                        function walk(d) {
                            if (!d || !d.querySelectorAll) return;
                            var ifr = d.querySelectorAll('iframe');
                            for (var i = 0; i < ifr.length; i++) {
                                try {
                                    var inner = ifr[i].contentDocument;
                                    if (inner) { roots.push(inner); walk(inner); }
                                } catch (e) {}
                            }
                        }
                        walk(document);
                        return roots;
                    }
                    
                    var items = [];
                    var docs = allRootNodes();
                    
                    for (var di = 0; di < docs.length; di++) {
                        var doc = docs[di];
                        
                        // 遍历所有带 data-message-author-role 的节点
                        var nodes = doc.querySelectorAll('[data-message-author-role]');
                        var useComposerLogic = false;
                        if (nodes.length === 0) {
                            // Cursor 较新版本 Composer 使用 .composer-rendered-message
                            nodes = doc.querySelectorAll('.composer-rendered-message');
                            useComposerLogic = nodes.length > 0;
                        }

                        for (var i = 0; i < nodes.length; i++) {
                            var node = nodes[i];
                            if (inChatInputArea(node)) continue;
                            
                            var role = '';
                            var text = '';
                            
                            if (useComposerLogic) {
                                var cls = (typeof node.className === 'string') ? node.className : (node.getAttribute('class') || '');
                                role = cls.indexOf('human') >= 0 ? 'user' : 'assistant';
                            } else {
                                role = node.getAttribute('data-message-author-role');
                            }
                            
                            if (role === 'assistant') {
                                // 对 assistant 消息用 select-text 抽取
                                var prose = node.querySelector('[class*="select-text"]');
                                if (prose && visible(prose)) {
                                    text = (prose.innerText || '').trim();
                                }
                                if (!text) {
                                    // 对于 Composer，有时候内容在 .markdown-root 中
                                    var md = node.querySelector('.markdown-root');
                                    if (md && visible(md)) text = (md.innerText || '').trim();
                                }
                                if (!text) {
                                    text = (node.innerText || '').trim();
                                }
                            } else {
                                text = (node.innerText || '').trim();
                            }
                            
                            if (text) {
                                items.push({
                                    role: role === 'user' ? 'USER' : 'ASSISTANT',
                                    content: text
                                });
                            }
                        }
                        
                        if (items.length > 0) break; // 找到了就不继续找其他 document
                    }
                    
                    // 如果上面没拿到，回退: 用 side panel 抽最后一条 assistant
                    if (items.length === 0) {
                        function tryInPanel(panelNode) {
                            var flat = flattenElements(panelNode);
                            var combo = flat.filter(function(el) {
                                var c = classStr(el);
                                return c.indexOf('select-text') >= 0 && c.indexOf('leading-relaxed') >= 0 && visible(el) && !inChatInputArea(el);
                            });
                            if (combo.length) {
                                var lastC = combo[combo.length - 1];
                                var t = (lastC.innerText || '').trim();
                                if (t.length) return t;
                            }
                            var all = flat.filter(function(el) {
                                var c = classStr(el);
                                return c.indexOf('select-text') >= 0 && visible(el) && !inChatInputArea(el);
                            });
                            if (all.length) {
                                var lastS = all[all.length - 1];
                                var t3 = (lastS.innerText || '').trim();
                                if (t3.length) return t3;
                            }
                            var articles = panelNode.querySelectorAll('[role="article"], [class*="markdown"], [class*="rendered-markdown"]');
                            for (var j = articles.length - 1; j >= 0; j--) {
                                var ar = articles[j];
                                if (inChatInputArea(ar) || !visible(ar)) continue;
                                var txt = (ar.innerText || '').trim();
                                if (txt.length > 2) return txt;
                            }
                            var weak = flat.filter(function(el) {
                                var c = classStr(el);
                                if (!visible(el) || inChatInputArea(el)) return false;
                                return c.indexOf('break-words') >= 0 || (c.indexOf('text-sm') >= 0 && c.indexOf('flex-col') >= 0);
                            });
                            if (weak.length) {
                                var lastW = weak[weak.length - 1];
                                var tw = (lastW.innerText || '').trim();
                                if (tw.length > 5) return tw;
                            }
                            return null;
                        }

                        var docs2 = allRootNodes();
                        var foundFallback = false;
                        for (var di = 0; di < docs2.length; di++) {
                            var doc2 = docs2[di];
                            var panel = doc2.querySelector('.antigravity-agent-side-panel')
                                || doc2.querySelector('[class*="antigravity-agent"]')
                                || doc2.querySelector('[class*="interactive-session"]')
                                || doc2.querySelector('[class*="aichat"]')
                                || doc2.querySelector('[class*="composer"]')
                                || doc2.querySelector('[class*="chat-view"]')
                                || doc2.querySelector('[class*="cascade-scrollbar"]')
                                || doc2.querySelector('[class*="chat-client-root"]')
                                || doc2.body || doc2;
                            var r0 = tryInPanel(panel);
                            if (r0) {
                                items.push({role: 'ASSISTANT', content: r0});
                                foundFallback = true;
                                break;
                            }
                        }
                    }
                    
                    return JSON.stringify(items);
                } catch(e) {
                    return JSON.stringify([]);
                }
            })()
        """.trimIndent())

        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return try {
            val jsonArray = JsonParser.parseString(result.getOrNull() ?: "[]").asJsonArray
            val messages = jsonArray.map { element ->
                val obj = element.asJsonObject
                val roleStr = obj.get("role").asString
                val role = if (roleStr == "USER") MessageRole.USER else MessageRole.ASSISTANT
                ChatMessage(
                    role = role,
                    content = obj.get("content").asString
                )
            }
            CdpResult.Success(messages)
        } catch (e: Exception) {
            CdpResult.Error("解析历史记录失败: ${'$'}{e.message}")
        }
    }

    /**
     * 检查 AI 是否正在生成中（纯查询，无副作用）
     * 移植自 antigravity_stop_session.js 的检测逻辑
     */
    suspend fun isGenerating(): Boolean {
        val result = cdp.evaluate("""
            (function() {
                function flattenElements(root) {
                    var out = [];
                    function walk(node) {
                        if (!node) return;
                        if (node.nodeType === 1) {
                            out.push(node);
                            if (node.shadowRoot) walk(node.shadowRoot);
                        }
                        for (var c = node.firstChild; c; c = c.nextSibling) walk(c);
                    }
                    walk(root);
                    return out;
                }
                function docs(d) {
                    var out = [d];
                    var ifr = d.querySelectorAll('iframe');
                    for (var i = 0; i < ifr.length; i++) {
                        try { if(ifr[i].contentDocument) out.push(ifr[i].contentDocument); } catch(e){}
                    }
                    return out;
                }
                
                var all = docs(document);
                for (var di = 0; di < all.length; di++) {
                    var doc = all[di];
                    if (!doc || !doc.querySelectorAll) continue;

                    var flat = flattenElements(doc);

                    // 1: 检查 cancel / stop (真正在生成中)
                    for (var i = 0; i < flat.length; i++) {
                        var b = flat[i];
                        if (!b.tagName) continue;
                        var tag = b.tagName.toLowerCase();
                        var role = b.getAttribute('role') || '';
                        var cls = (typeof b.className === 'string' ? b.className : '').toLowerCase();
                        if (tag === 'button' || role === 'button' || cls.includes('button') || tag.includes('button') || (tag === 'span' && b.closest && b.closest('.send-with-mode'))) {
                            if (!b.offsetParent && !(b.getRootNode() instanceof ShadowRoot)) continue;
                            var t = (b.textContent||'').trim().toLowerCase();
                            var a = (b.getAttribute('aria-label')||'').toLowerCase();
                            if (t === 'stop' || t === '停止生成' || t === '停止会话' || t === 'cancel' || a.includes('stop') || a.includes('cancel') || a.includes('停止') || cls.includes('stop-button') || cls.includes('cancel-button')) return true;
                        }
                    }

                    // 2: 检查是否有待确认的 Action 按钮（Run/Allow/Approve 存在 = 等待中）
                    for (var j = 0; j < flat.length; j++) {
                        var btn = flat[j];
                        if (!btn.tagName) continue;
                        var tag2 = btn.tagName.toLowerCase();
                        var role2 = btn.getAttribute('role') || '';
                        var cls2 = (typeof btn.className === 'string' ? btn.className : '').toLowerCase();
                        
                        if (tag2 === 'button' || tag2 === 'a' || role2 === 'button' || cls2.includes('button') || tag2.includes('button')) {
                            if (!btn.offsetParent && !(btn.getRootNode() instanceof ShadowRoot)) continue;
                            var txt = (btn.textContent || '').trim();
                            var lowerTxt = txt.toLowerCase();
                            if (
                                lowerTxt === 'run' ||
                                lowerTxt === 'allow' ||
                                lowerTxt === 'approve' ||
                                lowerTxt === 'continue' ||
                                lowerTxt === 'yes' ||
                                lowerTxt === 'always allow' ||
                                lowerTxt === 'allow once' ||
                                lowerTxt === 'allow in workspace' ||
                                (lowerTxt.startsWith('run') && lowerTxt.includes('(')) || 
                                (lowerTxt.startsWith('allow') && lowerTxt.includes('(')) ||
                                (lowerTxt.startsWith('approve') && lowerTxt.includes('(')) ||
                                lowerTxt === 'approve action' ||
                                lowerTxt === 'run action'
                            ) {
                                if (txt.length < 25 && txt.indexOf('Esc') === -1 && txt.indexOf('Cancel') === -1) {
                                    return true;
                                }
                            }
                        }
                    }
                }

                // 3: 兼容 Antigravity 原来的停止图标 (div.shrink-0 > div.bg-gray-500)
                var inputArea = document.getElementById('$INPUT_BOX_ID');
                if (inputArea) {
                    var stopDivs = inputArea.querySelectorAll('div.shrink-0 > div[class*="bg-gray-500"]');
                    if (stopDivs.length > 0) return true;
                }

                return false;
            })()
        """.trimIndent())
        return result.getOrNull()?.toBooleanStrictOrNull() ?: false
    }

    /**
     * 自动放行 Agent 的 Action 确认按钮 (Run, Allow, Approve 等)
     * 返回 true 表示成功点击了一个按钮
     */
    open suspend fun autoAcceptActions(): Boolean {
        ensureCdpHelpersInjected().let {
            if (it is CdpResult.Error) {
                Log.e(TAG, it.message)
                return false
            }
        }

        val result = cdp.evaluate("""
            (function() {
                var helpers = window.__cdpHelpers;
                if (!helpers) return JSON.stringify({ found: false, reason: 'helpers-missing' });

                var all = helpers.allDocsWithOffset(document);
                for (var di = 0; di < all.length; di++) {
                    var item = all[di];
                    var doc = item.doc;
                    if (!doc || !doc.querySelectorAll) continue;

                    // 只在右侧 AI 面板内搜索，避免误触编辑区、文件标签等
                    var panel = helpers.findPanelNode(doc);
                    if (!panel || panel === doc.body || panel === doc) {
                        // 如果找不到明确的面板，尝试用更窄的选择器
                        panel = doc.querySelector('.antigravity-agent-side-panel')
                             || doc.querySelector('[class*="antigravity-agent"]')
                             || doc.querySelector('[class*="interactive-session"]')
                             || doc.querySelector('[class*="aichat"]')
                             || doc.querySelector('[class*="cascade-scrollbar"]')
                             || doc.querySelector('[class*="chat-client-root"]');
                        if (!panel) continue; // 找不到面板就跳过，绝不扫全页面
                    }
                    var flat = helpers.flattenElements(panel);

                    for (var j = 0; j < flat.length; j++) {
                        var btn = flat[j];
                        if (helpers.isButtonLike(btn) && helpers.isVisibleButton(btn)) {
                            var txt = (btn.textContent || '').trim();
                            if (helpers.matchesActionButton(txt)) {
                                helpers.fullClick(btn);

                                var rect = btn.getBoundingClientRect();
                                return JSON.stringify({
                                    found: true,
                                    x: rect.x + item.offsetX + rect.width / 2,
                                    y: rect.y + item.offsetY + rect.height / 2
                                });
                            }
                        }
                    }
                }
                return JSON.stringify({ found: false });
            })()
        """.trimIndent())
        
        val evalStr = result.getOrNull() ?: return false
        try {
            val json = com.google.gson.JsonParser.parseString(evalStr).asJsonObject
            if (json.has("found") && json.get("found").asBoolean) {
                val x = json.get("x").asDouble
                val y = json.get("y").asDouble
                
                // Native CDP Click
                val moveParams = com.google.gson.JsonObject().apply {
                    addProperty("type", "mouseMoved")
                    addProperty("x", x)
                    addProperty("y", y)
                }
                cdp.call("Input.dispatchMouseEvent", moveParams)
                kotlinx.coroutines.delay(50)

                val downParams = com.google.gson.JsonObject().apply {
                    addProperty("type", "mousePressed")
                    addProperty("x", x)
                    addProperty("y", y)
                    addProperty("button", "left")
                    addProperty("clickCount", 1)
                }
                cdp.call("Input.dispatchMouseEvent", downParams)
                
                kotlinx.coroutines.delay(50)
                
                val upParams = com.google.gson.JsonObject().apply {
                    addProperty("type", "mouseReleased")
                    addProperty("x", x)
                    addProperty("y", y)
                    addProperty("button", "left")
                    addProperty("clickCount", 1)
                }
                cdp.call("Input.dispatchMouseEvent", upParams)
                
                return true
            }
        } catch (e: Exception) {
            android.util.Log.e("AntigravityCommands", "Failed to parse autoAcceptActions result", e)
        }
        
        return false
    }

    /**
     * 点击 "Accept all" 按钮 — 接收所有代码变更
     */
    open suspend fun acceptAll(): CdpResult<Boolean> {
        val result = cdp.evaluate("""
            (function() {
                function docs(d) {
                    var out = [d];
                    var ifr = d.querySelectorAll('iframe');
                    for (var i = 0; i < ifr.length; i++) {
                        try { if(ifr[i].contentDocument) out.push(ifr[i].contentDocument); } catch(e){}
                    }
                    return out;
                }
                function flattenAll(root) {
                    var out = [];
                    function walk(node) {
                        if (!node) return;
                        if (node.nodeType === 1) {
                            out.push(node);
                            if (node.shadowRoot) walk(node.shadowRoot);
                        }
                        for (var c = node.firstChild; c; c = c.nextSibling) walk(c);
                    }
                    walk(root);
                    return out;
                }
                var all = docs(document);
                for (var di = 0; di < all.length; di++) {
                    var doc = all[di];
                    var flat = flattenAll(doc);
                    for (var j = 0; j < flat.length; j++) {
                        var el = flat[j];
                        var txt = (el.textContent || '').trim();
                        if (txt.toLowerCase() !== 'accept all') continue;
                        var r = el.getBoundingClientRect();
                        if (r.width > 0 && r.height > 0) {
                            return JSON.stringify({found:true, x: r.x + r.width/2, y: r.y + r.height/2});
                        }
                    }
                }
                return JSON.stringify({found:false});
            })()
        """.trimIndent())

        val evalStr = result.getOrNull() ?: return CdpResult.Error("评估失败")
        try {
            val json = com.google.gson.JsonParser.parseString(evalStr).asJsonObject
            if (json.has("found") && json.get("found").asBoolean) {
                val x = json.get("x").asDouble
                val y = json.get("y").asDouble

                // CDP 原生鼠标点击
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

        // Cursor/agent 工具审批经常不是 “Accept all”，而是 Run/Allow/Approve。
        // 手机端“接受”按钮在这种状态下也应该推进审批，而不是只找代码 diff 的 Accept all。
        return if (autoAcceptActions()) {
            CdpResult.Success(true)
        } else {
            CdpResult.Error("未找到 Accept all 或 Run/Allow/Approve 按钮")
        }
    }

    /**
     * 点击 "Reject all" 按钮 — 拒绝所有代码变更
     */
    open suspend fun rejectAll(): CdpResult<Boolean> {
        val result = cdp.evaluate("""
            (function() {
                function docs(d) {
                    var out = [d];
                    var ifr = d.querySelectorAll('iframe');
                    for (var i = 0; i < ifr.length; i++) {
                        try { if(ifr[i].contentDocument) out.push(ifr[i].contentDocument); } catch(e){}
                    }
                    return out;
                }
                function flattenAll(root) {
                    var out = [];
                    function walk(node) {
                        if (!node) return;
                        if (node.nodeType === 1) {
                            out.push(node);
                            if (node.shadowRoot) walk(node.shadowRoot);
                        }
                        for (var c = node.firstChild; c; c = c.nextSibling) walk(c);
                    }
                    walk(root);
                    return out;
                }
                var all = docs(document);
                for (var di = 0; di < all.length; di++) {
                    var doc = all[di];
                    var flat = flattenAll(doc);
                    for (var j = 0; j < flat.length; j++) {
                        var el = flat[j];
                        var txt = (el.textContent || '').trim().toLowerCase();
                        if (txt !== 'reject' && txt !== 'reject all') continue;
                        var r = el.getBoundingClientRect();
                        if (r.width > 0 && r.height > 0) {
                            return JSON.stringify({found:true, x: r.x + r.width/2, y: r.y + r.height/2});
                        }
                    }
                }
                return JSON.stringify({found:false});
            })()
        """.trimIndent())

        val evalStr = result.getOrNull() ?: return CdpResult.Error("评估失败")
        try {
            val json = com.google.gson.JsonParser.parseString(evalStr).asJsonObject
            if (json.has("found") && json.get("found").asBoolean) {
                val x = json.get("x").asDouble
                val y = json.get("y").asDouble

                // CDP 原生鼠标点击
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
        return CdpResult.Error("未找到 Reject 按钮")
    }

    // ─────────────────── 会话控制 ───────────────────
    // 移植自: antigravity_start_new.js / antigravity_stop_session.js

    /**
     * 新建聊天会话 - 移植自 startNewChat()
     */
    open suspend fun startNewSession(): CdpResult<Unit> {
        val result = cdp.evaluate("""
            (function(){
                function docs(d) {
                    var out = [d];
                    var ifr = d.querySelectorAll('iframe');
                    for (var i = 0; i < ifr.length; i++) {
                        try {
                            var id = ifr[i].contentDocument;
                            if (id) out = out.concat(docs(id));
                        } catch (e) {}
                    }
                    return out;
                }
                
                var all = docs(document);
                
                // 策略1: 通用明确匹配 (Cursor/VSCode/Windsurf 及其 iframe)
                for (var di = 0; di < all.length; di++) {
                    var doc = all[di];
                    if (!doc || !doc.querySelectorAll) continue;
                    var btns = doc.querySelectorAll('a, button, [role="button"]');
                    for (var j = 0; j < btns.length; j++) {
                        var el = btns[j];
                        if (!el.offsetParent) continue;
                        
                        var aria = (el.getAttribute('aria-label') || '').toLowerCase();
                        var title = (el.title || '').toLowerCase();
                        var text = (el.textContent || '').trim().toLowerCase();
                        
                        // 过滤掉切换侧边栏的按钮 (Windsurf 中它在加号上方)
                        if (aria.includes('toggle') || title.includes('toggle') || 
                            aria.includes('hide') || title.includes('hide') || 
                            aria.includes('隐藏') || title.includes('隐藏') ||
                            aria.includes('secondary side bar') || title.includes('secondary side bar')) {
                            continue;
                        }

                        if (aria.includes('new chat') || aria.includes('new conversation') ||
                            title.includes('new chat') || title.includes('new conversation') ||
                            aria.includes('新建') || title.includes('新建') ||
                            aria === 'cascade' || title === 'cascade' ||
                            text === 'new chat' || text === '新建会话' ||
                            (typeof el.className === 'string' && (el.className.includes('codicon-add') || el.className.includes('codicon-plus'))) ||
                            el.getAttribute('data-tooltip-id') === 'new-conversation-tooltip' ||
                            (el.querySelector('svg.lucide-plus') !== null && aria === '' && title === '')) {
                            
                            el.click();
                            return 'clicked';
                        }
                    }
                }

                // 策略2: a.opacity-100 > div.absolute (Puppeteer 录制路径 - Antigravity 专有)
                // 放到最后，因为该选择器太泛，容易在 Windsurf 中误点到隐藏侧边栏的按钮
                var links = document.querySelectorAll('a');
                for (var i = 0; i < links.length; i++) {
                    var a = links[i];
                    if (!a.offsetParent) continue;
                    
                    var aria2 = (a.getAttribute('aria-label') || '').toLowerCase();
                    var title2 = (a.title || '').toLowerCase();
                    if (aria2.includes('toggle') || title2.includes('toggle') || 
                        aria2.includes('hide') || title2.includes('hide') || 
                        aria2.includes('隐藏') || title2.includes('隐藏')) {
                        continue;
                    }

                    if (a.className && typeof a.className === 'string' && a.className.includes('opacity-100')) {
                        var absDiv = a.querySelector('div.absolute');
                        if (absDiv) {
                            a.click();
                            return 'clicked';
                        }
                    }
                }
                
                return 'no-button';
            })()
        """.trimIndent())

        if (result.getOrNull() == "clicked") return CdpResult.Success(Unit)

        val isWindsurf = appName.contains("windsurf", ignoreCase = true)

        if (isWindsurf) {
            Log.d(TAG, "未找到新建按钮，Windsurf 使用 Option+Cmd+B 切换侧边栏")
            cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
                addProperty("type", "keyDown")
                addProperty("key", "b")
                addProperty("code", "KeyB")
                addProperty("modifiers", 5) // Meta (4) + Alt (1) = 5
                addProperty("windowsVirtualKeyCode", 66)
            })
            delay(50)
            cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
                addProperty("type", "keyUp")
                addProperty("key", "b")
                addProperty("code", "KeyB")
                addProperty("modifiers", 5)
                addProperty("windowsVirtualKeyCode", 66)
            })
        } else {
            // 降级: Cmd+Shift+L 快捷键 (Antigravity 的 New Conversation 快捷键)
            Log.d(TAG, "未找到新建按钮，使用 Cmd+Shift+L")
            cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
                addProperty("type", "keyDown")
                addProperty("key", "l")
                addProperty("code", "KeyL")
                addProperty("modifiers", 12) // Meta (4) + Shift (8) = 12
                addProperty("windowsVirtualKeyCode", 76)
            })
            delay(50)
            cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
                addProperty("type", "keyUp")
                addProperty("key", "l")
                addProperty("code", "KeyL")
                addProperty("modifiers", 12)
                addProperty("windowsVirtualKeyCode", 76)
            })
        }

        return CdpResult.Success(Unit)
    }

    /**
     * 打开历史/最近会话
     */
    open suspend fun showRecentSessions(): CdpResult<Unit> {
        val result = cdp.evaluate("""
            (function(){
                function docs(d) {
                    var out = [d];
                    var ifr = d.querySelectorAll('iframe');
                    for (var i = 0; i < ifr.length; i++) {
                        try {
                            var id = ifr[i].contentDocument;
                            if (id) out = out.concat(docs(id));
                        } catch (e) {}
                    }
                    return out;
                }
                
                var all = docs(document);
                for (var di = 0; di < all.length; di++) {
                    var doc = all[di];
                    if (!doc || !doc.querySelectorAll) continue;
                    var btns = doc.querySelectorAll('a, button, [role="button"]');
                    for (var j = 0; j < btns.length; j++) {
                        var el = btns[j];
                        if (!el.offsetParent) continue;
                        
                        var aria = (el.getAttribute('aria-label') || '').toLowerCase();
                        var title = (el.title || '').toLowerCase();
                        var text = (el.textContent || '').trim().toLowerCase();
                        
                        if (aria.includes('history') || title.includes('history') ||
                            aria.includes('recent') || title.includes('recent') ||
                            aria.includes('历史') || title.includes('历史') ||
                            text === 'history' || text === '历史记录' || text === 'recent sessions' ||
                            el.getAttribute('data-tooltip-id') === 'history-tooltip') {
                            
                            el.click();
                            return 'clicked';
                        }
                    }
                }
                
                return 'no-button';
            })()
        """.trimIndent())

        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return when (result.getOrNull()) {
            "clicked" -> CdpResult.Success(Unit)
            else -> CdpResult.Error("未找到历史记录按钮")
        }
    }

    open suspend fun switchSession(isNext: Boolean): CdpResult<Unit> {
        val script = """
            (async function() {
                // Antigravity 使用 quickinput 覆盖面板展示历史会话
                function getQuickInputItems() {
                    var container = document.querySelector('[class*="bg-quickinput-background"]');
                    if (!container) return [];
                    var items = container.querySelectorAll('div.cursor-pointer');
                    var result = [];
                    for (var i = 0; i < items.length; i++) {
                        var el = items[i];
                        var cls = (typeof el.className === 'string') ? el.className : '';
                        // 会话条目有 justify-between 和 py-2
                        if (cls.includes('justify-between') && el.offsetParent) {
                            result.push(el);
                        }
                    }
                    return result;
                }

                // 先检查面板是否已打开
                var existingItems = getQuickInputItems();
                if (existingItems.length > 0) {
                    // 找到当前高亮项 (focusBackground)
                    var activeIdx = -1;
                    for (var i = 0; i < existingItems.length; i++) {
                        var cls = (typeof existingItems[i].className === 'string') ? existingItems[i].className : '';
                        if (cls.includes('focusBackground')) { activeIdx = i; break; }
                    }
                    if (activeIdx === -1) activeIdx = 0;
                    var targetIdx = ${isNext} ? activeIdx + 1 : activeIdx - 1;
                    if (targetIdx < 0) targetIdx = existingItems.length - 1;
                    if (targetIdx >= existingItems.length) targetIdx = 0;
                    existingItems[targetIdx].click();
                    return 'clicked';
                }

                // 面板未打开，点击 history-tooltip 按钮打开
                var historyBtn = document.querySelector('a[data-tooltip-id="history-tooltip"]');
                if (!historyBtn) {
                    var btns = document.querySelectorAll('a, button, [role="button"]');
                    for (var i = 0; i < btns.length; i++) {
                        if (!btns[i].offsetParent) continue;
                        var tooltip = btns[i].getAttribute('data-tooltip-id') || '';
                        var text = (btns[i].textContent || '').trim().toLowerCase();
                        if (tooltip === 'history-tooltip' || text === 'past conversations') {
                            historyBtn = btns[i]; break;
                        }
                    }
                }
                if (!historyBtn) return 'no-button';

                historyBtn.click();
                await new Promise(r => setTimeout(r, 800));

                var items = getQuickInputItems();
                if (items.length > 0) {
                    // 当前会话通常是第一个 (focusBackground)，切换到下一个/上一个
                    var activeIdx = 0;
                    for (var i = 0; i < items.length; i++) {
                        var cls = (typeof items[i].className === 'string') ? items[i].className : '';
                        if (cls.includes('focusBackground')) { activeIdx = i; break; }
                    }
                    var targetIdx = ${isNext} ? activeIdx + 1 : activeIdx - 1;
                    if (targetIdx < 0) targetIdx = items.length - 1;
                    if (targetIdx >= items.length) targetIdx = 0;
                    items[targetIdx].click();
                    return 'clicked';
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

    open suspend fun getRecentSessionsList(): CdpResult<List<String>> {
        val script = """
            (async function() {
                // Antigravity: quickinput 覆盖面板
                function getQuickInputItems() {
                    var container = document.querySelector('[class*="bg-quickinput-background"]');
                    if (!container) return [];
                    var items = container.querySelectorAll('div.cursor-pointer');
                    var result = [];
                    for (var i = 0; i < items.length; i++) {
                        var el = items[i];
                        var cls = (typeof el.className === 'string') ? el.className : '';
                        if (cls.includes('justify-between') && el.offsetParent) {
                            result.push(el);
                        }
                    }
                    return result;
                }

                function extractTitles(items) {
                    // 解析相对时间为分钟数用于排序
                    function parseTime(t) {
                        if (!t) return 999999;
                        var m = t.match(/(\d+)\s*(sec|min|hour|hr|day|week|month|year)/i);
                        if (!m) return 999999;
                        var n = parseInt(m[1]);
                        var u = m[2].toLowerCase();
                        if (u.startsWith('sec')) return n / 60;
                        if (u.startsWith('min')) return n;
                        if (u.startsWith('h')) return n * 60;
                        if (u.startsWith('day')) return n * 1440;
                        if (u.startsWith('week')) return n * 10080;
                        if (u.startsWith('month')) return n * 43200;
                        if (u.startsWith('year')) return n * 525600;
                        return 999999;
                    }
                    var entries = [];
                    for (var i = 0; i < items.length; i++) {
                        var el = items[i];
                        var titleEl = el.children[0];
                        var timeEl = el.children[1];
                        var title = titleEl ? titleEl.textContent.trim() : '';
                        var time = timeEl ? timeEl.textContent.trim() : '';
                        if (!title) title = (el.textContent || '').trim();
                        title = title.replace(/\n/g, ' ').trim();
                        if (title.length > 50) title = title.substring(0, 50) + '...';
                        entries.push({title: title, time: time, minutes: parseTime(time), origIdx: i});
                    }
                    entries.sort(function(a, b) { return a.minutes - b.minutes; });
                    var res = [], idxMap = [];
                    for (var i = 0; i < entries.length; i++) {
                        var s = entries[i].title;
                        if (entries[i].time) s += ' · ' + entries[i].time;
                        if (s) { res.push(s); idxMap.push(entries[i].origIdx); }
                    }
                    return {sessions: res, indexMap: idxMap};
                }

                // 先检查面板是否已打开
                var existing = getQuickInputItems();
                if (existing.length > 0) {
                    var data = extractTitles(existing);
                    return JSON.stringify({status: 'found', sessions: data.sessions, indexMap: data.indexMap});
                }

                // 打开 Past Conversations 面板
                var historyBtn = document.querySelector('a[data-tooltip-id="history-tooltip"]');
                if (!historyBtn) {
                    var btns = document.querySelectorAll('a, button, [role="button"]');
                    for (var i = 0; i < btns.length; i++) {
                        if (!btns[i].offsetParent) continue;
                        var tooltip = btns[i].getAttribute('data-tooltip-id') || '';
                        var text = (btns[i].textContent || '').trim().toLowerCase();
                        if (tooltip === 'history-tooltip' || text === 'past conversations') {
                            historyBtn = btns[i]; break;
                        }
                    }
                }
                if (!historyBtn) return JSON.stringify({status: 'no-button'});

                historyBtn.click();
                await new Promise(r => setTimeout(r, 800));

                var items = getQuickInputItems();
                if (items.length > 0) {
                    var data = extractTitles(items);
                    return JSON.stringify({status: 'found', sessions: data.sessions, indexMap: data.indexMap});
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
                // 保存索引映射供 switchSessionByIndex 使用
                val idxArray = root.getAsJsonArray("indexMap")
                sessionIndexMap = idxArray?.map { it.asInt } ?: emptyList()
                CdpResult.Success(list)
            } else {
                CdpResult.Error("未找到会话列表")
            }
        } catch (e: Exception) {
            CdpResult.Error("解析会话列表失败")
        }
    }

    open suspend fun switchSessionByIndex(index: Int): CdpResult<Unit> {
        // 映射排序后的显示索引到原始 DOM 索引
        val realIndex = if (sessionIndexMap.isNotEmpty() && index < sessionIndexMap.size) {
            sessionIndexMap[index]
        } else {
            index
        }
        val script = """
            (async function() {
                // Antigravity: quickinput 覆盖面板中的会话条目
                function getQuickInputItems() {
                    var container = document.querySelector('[class*="bg-quickinput-background"]');
                    if (!container) return [];
                    var items = container.querySelectorAll('div.cursor-pointer');
                    var result = [];
                    for (var i = 0; i < items.length; i++) {
                        var el = items[i];
                        var cls = (typeof el.className === 'string') ? el.className : '';
                        if (cls.includes('justify-between') && el.offsetParent) {
                            result.push(el);
                        }
                    }
                    return result;
                }
                
                var vItems = getQuickInputItems();
                if (vItems.length > $realIndex) {
                    vItems[$realIndex].click();
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

    /**
     * 停止当前 AI 生成 - 移植自 antigravity_stop_session.js
     */
    open suspend fun stopGeneration(): CdpResult<Unit> {
        val result = cdp.evaluate("""
            (function(){
                function docs(d) {
                    var out = [d];
                    var ifr = d.querySelectorAll('iframe');
                    for (var i = 0; i < ifr.length; i++) {
                        try { if(ifr[i].contentDocument) out.push(ifr[i].contentDocument); } catch(e){}
                    }
                    return out;
                }
                
                // 1. Antigravity 专有逻辑
                var inputArea = document.getElementById('$INPUT_BOX_ID');
                if (inputArea) {
                    var stopDivs = inputArea.querySelectorAll('div.shrink-0 > div[class*="bg-gray-500"] > div');
                    for (var j = 0; j < stopDivs.length; j++) {
                        if (stopDivs[j].offsetParent) {
                            stopDivs[j].click();
                            return 'clicked';
                        }
                    }
                }
                
                // 2. 通用逻辑 (Antigravity & Cursor)
                var all = docs(document);
                for (var di = 0; di < all.length; di++) {
                    var doc = all[di];
                    if (!doc || !doc.querySelectorAll) continue;
                    
                    var btns = doc.querySelectorAll('button, div.send-with-mode span, [aria-label*="cancel" i], [aria-label*="stop" i], [class*="stop" i], [class*="cancel" i]');
                    for (var i = 0; i < btns.length; i++) {
                        var b = btns[i];
                        if (!b.offsetParent) continue;
                        var t = (b.textContent||'').trim().toLowerCase();
                        var a = (b.getAttribute('aria-label')||'').toLowerCase();
                        var c = (typeof b.className === 'string' ? b.className : '').toLowerCase();
                        if (t === 'stop' || t === '停止生成' || t === '停止会话' || t === 'cancel' || a.includes('stop') || a.includes('cancel') || a.includes('停止') || c.includes('stop-button') || c.includes('cancel-button')) {
                            b.dispatchEvent(new PointerEvent('pointerdown', {bubbles: true}));
                            b.dispatchEvent(new MouseEvent('mousedown', {bubbles: true}));
                            b.dispatchEvent(new PointerEvent('pointerup', {bubbles: true}));
                            b.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));
                            b.click();
                            return 'clicked';
                        }
                    }
                }

                return 'no-button';
            })()
        """.trimIndent())

        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return CdpResult.Success(Unit)
    }

    /**
     * 取消正在运行的任务（如长时间运行的 Step/Tool）
     * 默认实现：返回不支持的错误，子类（如 WindsurfCommands）应覆盖此方法
     */
    open suspend fun cancelRunningTask(): CdpResult<Unit> {
        return CdpResult.Error("当前 IDE 不支持取消任务功能")
    }

    /**
     * 打开 Antigravity User Settings。Models/Quota 页面在独立 CDP target 中，
     * 这里只负责把设置窗口唤出来，读取额度由上层连接 Settings target 完成。
     */
    open suspend fun showUsagePanel(): CdpResult<String> {
        val current = cdp.evaluate("""
            (function() {
                var text = document.body ? (document.body.innerText || '') : '';
                if (/MODEL QUOTA/i.test(text) || /Available AI Credits/i.test(text)) return 'models';
                if (/Settings/i.test(document.title || '')) return 'settings';
                return '';
            })()
        """.trimIndent()).getOrNull().orEmpty()
        if (current == "models") return CdpResult.Success("Antigravity Models")

        // Antigravity 菜单里的 "Open Antigravity User Settings" 绑定 Cmd+,。
        cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
            addProperty("type", "rawKeyDown")
            addProperty("key", ",")
            addProperty("code", "Comma")
            addProperty("modifiers", 4)
            addProperty("windowsVirtualKeyCode", 188)
            addProperty("nativeVirtualKeyCode", 188)
        })
        delay(50)
        cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
            addProperty("type", "keyUp")
            addProperty("key", ",")
            addProperty("code", "Comma")
            addProperty("modifiers", 4)
            addProperty("windowsVirtualKeyCode", 188)
            addProperty("nativeVirtualKeyCode", 188)
        })
        delay(700)
        return CdpResult.Success("已打开 Antigravity User Settings")
    }

    // ─────────────────── 模型切换 ───────────────────
    // 移植自: antigravity_switch_model.js

    /**
     * 获取当前使用的模型名称
     */
    open suspend fun getCurrentModel(): CdpResult<String> {
        val result = cdp.evaluate("""
            (function() {
                // 方法1: aria-label 包含当前模型名 (Antigravity)
                var btn = document.querySelector('button[aria-label^="Select model"]');
                if (btn) {
                    var aria = btn.getAttribute('aria-label') || '';
                    var match = aria.match(/current:\s*(.+)/);
                    if (match) return match[1].trim();
                    return btn.textContent.trim();
                }
                // 方法2: XPath (Antigravity)
                try {
                    var xr = document.evaluate(
                        '//*[@id="$INPUT_BOX_ID"]/div[3]/div[1]/div[3]/div/div/button',
                        document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null
                    );
                    if (xr.singleNodeValue) return xr.singleNodeValue.textContent.trim();
                } catch(e) {}
                // 方法3: Windsurf - 找包含模型名关键词的按钮
                var keywords = ['claude', 'gpt', 'gemini', 'opus', 'sonnet', 'haiku', 'o1', 'o3', 'deepseek', 'swe', 'kimi'];
                var btns = document.querySelectorAll('button');
                for (var i = 0; i < btns.length; i++) {
                    if (!btns[i].offsetParent) continue;
                    var text = (btns[i].textContent || '').trim().toLowerCase();
                    for (var k = 0; k < keywords.length; k++) {
                        if (text.indexOf(keywords[k]) >= 0 && text.length < 60) {
                            return btns[i].textContent.trim();
                        }
                    }
                }
                return '';
            })()
        """.trimIndent())

        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        val model = result.getOrNull() ?: ""
        return if (model.isNotEmpty()) CdpResult.Success(model)
        else CdpResult.Error("无法获取当前模型")
    }

    /**
     * 切换到指定模型 - 移植自 antigravity_switch_model.js (async 版)
     * 使用 awaitPromise 执行异步 JS
     */
    open suspend fun switchModel(modelName: String): CdpResult<Unit> {
        val params = JsonObject().apply {
            addProperty("expression", """
                (async function() {
                    try {
                        var input = ${com.google.gson.JsonPrimitive(modelName.lowercase()).toString()};

                        // 1. 点击模型选择下拉框
                        var dropdownXPath = '//*[@id="$INPUT_BOX_ID"]/div[3]/div[1]/div[3]/div/div/button';
                        var dropdownEl = document.evaluate(dropdownXPath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
                        if (!dropdownEl) {
                            dropdownEl = document.querySelector('button[aria-label^="Select model"]');
                        }
                        if (!dropdownEl) {
                            var keywords = ['claude', 'gpt', 'gemini', 'opus', 'sonnet', 'haiku', 'o1', 'o3', 'deepseek', 'swe', 'kimi'];
                            var allBtns = document.querySelectorAll('button');
                            for (var i = 0; i < allBtns.length; i++) {
                                if (!allBtns[i].offsetParent) continue;
                                var t = (allBtns[i].textContent || '').trim().toLowerCase();
                                for (var k = 0; k < keywords.length; k++) {
                                    if (t.indexOf(keywords[k]) >= 0 && t.length < 60) {
                                        dropdownEl = allBtns[i];
                                        break;
                                    }
                                }
                                if (dropdownEl) break;
                            }
                        }
                        if (!dropdownEl) {
                            return JSON.stringify({ok:false, err:'找不到模型选择下拉框'});
                        }

                        dropdownEl.click();
                        await new Promise(function(r) { setTimeout(r, 450); });

                        // 2. 在按钮列表中匹配目标模型
                        var btnLabel = function(b) {
                            return ((b.textContent || '') + ' ' + (b.getAttribute('aria-label') || '')).toLowerCase();
                        };
                        var btns = Array.from(document.querySelectorAll('button, [role="menuitem"], [role="option"]'));
                        var targetBtn = null;
                        var bestBtn = null;
                        var maxMatches = 0;
                        var tokens = input.replace(/[()]/g, '').split(' ').filter(Boolean);

                        for (var i = 0; i < btns.length; i++) {
                            if (!btns[i].offsetParent) continue;
                            var label = btnLabel(btns[i]);
                            var aria = btns[i].getAttribute('aria-label') || '';
                            if (aria.startsWith('Select model')) continue;
                            if (label === input || label.includes(input)) {
                                targetBtn = btns[i];
                                break;
                            }
                            var matches = 0;
                            for (var t = 0; t < tokens.length; t++) {
                                if (label.includes(tokens[t])) matches++;
                            }
                            if (matches > maxMatches && btns[i] !== dropdownEl) {
                                maxMatches = matches;
                                bestBtn = btns[i];
                            }
                        }
                        
                        if (!targetBtn && maxMatches > 0) {
                            targetBtn = bestBtn;
                        }

                        if (!targetBtn) {
                            dropdownEl.click();
                            return JSON.stringify({ok:false, err:'在列表中找不到匹配的模型: ' + input});
                        }

                        targetBtn.click();
                        return JSON.stringify({ok:true, info: (targetBtn.textContent || '').trim()});
                    } catch (e) {
                        return JSON.stringify({ok:false, err: e.message});
                    }
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
            if (json.get("ok")?.asBoolean == true) {
                val info = json.get("info")?.asString ?: ""
                Log.d(TAG, "切换模型成功: $info")
                CdpResult.Success(Unit)
            } else {
                CdpResult.Error(json.get("err")?.asString ?: "切换失败")
            }
        } catch (e: Exception) {
            CdpResult.Error("解析切换结果失败: $value")
        }
    }

    // ─────────────────── 自动重试 ───────────────────

    /**
     * 检测是否出现"服务器繁忙/限流/算力不足"错误并自动点击 Retry 按钮
     * 覆盖 Google Gemini、Claude、OpenAI 等常见错误
     */
    suspend fun checkAndRetryIfBusy(): CdpResult<Boolean> {
        val result = cdp.evaluate("""
            (function() {
                function docs(d) {
                    var out = [d];
                    var ifr = d.querySelectorAll('iframe');
                    for (var i = 0; i < ifr.length; i++) {
                        try { if(ifr[i].contentDocument) out.push(ifr[i].contentDocument); } catch(e){}
                    }
                    return out;
                }
                function findChatPanel(doc) {
                    var selectors = [
                        '.antigravity-agent-side-panel',
                        '[class*="antigravity-agent"]',
                        '[class*="interactive-session"]',
                        '[class*="aichat"]',
                        '[class*="composer"]',
                        '[class*="chat-view"]',
                        '[class*="cascade-scrollbar"]',
                        '[class*="chat-client-root"]'
                    ];
                    for (var i = 0; i < selectors.length; i++) {
                        var el = doc.querySelector(selectors[i]);
                        if (el) return el;
                    }
                    return null;
                }

                var allDocs = docs(document);
                var hasError = false;
                var errorPatterns = [
                    'server is busy', 'rate limit', 'overloaded',
                    'too many requests', 'try again later', 'capacity',
                    'internal error', 'internal server error',
                    'something went wrong', 'an error occurred',
                    'model is currently overloaded', 'the model is overloaded',
                    'resource exhausted', 'quota exceeded',
                    'service unavailable', 'temporarily unavailable',
                    'request failed', 'generation failed',
                    'unexpected error', 'failed to generate',
                    'our servers are experiencing',
                    'agent terminated due to error',
                    'terminated due to error',
                    'prompt the model to try again',
                    '服务器繁忙', '稍后重试', '请求过多', '服务不可用',
                    '内部错误', '出现错误', '算力不足', '模型繁忙',
                    'error 429', 'error 500', 'error 502', 'error 503', 'error 504'
                ];

                // 只在 AI 对话面板内搜索错误文本，不搜全页
                for (var di = 0; di < allDocs.length; di++) {
                    var chatPanel = findChatPanel(allDocs[di]);
                    if (!chatPanel) continue;
                    var panelText = (chatPanel.innerText || '').toLowerCase();
                    for (var i = 0; i < errorPatterns.length; i++) {
                        if (panelText.includes(errorPatterns[i])) {
                            hasError = true;
                            break;
                        }
                    }
                    if (hasError) break;
                }
                if (!hasError) return JSON.stringify({status:'ok'});

                // 找到错误后，在 chat panel 内尝试点击 Retry 按钮
                for (var di = 0; di < allDocs.length; di++) {
                    var chatPanel = findChatPanel(allDocs[di]);
                    if (!chatPanel) continue;
                    var buttons = chatPanel.querySelectorAll('button');
                    var retryPatterns = [
                        'retry', '重试', 'try again', 'regenerate',
                        'resend', 'resubmit', '重新生成', '再试一次'
                    ];
                    for (var j = 0; j < buttons.length; j++) {
                        var btn = buttons[j];
                        if (!btn.offsetParent) continue;
                        var btnText = (btn.textContent || '').toLowerCase().trim();
                        var btnAria = (btn.getAttribute('aria-label') || '').toLowerCase();
                        var btnTitle = (btn.title || '').toLowerCase();
                        var combined = btnText + ' ' + btnAria + ' ' + btnTitle;
                        for (var k = 0; k < retryPatterns.length; k++) {
                            if (combined.includes(retryPatterns[k])) {
                                var rect = btn.getBoundingClientRect();
                                btn.click();
                                return JSON.stringify({status:'retried', x: rect.x + rect.width/2, y: rect.y + rect.height/2});
                            }
                        }
                    }
                }
                return JSON.stringify({status:'error-no-retry-button'});
            })()
        """.trimIndent())

        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        val evalStr = result.getOrNull() ?: return CdpResult.Success(false)
        return try {
            val json = com.google.gson.JsonParser.parseString(evalStr).asJsonObject
            when (json.get("status")?.asString) {
                "retried" -> {
                    if (json.has("x") && json.has("y")) {
                        val x = json.get("x").asDouble
                        val y = json.get("y").asDouble
                        cdp.dispatchMouseEvent("mouseMoved", x, y)
                        delay(50)
                        cdp.dispatchMouseEvent("mousePressed", x, y, "left", 1)
                        delay(50)
                        cdp.dispatchMouseEvent("mouseReleased", x, y, "left", 1)
                    }
                    CdpResult.Success(true)
                }
                "error-no-retry-button" -> CdpResult.Error("检测到错误但找不到重试按钮")
                else -> CdpResult.Success(false)
            }
        } catch (e: Exception) {
            CdpResult.Error("解析重试结果失败: \${e.message}")
        }
    }

    // ─────────────────── Agent 全局规则（Customizations → Rules → Global）──────────────────
    // 通过 CDP 在侧栏打开「Customizations」，写入 Global 规则，等同远程点击保存。

    /**
     * 将 [text] 写入侧栏 **Customization → Rules → Global** 编辑框，并触发 input/change 以便 IDE 持久化。
     * 依赖 Antigravity / Windsurf 系侧栏 DOM；若反重力更新 UI，需按报错调整选择器。
     */
    open suspend fun setGlobalAgentRule(text: String): CdpResult<Unit> {
        val ruleLiteral = com.google.gson.JsonPrimitive(text).toString()
        val params = JsonObject().apply {
            addProperty("expression", """
                (async function() {
                    try {
                        var ruleText = $ruleLiteral;

                        function sleep(ms) { return new Promise(function(r) { setTimeout(r, ms); }); }

                        function findSidePanel() {
                            var a = document.getElementById('antigravity.agentSidePanelInputBox');
                            if (a) {
                                var s = a.closest('section, [class*="side"], [class*="panel"]');
                                if (s) return s;
                            }
                            return document.querySelector('.antigravity-agent-side-panel')
                                || document.querySelector('[class*="antigravity-agent"]')
                                || document.getElementById('windsurf.cascadePanel');
                        }

                        function clickElement(el) {
                            if (!el) return;
                            el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
                            el.dispatchEvent(new MouseEvent('click', { bubbles: true }));
                            if (typeof el.click === 'function') el.click();
                        }

                        function findMenuButton(panel) {
                            if (!panel) return null;
                            var buttons = Array.from(panel.querySelectorAll('button')).filter(function(b) { return b.offsetParent; });
                            if (buttons.length === 0) return null;
                            var minT = Math.min.apply(null, buttons.map(function(b) { return b.getBoundingClientRect().top; }));
                            var topRow = buttons.filter(function(b) { return Math.abs(b.getBoundingClientRect().top - minT) < 10; });
                            topRow.sort(function(a, b) { return b.getBoundingClientRect().right - a.getBoundingClientRect().right; });
                            for (var i = 0; i < topRow.length; i++) {
                                var br = topRow[i].getBoundingClientRect();
                                if (br.width < 56 && br.height < 48) return topRow[i];
                            }
                            return topRow.length ? topRow[0] : null;
                        }

                        function clickMenuItemByText(keywords) {
                            var all = document.querySelectorAll('button, [role="menuitem"], [role="option"], a, [class*="menu"] div, [class*="MenuItem"]');
                            for (var i = 0; i < all.length; i++) {
                                if (!all[i].offsetParent) continue;
                                var tx = (all[i].textContent || '').replace(/\s+/g, ' ').trim();
                                for (var k = 0; k < keywords.length; k++) {
                                    if (tx === keywords[k] || (keywords[k].length > 2 && tx.indexOf(keywords[k]) >= 0)) {
                                        clickElement(all[i]);
                                        return true;
                                    }
                                }
                            }
                            return false;
                        }

                        function clickTabIfNeeded() {
                            var tabWords = [ ['Rules', '规则'], ['Workflows', '工作流'] ];
                            for (var t = 0; t < tabWords.length; t++) {
                                var kws = tabWords[t];
                                var all = document.querySelectorAll('[role="tab"], button, [class*="tab"]');
                                for (var i = 0; i < all.length; i++) {
                                    if (!all[i].offsetParent) continue;
                                    var tx = (all[i].textContent || '').trim();
                                    for (var j = 0; j < kws.length; j++) {
                                        if (tx === kws[j] || tx.indexOf(kws[j]) === 0) {
                                            if (all[i].getAttribute('aria-selected') === 'false' || (all[i].getAttribute('data-state') && all[i].getAttribute('data-state') !== 'active')) {
                                                clickElement(all[i]);
                                            }
                                            return true;
                                        }
                                    }
                                }
                            }
                            return true;
                        }

                        function findGlobalTextarea() {
                            var i, t, p, j;
                            var labelWords = ['Global', '全局', 'Global Rule', 'Global rule'];
                            var labs = document.querySelectorAll('div, span, p, label, h1, h2, h3, h4');
                            for (i = 0; i < labs.length; i++) {
                                if (!labs[i].offsetParent) continue;
                                var te = (labs[i].textContent || '').replace(/\s+/g, ' ').trim();
                                for (t = 0; t < labelWords.length; t++) {
                                    if (te === labelWords[t] || (labelWords[t].length > 1 && te.indexOf(labelWords[t]) >= 0)) {
                                        p = labs[i];
                                        for (j = 0; j < 10 && p; j++) {
                                            var ta = p.querySelector('textarea');
                                            if (ta && ta.offsetParent) return ta;
                                            p = p.parentElement;
                                        }
                                    }
                                }
                            }
                            var tas = document.querySelectorAll('textarea');
                            var best = null, bestArea = 0;
                            for (i = 0; i < tas.length; i++) {
                                if (!tas[i].offsetParent) continue;
                                var r = tas[i].getBoundingClientRect();
                                var ar = r.width * r.height;
                                if (ar > 4000 && ar > bestArea) { best = tas[i]; bestArea = ar; }
                            }
                            return best;
                        }

                        function setTextareaValue(ta) {
                            if (!ta) return false;
                            ta.focus();
                            var proto = window.HTMLTextAreaElement ? window.HTMLTextAreaElement.prototype : null;
                            if (proto) {
                                var d = Object.getOwnPropertyDescriptor(proto, 'value');
                                if (d && d.set) d.set.call(ta, ruleText);
                            } else {
                                ta.value = ruleText;
                            }
                            ta.dispatchEvent(new Event('input', { bubbles: true }));
                            ta.dispatchEvent(new Event('change', { bubbles: true }));
                            return true;
                        }

                        var panel = findSidePanel();
                        var mbtn = findMenuButton(panel);
                        if (!mbtn) {
                            return JSON.stringify({ ok: false, err: '找不到侧栏菜单（⋯）按钮' });
                        }
                        clickElement(mbtn);
                        await sleep(400);

                        var opened = clickMenuItemByText(['Customization', 'Customizations', '自定义', 'Customize', 'Customise']);
                        if (!opened) {
                            return JSON.stringify({ ok: false, err: '未找到 Customization 菜单项，请侧栏为英文/中文与当前版本一致' });
                        }
                        await sleep(550);
                        clickTabIfNeeded();
                        await sleep(250);

                        var targetTa = findGlobalTextarea();
                        if (!setTextareaValue(targetTa)) {
                            return JSON.stringify({ ok: false, err: '未找到 Global 规则输入框' });
                        }
                        targetTa.dispatchEvent(new Event('blur', { bubbles: true }));
                        return JSON.stringify({ ok: true });
                    } catch (e) {
                        return JSON.stringify({ ok: false, err: e && e.message ? e.message : String(e) });
                    }
                })()
            """.trimIndent())
            addProperty("awaitPromise", true)
            addProperty("returnByValue", true)
            addProperty("timeout", 25000)
        }

        val result = cdp.call("Runtime.evaluate", params)
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        val data = result.getOrThrow()
        val value = data.getAsJsonObject("result")?.get("value")?.asString ?: ""
        return try {
            val json = JsonParser.parseString(value).asJsonObject
            if (json.get("ok")?.asBoolean == true) CdpResult.Success(Unit)
            else CdpResult.Error(json.get("err")?.asString ?: "保存全局规则失败")
        } catch (e: Exception) {
            CdpResult.Error("解析全局规则结果失败: $value")
        }
    }
}
