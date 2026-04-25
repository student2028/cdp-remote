package com.cdp.remote.data.cdp

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Cursor IDE 专用 CDP 命令集
 *
 * 继承自 [AntigravityCommands]，绝大多数行为复用反重力那一套（发消息、抽回复、
 * 新建会话、停止生成、接受/拒绝改动 等）。仅针对 **模型切换** 提供 Cursor 专属实现：
 * 使用 `button.ui-model-picker__trigger` 触发菜单，并在
 * `[data-testid="model-picker-menu"]` 中（含 iframe）匹配选项。
 *
 * 对应桌面端 Skill：`~/.agents/skills/cursor/scripts/cursor_switch_model.js`。
 */
class CursorCommands(cdp: ICdpClient, appName: String = "Cursor") : AntigravityCommands(cdp, appName) {

    companion object {
        private const val TAG = "CursorCmds"
    }

    /**
     * 切换到指定模型。
     *
     * 关键 DOM（来自 Cursor 桌面端 Composer / 聊天侧栏）：
     * - 触发器：`button.ui-model-picker__trigger`
     * - 菜单容器：`[data-testid="model-picker-menu"]`
     * - 菜单项：`li[role="menuitem"].ui-menu__row`
     * - Auto 开关：`li[data-testid="auto-mode-toggle"]`（开启时会隐藏具体模型列表，
     *   切具体模型前需先关闭）
     * - 搜索框：`input[placeholder="Search models"]` / `input[aria-label="Search menu items"]`
     *
     * 由于 Composer 常寄生于 iframe，查询时必须遍历所有可达文档。
     */
    override suspend fun switchModel(modelName: String): CdpResult<Unit> {
        val params = JsonObject().apply {
            addProperty("expression", """
                (async function() {
                    try {
                        var input = ${com.google.gson.JsonPrimitive(modelName.lowercase())};

                        function allDocs() {
                            var out = [document];
                            var ifr = document.querySelectorAll('iframe');
                            for (var i = 0; i < ifr.length; i++) {
                                try { if (ifr[i].contentDocument) out.push(ifr[i].contentDocument); } catch(e) {}
                            }
                            return out;
                        }
                        function findTrigger() {
                            var docs = allDocs();
                            for (var i = 0; i < docs.length; i++) {
                                var el = docs[i].querySelector('button.ui-model-picker__trigger');
                                if (el && el.offsetParent) return el;
                            }
                            return null;
                        }
                        function findMenu() {
                            var docs = allDocs();
                            for (var i = 0; i < docs.length; i++) {
                                var m = docs[i].querySelector('[data-testid="model-picker-menu"]');
                                if (m && m.offsetParent) return m;
                            }
                            return null;
                        }

                        var trigger = findTrigger();
                        if (!trigger) {
                            return JSON.stringify({ok:false, err:'找不到 Cursor 模型选择下拉（ui-model-picker__trigger 未出现）'});
                        }

                        var ownerDoc = trigger.ownerDocument || document;
                        var wantAuto = (input === 'auto' || input.indexOf('auto') === 0);
                        trigger.click();

                        var menu = null;
                        for (var w = 0; w < 25; w++) {
                            await new Promise(function(r){ setTimeout(r, 100); });
                            menu = findMenu();
                            if (menu) break;
                        }
                        if (!menu) {
                            return JSON.stringify({ok:false, err:'Cursor 模型菜单未出现'});
                        }
                        await new Promise(function(r){ setTimeout(r, 150); });

                        function getAutoToggle() { return menu.querySelector('li[data-testid="auto-mode-toggle"]'); }
                        var autoToggle = getAutoToggle();
                        var autoOn = autoToggle ? autoToggle.getAttribute('aria-checked') === 'true' : false;

                        if (wantAuto) {
                            if (autoToggle) {
                                if (!autoOn) {
                                    autoToggle.click();
                                    await new Promise(function(r){ setTimeout(r, 200); });
                                }
                                ownerDoc.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true}));
                                return JSON.stringify({ok:true, info:'Auto'});
                            }
                        } else if (autoToggle && autoOn) {
                            autoToggle.click();
                            for (var w2 = 0; w2 < 25; w2++) {
                                await new Promise(function(r){ setTimeout(r, 100); });
                                menu = findMenu() || menu;
                                if (menu && menu.querySelector('li[role="menuitem"].ui-menu__row')) break;
                            }
                        }

                        function collectRows() {
                            var rows = [];
                            menu.querySelectorAll('li[role="menuitem"].ui-menu__row').forEach(function(li) {
                                if (li.closest('.ui-menu__search-row')) return;
                                if (li.getAttribute('data-testid') === 'max-mode-toggle') return;
                                var nameEl = li.querySelector('.ui-model-picker__item-content-name');
                                var raw = (nameEl ? nameEl.textContent : li.textContent) || '';
                                var text = raw.replace(/\s+/g, ' ').trim();
                                if (!text || /^edit${'$'}/i.test(text)) return;
                                if (text === 'Add Models') return;
                                rows.push({li: li, text: text, lower: text.toLowerCase()});
                            });
                            return rows;
                        }
                        function pick(rows) {
                            var exact = rows.find(function(r){ return r.lower === input; });
                            if (exact) return exact;
                            var starts = rows.find(function(r){ return r.lower.indexOf(input) === 0; });
                            if (starts) return starts;
                            var sub = rows.find(function(r){ return r.lower.indexOf(input) >= 0; });
                            if (sub) return sub;
                            var tokens = input.split(/\s+/).filter(Boolean);
                            return rows.find(function(r){
                                return tokens.every(function(t){ return r.lower.indexOf(t) >= 0; });
                            }) || null;
                        }

                        var rows = collectRows();
                        var choice = pick(rows);
                        if (!choice) {
                            var searchInput = menu.querySelector('input[placeholder="Search models"], input[aria-label="Search menu items"]');
                            if (searchInput) {
                                searchInput.focus();
                                searchInput.value = input;
                                searchInput.dispatchEvent(new Event('input', {bubbles:true}));
                                await new Promise(function(r){ setTimeout(r, 350); });
                                menu = findMenu() || menu;
                                rows = collectRows();
                                choice = pick(rows);
                            }
                        }
                        if (!choice) {
                            ownerDoc.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true}));
                            return JSON.stringify({
                                ok: false,
                                err: 'Cursor 列表中没有匹配「' + input + '」',
                                available: rows.map(function(r){return r.text;}).slice(0, 20)
                            });
                        }

                        choice.li.click();
                        return JSON.stringify({ok:true, info: choice.text});
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
                Log.d(TAG, "Cursor 切换模型成功: $info")
                CdpResult.Success(Unit)
            } else {
                val err = json.get("err")?.asString ?: "切换失败"
                val available = json.getAsJsonArray("available")
                val tail = if (available != null && available.size() > 0) {
                    val names = available.joinToString(", ") { it.asString }
                    "；可见模型: $names"
                } else ""
                CdpResult.Error(err + tail)
            }
        } catch (e: Exception) {
            CdpResult.Error("解析切换结果失败: $value")
        }
    }

    /**
     * 获取最后一条助手回复 — Cursor 专属覆写
     *
     * Cursor 的聊天 DOM 与 Antigravity 完全不同：
     * - 消息对容器: `.composer-human-ai-pair-container`
     * - 用户消息: `.composer-rendered-message.composer-sticky-human-message`
     * - AI 回复: `.composer-rendered-message`（不带 sticky-human 标记）
     * - 最终文本回复: 最后一个 AI `.composer-rendered-message` 中的 `.markdown-root`
     *
     * 继承的 `AntigravityCommands.getLastReply()` 查找 `[data-message-author-role]`
     * 和 `select-text` + `leading-relaxed` 类，Cursor 中不存在这些，导致常常返回空。
     */
    override suspend fun getLastReply(): CdpResult<String> {
        val result = cdp.evaluate("""
            (function() {
                try {
                    // Cursor 不用 iframe，直接在主文档中查找
                    var conv = document.querySelector('.conversations');
                    if (!conv) {
                        // 降级：尝试 composer-bar（非空）
                        conv = document.querySelector('.composer-bar:not(.empty)');
                    }
                    if (!conv) return '';

                    // 从最后一个 pair 的最后一个非人类 rendered-message 中提取 markdown
                    var pairs = conv.querySelectorAll('.composer-human-ai-pair-container');
                    for (var pi = pairs.length - 1; pi >= 0; pi--) {
                        var pair = pairs[pi];
                        var msgs = pair.querySelectorAll('.composer-rendered-message');
                        // 从后往前找最后一个非人类消息
                        for (var mi = msgs.length - 1; mi >= 0; mi--) {
                            var msg = msgs[mi];
                            // 跳过人类消息
                            var cls = (msg.className || '').toString();
                            if (cls.indexOf('composer-sticky-human-message') >= 0) continue;
                            if (msg.querySelector('.composer-human-message')) continue;
                            if (msg.querySelector('.human-message')) continue;

                            // 优先取 markdown-root 的文本（最终的文字回复）
                            var md = msg.querySelector('.markdown-root');
                            if (md) {
                                var t = (md.innerText || '').trim();
                                if (t.length > 0) return t;
                            }
                            // 否则取整个消息的文本
                            var t2 = (msg.innerText || '').trim();
                            if (t2.length > 2) return t2;
                        }
                    }

                    // 兜底：直接找最后一个 markdown-root
                    var allMd = conv.querySelectorAll('.markdown-root');
                    if (allMd.length > 0) {
                        var last = allMd[allMd.length - 1];
                        var t3 = (last.innerText || '').trim();
                        if (t3.length > 0) return t3;
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
     * 打开历史会话列表 — Cursor 专属
     * 按钮: `a[aria-label="Show Chat History"]` / class `codicon-history-two`
     */
    override suspend fun showRecentSessions(): CdpResult<Unit> {
        val result = cdp.evaluate("""
            (function() {
                // Cursor: aria-label="Show Chat History" 的按钮
                var btn = document.querySelector('a[aria-label*="Show Chat History"]');
                if (!btn) {
                    var icon = document.querySelector('.codicon-history-two');
                    if (icon) btn = icon.closest('a') || icon;
                }
                if (btn && btn.offsetParent) {
                    btn.click();
                    return 'clicked';
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

    /**
     * 获取 Cursor 最近会话列表
     * 点击 Show Chat History 后出现: 
     * - 容器: `div.composer-history-hover-menu`
     * - 列表: `ul[aria-label="Agent history"]`
     * - 条目标题: `span.compact-agent-history-react-menu-label`
     */
    override suspend fun getRecentSessionsList(): CdpResult<List<String>> {
        val script = """
            (async function() {
                // 先检查菜单是否已打开
                function getMenuItems() {
                    var labels = document.querySelectorAll('span.compact-agent-history-react-menu-label');
                    var items = [];
                    for (var i = 0; i < labels.length; i++) {
                        if (!labels[i].offsetParent) continue;
                        var text = (labels[i].textContent || '').trim();
                        if (text && text.length > 0) items.push(text);
                    }
                    return items;
                }

                var existing = getMenuItems();
                if (existing.length > 0) {
                    return JSON.stringify({status: 'found', sessions: existing});
                }

                // 点击 Show Chat History 按钮
                var btn = document.querySelector('a[aria-label*="Show Chat History"]');
                if (!btn) {
                    var icon = document.querySelector('.codicon-history-two');
                    if (icon) btn = icon.closest('a') || icon;
                }
                if (!btn || !btn.offsetParent) return JSON.stringify({status: 'no-button'});

                btn.click();
                await new Promise(r => setTimeout(r, 800));

                var items = getMenuItems();
                if (items.length > 0) {
                    return JSON.stringify({status: 'found', sessions: items});
                }
                return JSON.stringify({status: 'no-items'});
            })()
        """.trimIndent()

        val result = cdp.evaluate(script, awaitPromise = true)
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

    /**
     * 切换到上/下一个会话 — Cursor 专属
     * 在 compact-agent-history-react-menu 中选择条目
     */
    override suspend fun switchSession(isNext: Boolean): CdpResult<Unit> {
        val script = """
            (async function() {
                function getMenuLabels() {
                    var labels = document.querySelectorAll('span.compact-agent-history-react-menu-label');
                    var items = [];
                    for (var i = 0; i < labels.length; i++) {
                        if (!labels[i].offsetParent) continue;
                        items.push(labels[i]);
                    }
                    return items;
                }

                // 先检查菜单是否已打开
                var items = getMenuLabels();
                if (items.length > 0) {
                    // 点击第一个或最后一个
                    var targetIdx = ${isNext} ? 0 : items.length - 1;
                    items[targetIdx].click();
                    return 'clicked';
                }

                // 点击 Show Chat History 按钮打开菜单
                var btn = document.querySelector('a[aria-label*="Show Chat History"]');
                if (!btn) {
                    var icon = document.querySelector('.codicon-history-two');
                    if (icon) btn = icon.closest('a') || icon;
                }
                if (!btn || !btn.offsetParent) return 'no-button';

                btn.click();
                await new Promise(r => setTimeout(r, 800));

                var items2 = getMenuLabels();
                if (items2.length > 0) {
                    var targetIdx = ${isNext} ? 0 : items2.length - 1;
                    items2[targetIdx].click();
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

    /**
     * 按索引切换会话 — Cursor 专属
     * 在 compact-agent-history-react-menu 中按索引选择
     */
    override suspend fun switchSessionByIndex(index: Int): CdpResult<Unit> {
        val script = """
            (async function() {
                var labels = document.querySelectorAll('span.compact-agent-history-react-menu-label');
                var items = [];
                for (var i = 0; i < labels.length; i++) {
                    if (!labels[i].offsetParent) continue;
                    items.push(labels[i]);
                }
                if (items.length > $index) {
                    items[$index].click();
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
}
