/**
 * CDP 共享辅助函数库
 * 
 * 在首次连接时注入到页面全局作用域 (window.__cdpHelpers)。
 * 避免 AntigravityCommands.kt 中的 JS 辅助函数在每个方法中重复定义。
 */
(function () {
    if (window.__cdpHelpers) return; // 已注入，跳过

    var INPUT_BOX_ID = 'antigravity.agentSidePanelInputBox';

    window.__cdpHelpers = {
        INPUT_BOX_ID: INPUT_BOX_ID,

        /**
         * 递归遍历所有 document（包括 iframe）
         */
        allDocs: function (d) {
            var out = [d];
            var ifr = d.querySelectorAll('iframe');
            for (var i = 0; i < ifr.length; i++) {
                try {
                    var id = ifr[i].contentDocument;
                    if (id) out = out.concat(this.allDocs(id));
                } catch (e) { }
            }
            return out;
        },

        /**
         * 递归遍历所有 document，附带 iframe 偏移坐标
         */
        allDocsWithOffset: function (d) {
            var self = this;
            var out = [{ doc: d, offsetX: 0, offsetY: 0 }];
            var ifr = d.querySelectorAll('iframe, webview');
            for (var i = 0; i < ifr.length; i++) {
                try {
                    if (ifr[i].contentDocument) {
                        var offset = self.getIframeOffset(ifr[i].contentDocument);
                        out.push({ doc: ifr[i].contentDocument, offsetX: offset.x, offsetY: offset.y });
                    }
                } catch (e) { }
            }
            return out;
        },

        /**
         * 计算 iframe 相对于顶层窗口的偏移
         */
        getIframeOffset: function (doc) {
            var x = 0, y = 0;
            try {
                var currentWindow = doc.defaultView;
                while (currentWindow && currentWindow !== window) {
                    var iframes = currentWindow.parent.document.querySelectorAll('iframe, webview');
                    var found = false;
                    for (var i = 0; i < iframes.length; i++) {
                        if (iframes[i].contentWindow === currentWindow) {
                            var rect = iframes[i].getBoundingClientRect();
                            x += rect.x;
                            y += rect.y;
                            currentWindow = currentWindow.parent;
                            found = true;
                            break;
                        }
                    }
                    if (!found) break;
                }
            } catch (e) { }
            return { x: x, y: y };
        },

        /**
         * 递归展平 DOM 元素（含 Shadow DOM）
         */
        flattenElements: function (root) {
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
        },

        /**
         * 检测元素是否可见
         */
        visible: function (el) {
            if (!el) return false;
            var r = el.getBoundingClientRect();
            return r.width > 0 && r.height > 0;
        },

        /**
         * 安全获取 className 字符串
         */
        classStr: function (el) {
            var c = el.className;
            return (typeof c === 'string' ? c : (el.getAttribute && el.getAttribute('class')) || '') || '';
        },

        /**
         * 获取文档视口高度
         */
        viewportHeight: function (doc) {
            try { return doc.defaultView && doc.defaultView.innerHeight ? doc.defaultView.innerHeight : 800; }
            catch (e) { return 800; }
        },

        /**
         * 判断元素是否在聊天输入区域内
         */
        inChatInputArea: function (el) {
            if (!el || !el.closest) return false;
            var inputBox = document.getElementById(INPUT_BOX_ID);
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
        },

        /**
         * 判断元素是否在用户消息 turn 中
         */
        inUserTurn: function (el) {
            return el.closest && el.closest('[data-message-author-role="user"]');
        },

        /**
         * 判断元素是否为助手回复候选
         */
        isAssistantCandidate: function (el) {
            if (this.inChatInputArea(el) || this.inUserTurn(el)) return false;
            return true;
        },

        /**
         * 判断是否匹配 prose 样式（select-text + leading-relaxed）
         */
        matchesProse: function (el) {
            var c = this.classStr(el);
            return c.indexOf('select-text') >= 0 && c.indexOf('leading-relaxed') >= 0;
        },

        /**
         * 判断是否有 select-text 类
         */
        hasSelectText: function (el) {
            return this.classStr(el).indexOf('select-text') >= 0;
        },

        /**
         * 在面板节点中尝试提取最后一条助手回复
         */
        tryInPanel: function (panelNode) {
            var self = this;
            var flat = self.flattenElements(panelNode);
            var combo = flat.filter(function (el) {
                return self.matchesProse(el) && self.visible(el) && self.isAssistantCandidate(el);
            });
            if (combo.length) {
                var lastC = combo[combo.length - 1];
                var t = (lastC.innerText || '').trim();
                if (t.length) return t;
            }
            var all = flat.filter(function (el) {
                return self.hasSelectText(el) && self.visible(el) && self.isAssistantCandidate(el);
            });
            if (all.length) {
                var lastS = all[all.length - 1];
                var t3 = (lastS.innerText || '').trim();
                if (t3.length) return t3;
            }
            var articles = panelNode.querySelectorAll('[role="article"], [class*="markdown"], [class*="rendered-markdown"]');
            for (var j = articles.length - 1; j >= 0; j--) {
                var ar = articles[j];
                if (self.inChatInputArea(ar) || !self.visible(ar)) continue;
                var txt = (ar.innerText || '').trim();
                if (txt.length > 2) return txt;
            }
            var weak = flat.filter(function (el) {
                var c = self.classStr(el);
                if (!self.visible(el) || !self.isAssistantCandidate(el)) return false;
                return c.indexOf('break-words') >= 0 || (c.indexOf('text-sm') >= 0 && c.indexOf('flex-col') >= 0);
            });
            if (weak.length) {
                var lastW = weak[weak.length - 1];
                var tw = (lastW.innerText || '').trim();
                if (tw.length > 5) return tw;
            }
            return null;
        },

        /**
         * 获取面板节点（Antigravity / Cursor / Windsurf 通用）
         */
        findPanelNode: function (doc) {
            return doc.querySelector('.antigravity-agent-side-panel')
                || doc.querySelector('[class*="antigravity-agent"]')
                || doc.querySelector('[class*="interactive-session"]')
                || doc.querySelector('[class*="aichat"]')
                || doc.querySelector('[class*="composer"]')
                || doc.querySelector('[class*="chat-view"]')
                || doc.querySelector('[class*="cascade-scrollbar"]')
                || doc.querySelector('[class*="chat-client-root"]')
                || doc.body || doc;
        },

        /**
         * 判断元素是否为按钮（统一检测逻辑）
         */
        isButtonLike: function (el) {
            if (!el.tagName) return false;
            var tag = el.tagName.toLowerCase();
            var role = el.getAttribute('role') || '';
            var cls = (typeof el.className === 'string' ? el.className : '').toLowerCase();
            return tag === 'button' || tag === 'a' || role === 'button' || cls.includes('button') || tag.includes('button');
        },

        /**
         * 判断元素是否可见（包含 ShadowRoot 检测）
         */
        isVisibleButton: function (el) {
            return el.offsetParent || (el.getRootNode && el.getRootNode() instanceof ShadowRoot);
        },

        /**
         * 统一的 Action 按钮匹配逻辑（Run/Allow/Approve 等）
         * 返回 true 表示匹配
         */
        matchesActionButton: function (txt) {
            var lowerTxt = txt.toLowerCase().trim();
            var commandTxt = lowerTxt.replace(/[^a-z]/g, '');
            if (
                lowerTxt === 'run' ||
                commandTxt === 'run' ||
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
                return txt.length < 25 && txt.indexOf('Esc') === -1 && txt.indexOf('Cancel') === -1;
            }
            return false;
        },

        /**
         * 统一的 Stop/Cancel 按钮匹配逻辑
         */
        matchesStopButton: function (el) {
            var t = (el.textContent || '').trim().toLowerCase();
            var a = (el.getAttribute('aria-label') || '').toLowerCase();
            var c = (typeof el.className === 'string' ? el.className : '').toLowerCase();
            return t === 'stop' || t === '停止生成' || t === '停止会话' || t === 'cancel' ||
                a.includes('stop') || a.includes('cancel') || a.includes('停止') ||
                c.includes('stop-button') || c.includes('cancel-button');
        },

        /**
         * 对按钮执行全套点击事件（pointer + mouse + click）
         */
        fullClick: function (btn) {
            try {
                btn.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, composed: true }));
                btn.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, composed: true }));
                btn.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, composed: true }));
                btn.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, composed: true }));
                btn.click();
            } catch (e) { }
        }
    };
})();
