package com.cdp.remote.data.cdp

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.delay

/**
 * Windsurf IDE 专用 CDP 命令集
 *
 * 继承 [AntigravityCommands]，复用消息发送、回复获取、Accept/Reject 等通用逻辑。
 * 仅 override Windsurf 独有的差异方法：
 *   - pasteImage: Windsurf 容器选择器优先级不同
 *   - isGenerating: 额外检测 lucide-square SVG 停止按钮
 *   - stopGeneration: Windsurf 圆形停止按钮
 *   - startNewSession: Windsurf Cascade 快捷键 Cmd+L
 *   - showRecentSessions: Windsurf 时钟 SVG 按钮
 *   - switchSession / getRecentSessionsList / switchSessionByIndex: Windsurf 标签栏
 *   - getCurrentModel / switchModel: Windsurf chat-client-root 模型选择器
 */
class WindsurfCommands(cdp: ICdpClient) : AntigravityCommands(cdp, "Windsurf") {

    companion object {
        private const val TAG = "WindsurfCmds"
    }

    // ─────────────────── 发送消息 (Windsurf Lexical 编辑器) ───────────────────

    /**
     * Windsurf 使用 Lexical 编辑器 (data-lexical-editor="true")：
     * - 没有 <form>，button[type=submit] 的 .click() 无法触发发送
     * - 发送由 React fiber 上的 onEnter(KeyboardEvent) 回调控制
     * - onEnter 内部会检查 am (isGenerating)，为 true 时直接拒绝
     * - Input.insertText (CDP 原生) 能正确同步 Lexical 内部状态
     * - 清空需要用 CDP 键盘事件 (Cmd+A + Backspace)
     *
     * 通过 CDP 实测验证的完整流程：
     * 1. 检测 am 状态（用 altKey 技巧调用 onEnter 判断返回值）
     * 2. CDP 鼠标点击输入框获取真实焦点
     * 3. Cmd+A + Backspace 清空
     * 4. Input.insertText 输入文字
     * 5. onEnter(KeyboardEvent{shiftKey:false}) 触发发送
     */
    override suspend fun sendMessage(text: String): CdpResult<Unit> {
        // Step 1: 等待 isGenerating=false（最多 60 秒）
        // 用 altKey 技巧检测: onEnter({altKey:true}) 如果返回 false 说明 am=true
        Log.d(TAG, "Windsurf sendMessage: 检查生成状态...")
        for (i in 0 until 120) {
            val amCheck = cdp.evaluate("""
                (function() {
                    var el = document.querySelector('[data-lexical-editor="true"]');
                    if (!el) return 'no-lexical';
                    var fiberKey = Object.keys(el).find(function(k) { return k.indexOf('__reactFiber') === 0; });
                    if (!fiberKey) return 'no-fiber';
                    var fiber = el[fiberKey];
                    var current = fiber;
                    for (var j = 0; j < 30 && current; j++) {
                        if (current.memoizedProps && typeof current.memoizedProps.onEnter === 'function') {
                            var e = new KeyboardEvent('keydown', { key: 'Enter', altKey: true, shiftKey: false });
                            return current.memoizedProps.onEnter(e) === false ? 'generating' : 'idle';
                        }
                        current = current.return;
                    }
                    return 'idle';
                })()
            """.trimIndent())
            if (amCheck.getOrNull() != "generating") break
            if (i == 119) {
                Log.w(TAG, "等待生成完毕超时")
                return CdpResult.Error("Windsurf 仍在生成中，无法发送新消息")
            }
            delay(500)
        }

        // Step 2: CDP 鼠标点击 Lexical 输入框获取真实焦点
        val clickResult = cdp.evaluate("""
            (function() {
                var el = document.querySelector('[data-lexical-editor="true"]');
                if (!el) return 'no-lexical';
                if (!el.offsetParent) return 'not-visible';
                var r = el.getBoundingClientRect();
                return JSON.stringify({x: Math.round(r.x + 10), y: Math.round(r.y + r.height / 2)});
            })()
        """.trimIndent())
        val posJson = clickResult.getOrNull() ?: ""
        if (posJson.startsWith("{")) {
            try {
                val pos = JsonParser.parseString(posJson).asJsonObject
                val x = pos.get("x").asDouble
                val y = pos.get("y").asDouble
                cdp.call("Input.dispatchMouseEvent", JsonObject().apply {
                    addProperty("type", "mousePressed"); addProperty("x", x); addProperty("y", y)
                    addProperty("button", "left"); addProperty("clickCount", 1)
                })
                delay(30)
                cdp.call("Input.dispatchMouseEvent", JsonObject().apply {
                    addProperty("type", "mouseReleased"); addProperty("x", x); addProperty("y", y)
                    addProperty("button", "left"); addProperty("clickCount", 1)
                })
            } catch (e: Exception) {
                Log.w(TAG, "CDP 鼠标点击失败: ${e.message}")
            }
        }
        delay(100)

        // Step 3: Cmd+A + Backspace 清空（CDP 键盘事件，Lexical 能正确响应）
        cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
            addProperty("type", "keyDown"); addProperty("key", "a"); addProperty("code", "KeyA")
            addProperty("modifiers", 4); addProperty("windowsVirtualKeyCode", 65)
        })
        cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
            addProperty("type", "keyUp"); addProperty("key", "a"); addProperty("code", "KeyA")
            addProperty("modifiers", 4); addProperty("windowsVirtualKeyCode", 65)
        })
        delay(30)
        cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
            addProperty("type", "keyDown"); addProperty("key", "Backspace"); addProperty("code", "Backspace")
            addProperty("windowsVirtualKeyCode", 8)
        })
        cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
            addProperty("type", "keyUp"); addProperty("key", "Backspace"); addProperty("code", "Backspace")
            addProperty("windowsVirtualKeyCode", 8)
        })
        delay(200)

        // Step 4: Input.insertText（CDP 原生，能正确同步 Lexical 内部状态）
        val insertResult = cdp.call("Input.insertText", JsonObject().apply {
            addProperty("text", text)
        })
        if (insertResult is CdpResult.Error) {
            Log.w(TAG, "Input.insertText 失败: ${insertResult.message}")
            return CdpResult.Error("输入失败: ${insertResult.message}")
        }
        delay(300)

        // Step 5: 通过 React fiber 调用 onEnter 发送
        val sendResult = cdp.evaluate("""
            (function() {
                var el = document.querySelector('[data-lexical-editor="true"]');
                if (!el) return 'no-lexical';
                
                var fiberKey = Object.keys(el).find(function(k) { return k.indexOf('__reactFiber') === 0; });
                if (!fiberKey) return 'no-fiber';
                
                var fiber = el[fiberKey];
                var current = fiber;
                for (var j = 0; j < 30 && current; j++) {
                    if (current.memoizedProps && typeof current.memoizedProps.onEnter === 'function') {
                        var fakeEvent = new KeyboardEvent('keydown', {
                            key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
                            shiftKey: false, altKey: false, ctrlKey: false, metaKey: false,
                            bubbles: true, cancelable: true
                        });
                        try {
                            Object.defineProperty(fakeEvent, 'preventDefault', { value: function() {} });
                            Object.defineProperty(fakeEvent, 'stopPropagation', { value: function() {} });
                        } catch(e) {}
                        var ret = current.memoizedProps.onEnter(fakeEvent);
                        return ret ? 'sent' : 'rejected';
                    }
                    current = current.return;
                }
                return 'no-onEnter';
            })()
        """.trimIndent())

        val sendValue = sendResult.getOrNull() ?: ""
        Log.d(TAG, "Windsurf onEnter result: $sendValue")

        if (sendValue == "sent") {
            return CdpResult.Success(Unit)
        }

        // 降级: 如果 onEnter 不可用或被拒绝，用父类的 clickSendButton
        Log.d(TAG, "onEnter 未成功 ($sendValue)，降级到 clickSendButton")
        clickSendButton()
        return CdpResult.Success(Unit)
    }

    // ─────────────────── Action 确认 (Run / Skip) ───────────────────

    override suspend fun autoAcceptActions(): Boolean {
        return clickCascadeAction("run")
    }

    override suspend fun acceptAll(): CdpResult<Boolean> {
        if (clickCascadeAction("run")) {
            return CdpResult.Success(true)
        }
        return super.acceptAll()
    }

    override suspend fun rejectAll(): CdpResult<Boolean> {
        if (clickCascadeAction("skip")) {
            return CdpResult.Success(true)
        }
        return super.rejectAll()
    }

    private suspend fun clickCascadeAction(action: String): Boolean {
        val actionName = action.lowercase()
        val result = cdp.evaluate("""
            (function() {
                function isVisible(el) {
                    if (!el) return false;
                    var r = el.getBoundingClientRect();
                    var s = window.getComputedStyle(el);
                    return r.width > 0 && r.height > 0 && s.display !== 'none' && s.visibility !== 'hidden';
                }
                function fullClick(el) {
                    try {
                        el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, composed: true }));
                        el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, composed: true }));
                        el.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, composed: true }));
                        el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, composed: true }));
                    } catch (e) {}
                    el.click();
                }
                function normalizedText(el) {
                    return (el.innerText || el.textContent || el.value || '')
                        .trim()
                        .toLowerCase()
                        .replace(/\s+/g, ' ');
                }
                function commandText(text) {
                    return text.replace(/[^a-z]/g, '');
                }
                function matches(el) {
                    var text = normalizedText(el);
                    var cmd = commandText(text);
                    if ('$actionName' === 'run') {
                        return text === 'run' || cmd === 'run' || text === 'allow' || text === 'approve' || text === 'continue';
                    }
                    return text === 'skip' || text === 'reject' || text === 'reject all' || text === 'discard';
                }

                var roots = [];
                var panel = document.getElementById('windsurf.cascadePanel');
                if (panel) roots.push(panel);
                var chatRoot = document.querySelector('[class*="chat-client-root"]');
                if (chatRoot && chatRoot !== panel) roots.push(chatRoot);
                roots.push(document.body);

                for (var ri = 0; ri < roots.length; ri++) {
                    var buttons = roots[ri].querySelectorAll('button, [role="button"]');
                    for (var i = 0; i < buttons.length; i++) {
                        var btn = buttons[i];
                        if (!isVisible(btn)) continue;
                        if (!matches(btn)) continue;
                        fullClick(btn);
                        var r = btn.getBoundingClientRect();
                        return JSON.stringify({
                            found: true,
                            text: normalizedText(btn),
                            x: r.x + r.width / 2,
                            y: r.y + r.height / 2
                        });
                    }
                }
                return JSON.stringify({ found: false });
            })()
        """.trimIndent())

        val evalStr = result.getOrNull() ?: return false
        return runCatching {
            val json = JsonParser.parseString(evalStr).asJsonObject
            json.has("found") && json.get("found").asBoolean
        }.getOrDefault(false)
    }

    // ─────────────────── 用量面板 (Plan / Quota) ───────────────────

    override suspend fun showUsagePanel(): CdpResult<String> {
        val openResult = cdp.evaluate("""
            (function() {
                function isVisible(el) {
                    if (!el) return false;
                    var r = el.getBoundingClientRect();
                    var s = window.getComputedStyle(el);
                    return r.width > 0 && r.height > 0 && s.display !== 'none' && s.visibility !== 'hidden';
                }
                function fullClick(el) {
                    try {
                        el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, composed: true }));
                        el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, composed: true }));
                        el.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, composed: true }));
                        el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, composed: true }));
                    } catch (e) {}
                    el.click();
                }
                function quotaSummary() {
                    var plan = document.getElementById('codeium.windsurf.settings.planInfo');
                    if (plan) {
                        var label = plan.getAttribute('aria-label') || '';
                        if (label) return label;
                    }
                    var text = document.body ? (document.body.innerText || '') : '';
                    var daily = text.match(/Daily quota usage:\s*([0-9]+%)/i);
                    var weekly = text.match(/Weekly quota usage:\s*([0-9]+%)/i);
                    if (daily || weekly) {
                        return 'Daily: ' + (daily ? daily[1] : '?') + ' · Weekly: ' + (weekly ? weekly[1] : '?');
                    }
                    return '';
                }

                var planStatus = document.getElementById('codeium.windsurf.settings.planInfo');
                var clickTarget = planStatus ? (planStatus.querySelector('[role="button"], a, button') || planStatus) : null;
                if (!clickTarget || !isVisible(clickTarget)) {
                    var all = document.querySelectorAll('button, [role="button"], a, [aria-label]');
                    for (var i = 0; i < all.length; i++) {
                        if (!isVisible(all[i])) continue;
                        var t = ((all[i].textContent || '') + ' ' + (all[i].getAttribute('aria-label') || '')).toLowerCase();
                        if (t.indexOf('quota') >= 0 || t.indexOf('plan') >= 0 || t.indexOf('windsurf settings') >= 0) {
                            clickTarget = all[i];
                            break;
                        }
                    }
                }
                if (!clickTarget) return JSON.stringify({ ok: false, error: '未找到 Windsurf 用量入口' });

                var summary = quotaSummary();
                fullClick(clickTarget);
                return JSON.stringify({ ok: true, summary: summary });
            })()
        """.trimIndent())

        if (openResult is CdpResult.Error) return CdpResult.Error(openResult.message)
        val openStr = openResult.getOrNull() ?: return CdpResult.Error("无返回结果")
        val summary = try {
            val json = JsonParser.parseString(openStr).asJsonObject
            if (json.get("ok")?.asBoolean != true) {
                return CdpResult.Error(json.get("error")?.asString ?: "未找到 Windsurf 用量入口")
            }
            json.get("summary")?.asString.orEmpty()
        } catch (e: Exception) {
            return CdpResult.Error("解析失败: ${e.message}")
        }

        delay(300)
        cdp.evaluate("""
            (function() {
                function isVisible(el) {
                    if (!el) return false;
                    var r = el.getBoundingClientRect();
                    var s = window.getComputedStyle(el);
                    return r.width > 0 && r.height > 0 && s.display !== 'none' && s.visibility !== 'hidden';
                }
                var tabs = document.querySelectorAll('button, [role="tab"], [role="button"], a');
                for (var i = 0; i < tabs.length; i++) {
                    if (!isVisible(tabs[i])) continue;
                    var t = (tabs[i].innerText || tabs[i].textContent || '').trim().toLowerCase();
                    if (t === 'plan info' || t === 'plan') {
                        tabs[i].click();
                        return 'clicked';
                    }
                }
                return 'no-tab';
            })()
        """.trimIndent())

        delay(200)
        val detail = cdp.evaluate("""
            (function() {
                var text = document.body ? (document.body.innerText || '') : '';
                var daily = text.match(/Daily quota usage:\s*([0-9]+%)/i);
                var weekly = text.match(/Weekly quota usage:\s*([0-9]+%)/i);
                var resetDaily = text.match(/Resets[^\\n]*/i);
                var out = [];
                if (daily) out.push('Daily ' + daily[1]);
                if (weekly) out.push('Weekly ' + weekly[1]);
                if (resetDaily) out.push(resetDaily[0].trim());
                return out.join(' · ');
            })()
        """.trimIndent()).getOrNull().orEmpty()

        val info = detail.ifBlank { summary.ifBlank { "Windsurf Plan Info" } }
        return CdpResult.Success(info)
    }

    // ─────────────────── 图片粘贴 (Windsurf 容器优先) ───────────────────

    override suspend fun pasteImage(base64Data: String, mimeType: String, fileName: String): CdpResult<Boolean> {
        focusInput().let { if (it is CdpResult.Error) return CdpResult.Error("聚焦失败: ${it.message}") }
        delay(100)

        val chunkSize = 200_000  // 200KB per chunk; 压缩后图片 ≤500KB 仅需 ~4 次调用
        val chunks = base64Data.chunked(chunkSize)
        val initResult = cdp.evaluate("window.__pasteImageB64 = '';")
        if (initResult is CdpResult.Error) return CdpResult.Error("初始化图片传输失败: ${initResult.message}")
        for (chunk in chunks) {
            val literal = com.google.gson.JsonPrimitive(chunk).toString()
            val appendResult = cdp.evaluate("window.__pasteImageB64 += $literal;")
            if (appendResult is CdpResult.Error) {
                cdp.evaluate("window.__pasteImageB64 = null;")
                return CdpResult.Error("图片分块传输失败: ${appendResult.message}")
            }
        }

        val result = cdp.evaluate("""
            (function() {
                try {
                    var editableDiv = null;
                    // 1. Windsurf: #chat 内的 contenteditable
                    var chatDiv = document.getElementById('chat');
                    if (chatDiv) {
                        editableDiv = chatDiv.querySelector('[contenteditable="true"]');
                    }
                    // 2. Windsurf: #windsurf.cascadePanel
                    if (!editableDiv) {
                        var panel = document.getElementById('windsurf.cascadePanel');
                        if (panel) editableDiv = panel.querySelector('[contenteditable="true"]');
                    }
                    // 3. Antigravity 兼容
                    if (!editableDiv) {
                        var agBox = document.getElementById('$INPUT_BOX_ID');
                        if (agBox) editableDiv = agBox.querySelector('[contenteditable="true"]');
                    }
                    // 4. 通用: role=textbox 或 min-h- 类名的 contenteditable
                    if (!editableDiv) {
                        var ces = document.querySelectorAll('div[contenteditable="true"]');
                        for (var i = 0; i < ces.length; i++) {
                            var el = ces[i];
                            if (!el.offsetParent) continue;
                            var cls = el.className || '';
                            var role = el.getAttribute('role') || '';
                            if (role === 'textbox' || cls.indexOf('min-h-') >= 0 || cls.indexOf('outline-none') >= 0) {
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
                    
                    var binary = atob(base64);
                    var bytes = new Uint8Array(binary.length);
                    for (var i = 0; i < binary.length; i++) {
                        bytes[i] = binary.charCodeAt(i);
                    }
                    
                    var blob = new Blob([bytes], {type: '$mimeType'});
                    var file = new File([blob], '$fileName', {type: '$mimeType', lastModified: Date.now()});
                    
                    var dt = new DataTransfer();
                    dt.items.add(file);
                    
                    try {
                        var pasteEvent = new ClipboardEvent('paste', {
                            clipboardData: dt,
                            bubbles: true,
                            cancelable: true
                        });
                        editableDiv.dispatchEvent(pasteEvent);
                    } catch(e1) {
                        var dropEvent = new DragEvent('drop', {
                            dataTransfer: dt,
                            bubbles: true,
                            cancelable: true
                        });
                        editableDiv.dispatchEvent(dropEvent);
                    }
                    
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

    // ─────────────────── 停止生成 (Windsurf 圆形按钮) ───────────────────

    override suspend fun stopGeneration(): CdpResult<Unit> {
        val result = cdp.evaluate("""
            (function(){
                // 1. Windsurf 专属: 圆形停止按钮 (rounded-full, 内含 lucide-square rect)
                var panel = document.getElementById('windsurf.cascadePanel');
                if (panel) {
                    var roundBtns = panel.querySelectorAll('button[class*="rounded-full"]');
                    for (var i = 0; i < roundBtns.length; i++) {
                        var btn = roundBtns[i];
                        if (!btn.offsetParent) continue;
                        var rect = btn.querySelector('rect');
                        var svg = btn.querySelector('svg');
                        if (rect && svg) {
                            var cls = (typeof svg.className === 'object' && svg.className.baseVal) 
                                ? svg.className.baseVal : (svg.getAttribute('class') || '');
                            if (cls.includes('lucide-square') || cls.includes('square')) {
                                btn.click();
                                return 'clicked';
                            }
                        }
                    }
                    var stopBtn = panel.querySelector('button[class*="h-[20px]"][class*="w-[20px]"][class*="rounded-full"][class*="bg-ide-input-color"]');
                    if (stopBtn && stopBtn.offsetParent) {
                        stopBtn.click();
                        return 'clicked';
                    }
                }
                
                // 2. Antigravity 专有逻辑
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
                
                // 3. 通用逻辑
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

    // ─────────────────── 取消运行中的任务 (Windsurf 专属) ───────────────────

    /**
     * 取消 Windsurf Cascade 中正在运行的长时间任务（如 Step、Tool 执行）
     *
     * DOM 定位策略（按优先级）：
     * 1. `hover:text-red-500` CSS class + lucide SVG 图标 (circle+rect)
     * 2. `aria-label` 包含 cancel/stop/abort
     * 3. `title` 属性包含 cancel/stop
     * 4. 可见文本 "Cancel" / "取消"
     *
     * 注意: 选择器依赖 Windsurf Cascade 前端结构，版本更新后可能需要调整。
     * 已验证版本: Windsurf 2025.4.x
     */
    override suspend fun cancelRunningTask(): CdpResult<Unit> {
        val result = cdp.evaluate("""
            (function(){
                var panel = document.getElementById('windsurf.cascadePanel');
                if (!panel) return 'no-panel';
                
                // 策略1: hover:text-red-500 类 + SVG 图标验证（取消按钮特有样式）
                var redHoverBtns = panel.querySelectorAll('button[class*="hover:text-red-500"]');
                for (var i = 0; i < redHoverBtns.length; i++) {
                    var btn = redHoverBtns[i];
                    if (!btn.offsetParent) continue;
                    
                    var svg = btn.querySelector('svg');
                    if (!svg) continue;
                    
                    // lucide cancel icon: circle+rect 组合，或 lucide-* class
                    var svgClass = (typeof svg.className === 'object' && svg.className.baseVal) 
                        ? svg.className.baseVal : (svg.getAttribute('class') || '');
                    var hasStopIcon = (svg.querySelector('circle') && svg.querySelector('rect'))
                        || svgClass.includes('lucide')
                        || svg.querySelector('path[d*="M18 6"], path[d*="M6 18"]') != null;
                    
                    if (hasStopIcon) {
                        btn.click();
                        return 'clicked';
                    }
                }
                
                // 策略2: aria-label / title 属性匹配
                var attrBtns = panel.querySelectorAll(
                    '[aria-label*="cancel" i], [aria-label*="Cancel" i], '
                    + '[aria-label*="stop" i], [aria-label*="abort" i], '
                    + '[title*="cancel" i], [title*="Cancel" i], '
                    + '[title*="stop" i]'
                );
                for (var j = 0; j < attrBtns.length; j++) {
                    if (attrBtns[j].offsetParent) {
                        attrBtns[j].click();
                        return 'clicked';
                    }
                }
                
                // 策略3: 可见文本匹配 (最后的降级方案)
                var allBtns = panel.querySelectorAll('button');
                for (var k = 0; k < allBtns.length; k++) {
                    if (!allBtns[k].offsetParent) continue;
                    var text = (allBtns[k].textContent || '').trim().toLowerCase();
                    if (text === 'cancel' || text === '取消' || text === '取消任务') {
                        allBtns[k].click();
                        return 'clicked';
                    }
                }
                
                return 'no-cancel-btn';
            })()
        """.trimIndent())

        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return when (result.getOrNull()) {
            "clicked" -> CdpResult.Success(Unit)
            "no-panel" -> CdpResult.Error("未找到 Windsurf Cascade 面板")
            else -> CdpResult.Error("未找到取消按钮，请确认 Cascade 中有正在运行的任务")
        }
    }

    // ─────────────────── 新建会话 (Windsurf Cmd+L) ───────────────────

    override suspend fun startNewSession(): CdpResult<Unit> {
        val result = cdp.evaluate("""
            (function(){
                // Windsurf 专用: Cascade 面板顶部的 + 按钮
                var panel = document.getElementById('windsurf.cascadePanel');
                if (panel) {
                    var btns = panel.querySelectorAll('button');
                    for (var i = 0; i < btns.length; i++) {
                        var btn = btns[i];
                        if (!btn.offsetParent) continue;
                        var svg = btn.querySelector('svg.lucide-plus');
                        if (svg) {
                            var rect = btn.getBoundingClientRect();
                            if (rect.y < 100) {
                                btn.click();
                                return 'clicked';
                            }
                        }
                    }
                }
                // Windsurf: 标题栏的 Cascade 按钮
                var allBtns = document.querySelectorAll('button');
                for (var b = 0; b < allBtns.length; b++) {
                    if (!allBtns[b].offsetParent) continue;
                    var svg2 = allBtns[b].querySelector('svg.lucide-plus');
                    if (svg2) {
                        var r2 = allBtns[b].getBoundingClientRect();
                        if (r2.y < 100) {
                            allBtns[b].click();
                            return 'clicked';
                        }
                    }
                }
                var cascadeEl = document.querySelector('a[aria-label*="Cascade"], a[aria-label*="cascade"]');
                if (cascadeEl && cascadeEl.offsetParent) {
                    cascadeEl.click();
                    return 'clicked';
                }
                return 'no-button';
            })()
        """.trimIndent())

        if (result.getOrNull() == "clicked") return CdpResult.Success(Unit)

        // 降级: Cmd+L 快捷键 (Windsurf 的 New Cascade 快捷键)
        Log.d(TAG, "未找到新建按钮，使用 Cmd+L")
        cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
            addProperty("type", "keyDown")
            addProperty("key", "l")
            addProperty("code", "KeyL")
            addProperty("modifiers", 4) // Meta only
            addProperty("windowsVirtualKeyCode", 76)
        })
        delay(50)
        cdp.call("Input.dispatchKeyEvent", JsonObject().apply {
            addProperty("type", "keyUp")
            addProperty("key", "l")
            addProperty("code", "KeyL")
            addProperty("modifiers", 4)
            addProperty("windowsVirtualKeyCode", 76)
        })

        return CdpResult.Success(Unit)
    }

    // ─────────────────── 历史会话 (Windsurf SVG 时钟按钮) ───────────────────

    override suspend fun showRecentSessions(): CdpResult<Unit> {
        val result = cdp.evaluate("""
            (function() {
                var panel = document.getElementById('windsurf.cascadePanel') || document.body;
                var btns = panel.querySelectorAll('button');
                for (var i = 0; i < btns.length; i++) {
                    if (!btns[i].offsetParent) continue;
                    var paths = btns[i].querySelectorAll('path');
                    for (var p = 0; p < paths.length; p++) {
                        var d = (paths[p].getAttribute('d') || '');
                        if (d.startsWith('M3 12a9 9 0 1 0 9-9')) {
                            btns[i].click();
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

    // ─────────────────── 会话切换 (Windsurf 标签栏) ───────────────────

    override suspend fun switchSession(isNext: Boolean): CdpResult<Unit> {
        val script = """
            (function() {
                var panel = document.getElementById('windsurf.cascadePanel');
                if (!panel) return 'no-panel';
                var tabs = panel.querySelectorAll('[class*="h-[34px]"][class*="min-w-[80px]"][class*="cursor-pointer"]');
                var items = [];
                var seen = {};
                for (var i = 0; i < tabs.length; i++) {
                    var tab = tabs[i];
                    if (!tab.offsetParent) continue;
                    var text = (tab.textContent || '').trim();
                    if (!text) continue;
                    if (!seen[text]) { seen[text] = true; items.push(tab); }
                }
                if (items.length < 2) return 'no-items';
                var activeIdx = -1;
                for (var i = 0; i < items.length; i++) {
                    var cls = (typeof items[i].className === 'string') ? items[i].className : '';
                    if (cls.includes('scale-x-100') || cls.includes('border-b')) { activeIdx = i; break; }
                }
                if (activeIdx === -1) activeIdx = items.length - 1;
                var targetIdx = ${isNext} ? activeIdx + 1 : activeIdx - 1;
                if (targetIdx < 0) targetIdx = items.length - 1;
                if (targetIdx >= items.length) targetIdx = 0;
                items[targetIdx].click();
                return 'clicked';
            })()
        """.trimIndent()
        
        val result = cdp.evaluate(script)
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return when (result.getOrNull()) {
            "clicked" -> CdpResult.Success(Unit)
            else -> CdpResult.Error("未找到可切换的历史会话")
        }
    }

    override suspend fun getRecentSessionsList(): CdpResult<List<String>> {
        val script = """
            (function() {
                var panel = document.getElementById('windsurf.cascadePanel');
                if (!panel) return JSON.stringify({status: 'no-panel'});
                var tabs = panel.querySelectorAll('[class*="h-[34px]"][class*="min-w-[80px]"][class*="cursor-pointer"]');
                var sessions = [];
                var seen = {};
                for (var i = 0; i < tabs.length; i++) {
                    var tab = tabs[i];
                    if (!tab.offsetParent) continue;
                    var text = (tab.textContent || '').trim();
                    if (!text) continue;
                    if (text.length > 50) text = text.substring(0, 50) + '...';
                    if (!seen[text]) { seen[text] = true; sessions.push(text); }
                }
                if (sessions.length > 0) return JSON.stringify({status: 'found', sessions: sessions});
                return JSON.stringify({status: 'no-items'});
            })()
        """.trimIndent()
        
        val result = cdp.evaluate(script)
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        val jsonStr = result.getOrNull() ?: return CdpResult.Error("无返回结果")
        return try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
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

    override suspend fun switchSessionByIndex(index: Int): CdpResult<Unit> {
        val script = """
            (function() {
                var panel = document.getElementById('windsurf.cascadePanel');
                if (!panel) return 'no-panel';
                var tabs = panel.querySelectorAll('[class*="h-[34px]"][class*="min-w-[80px]"][class*="cursor-pointer"]');
                var items = [];
                var seen = {};
                for (var i = 0; i < tabs.length; i++) {
                    var tab = tabs[i];
                    if (!tab.offsetParent) continue;
                    var text = (tab.textContent || '').trim();
                    if (!text) continue;
                    if (!seen[text]) { seen[text] = true; items.push(tab); }
                }
                if (items.length > $index) {
                    items[$index].click();
                    return 'clicked';
                }
                return 'no-items';
            })()
        """.trimIndent()
        
        val result = cdp.evaluate(script)
        if (result is CdpResult.Error) return CdpResult.Error(result.message)
        return when (result.getOrNull()) {
            "clicked" -> CdpResult.Success(Unit)
            else -> CdpResult.Error("索引超出范围或未找到会话")
        }
    }

    // ─────────────────── 模型切换 (Windsurf cascadePanel) ───────────────────

    /**
     * Windsurf 模型选择器 DOM 结构:
     * - 当前模型按钮: cascadePanel 内, class 含 "cursor-pointer" + "flex-row" + "items-center"
     * - 模型下拉菜单项: class 含 "flex w-full flex-col gap-1" + "cursor-pointer"
     * - 菜单项结构: 第一个文本节点 = 模型名（如 "Claude Sonnet 4.6"），
     *   后续文本节点 = 标签（"Thinking", "New", "Free", "BYOK" 等）
     * - 有 "See more" 按钮可展开完整列表（46+ 模型）
     */

    override suspend fun getCurrentModel(): CdpResult<String> {
        val result = cdp.evaluate("""
            (function() {
                var panel = document.getElementById('windsurf.cascadePanel');
                if (panel) {
                    var btns = panel.querySelectorAll('button[class*="cursor-pointer"][class*="flex-row"][class*="items-center"]');
                    for (var i = 0; i < btns.length; i++) {
                        if (!btns[i].offsetParent) continue;
                        var t = (btns[i].textContent || '').trim();
                        if (t.length > 0 && t.length < 40) return t;
                    }
                }
                var roots = document.querySelectorAll('[class*="chat-client-root"]');
                for (var ri = 0; ri < roots.length; ri++) {
                    var rBtns = roots[ri].querySelectorAll('button');
                    for (var rb = 0; rb < rBtns.length; rb++) {
                        if (!rBtns[rb].offsetParent) continue;
                        var rt = (rBtns[rb].textContent || '').trim();
                        if (rt.length > 0 && rt.length < 40) {
                            var rl = rt.toLowerCase();
                            var kw = ['adaptive','claude','gpt','gemini','sonnet','opus','haiku','deepseek','o1','o3','o4','flash','swe','kimi','codestral','mistral','llama','glm','grok','minimax'];
                            for (var k = 0; k < kw.length; k++) {
                                if (rl.indexOf(kw[k]) >= 0) return rt;
                            }
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

    override suspend fun switchModel(modelName: String): CdpResult<Unit> {
        val params = JsonObject().apply {
            addProperty("expression", """
                (async function() {
                    try {
                        var input = ${com.google.gson.JsonPrimitive(modelName.lowercase())};
                        var panel = document.getElementById('windsurf.cascadePanel');
                        if (!panel) return JSON.stringify({ok:false, err:'找不到 cascadePanel'});

                        // Step 1: 找到并点击当前模型按钮（打开下拉菜单）
                        var modelBtn = null;
                        // 精确匹配: cursor-pointer + flex-row + items-center
                        var precBtns = panel.querySelectorAll('button[class*="cursor-pointer"][class*="flex-row"][class*="items-center"]');
                        for (var pb = 0; pb < precBtns.length; pb++) {
                            if (!precBtns[pb].offsetParent) continue;
                            var pt = (precBtns[pb].textContent || '').trim();
                            if (pt.length > 0 && pt.length < 40) { modelBtn = precBtns[pb]; break; }
                        }
                        // 降级: chat-client-root 内关键词匹配
                        if (!modelBtn) {
                            var kw = ['adaptive','claude','gpt','gemini','sonnet','opus','haiku','deepseek','o1','o3','o4','flash','swe','kimi','codestral','mistral','llama','glm','grok','minimax'];
                            var roots = document.querySelectorAll('[class*="chat-client-root"]');
                            for (var ri = 0; ri < roots.length && !modelBtn; ri++) {
                                var rBtns = roots[ri].querySelectorAll('button');
                                for (var rb = 0; rb < rBtns.length; rb++) {
                                    if (!rBtns[rb].offsetParent) continue;
                                    var rt = (rBtns[rb].textContent || '').trim().toLowerCase();
                                    for (var rk = 0; rk < kw.length; rk++) {
                                        if (rt.indexOf(kw[rk]) >= 0 && rt.length < 40) { modelBtn = rBtns[rb]; break; }
                                    }
                                    if (modelBtn) break;
                                }
                            }
                        }
                        if (!modelBtn) return JSON.stringify({ok:false, err:'找不到模型选择按钮'});

                        modelBtn.click();
                        await new Promise(function(r) { setTimeout(r, 500); });

                        // Step 2: 点击 "See more" 展开完整列表
                        var allBtns = panel.querySelectorAll('button');
                        for (var sm = 0; sm < allBtns.length; sm++) {
                            if (!allBtns[sm].offsetParent) continue;
                            if ((allBtns[sm].textContent || '').trim() === 'See more') {
                                allBtns[sm].click();
                                await new Promise(function(r) { setTimeout(r, 400); });
                                break;
                            }
                        }

                        // Step 3: 在菜单项中匹配模型
                        // 菜单项: class 含 "flex w-full flex-col" 的 button
                        var menuItems = panel.querySelectorAll('button[class*="flex"][class*="w-full"][class*="flex-col"]');
                        var bestMatch = null;
                        var bestScore = -1;
                        var available = [];

                        for (var mi = 0; mi < menuItems.length; mi++) {
                            var item = menuItems[mi];
                            if (!item.offsetParent) continue;
                            var rect = item.getBoundingClientRect();
                            if (rect.y < 0) continue;

                            // 提取第一个文本节点作为模型名
                            var walker = document.createTreeWalker(item, NodeFilter.SHOW_TEXT, null, false);
                            var firstText = '';
                            var node;
                            while (node = walker.nextNode()) {
                                var nt = node.textContent.trim();
                                if (nt) { firstText = nt; break; }
                            }
                            if (!firstText) continue;

                            var modelName = firstText.toLowerCase();
                            available.push(firstText);

                            // 精确匹配: 输入完全等于模型名
                            if (modelName === input) {
                                bestMatch = item;
                                bestScore = 100;
                                break;
                            }
                            // 包含匹配: 模型名包含输入
                            if (modelName.indexOf(input) >= 0 && bestScore < 50) {
                                bestMatch = item;
                                bestScore = 50;
                            }
                            // 反向包含: 输入包含模型名
                            if (input.indexOf(modelName) >= 0 && bestScore < 40) {
                                bestMatch = item;
                                bestScore = 40;
                            }
                            // 部分匹配: 按空格分词后所有词都匹配
                            if (bestScore < 30) {
                                var inputWords = input.split(/[\s\-\.]+/).filter(function(w){return w.length>0;});
                                var nameWords = modelName.split(/[\s\-\.]+/).filter(function(w){return w.length>0;});
                                var allMatch = inputWords.length > 0;
                                for (var iw = 0; iw < inputWords.length && allMatch; iw++) {
                                    var found = false;
                                    for (var nw = 0; nw < nameWords.length; nw++) {
                                        if (nameWords[nw].indexOf(inputWords[iw]) >= 0 || inputWords[iw].indexOf(nameWords[nw]) >= 0) { found = true; break; }
                                    }
                                    if (!found) allMatch = false;
                                }
                                if (allMatch) {
                                    bestMatch = item;
                                    bestScore = 30;
                                }
                            }
                        }

                        if (bestMatch) {
                            bestMatch.click();
                            // 提取点击项的第一个文本节点
                            var w2 = document.createTreeWalker(bestMatch, NodeFilter.SHOW_TEXT, null, false);
                            var clickedName = '';
                            var n2;
                            while (n2 = w2.nextNode()) { var t2 = n2.textContent.trim(); if (t2) { clickedName = t2; break; } }
                            return JSON.stringify({ok:true, info: clickedName || bestMatch.textContent.trim()});
                        }

                        // 没找到 → 关闭菜单,返回可用模型列表
                        document.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', code:'Escape', bubbles:true}));
                        return JSON.stringify({ok:false, err:'找不到匹配模型: ' + input, available: available.slice(0, 20)});
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
            if (json.get("ok")?.asBoolean == true) {
                val info = json.get("info")?.asString ?: ""
                Log.d(TAG, "Windsurf 切换模型成功: $info")
                CdpResult.Success(Unit)
            } else {
                val err = json.get("err")?.asString ?: "切换失败"
                val avail = json.getAsJsonArray("available")
                if (avail != null && avail.size() > 0) {
                    val list = (0 until avail.size()).map { avail[it].asString }
                    Log.w(TAG, "可用模型: $list")
                }
                CdpResult.Error(err)
            }
        } catch (e: Exception) {
            CdpResult.Error("解析切换结果失败: $value")
        }
    }
}
