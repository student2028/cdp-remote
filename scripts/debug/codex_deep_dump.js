const WebSocket = require('ws');
const fs = require('fs');
const WS_URL = 'ws://127.0.0.1:9666/devtools/page/2971DE172F6F4F32D93A55BC1F14F1FB';
const ws = new WebSocket(WS_URL);
let id = 1;
function send(method, params = {}) { ws.send(JSON.stringify({ id: id++, method, params })); }

ws.on('open', () => {
    // 1. 深入分析输入区域
    send('Runtime.evaluate', {
        expression: `(function() {
            var pm = document.querySelector('.ProseMirror');
            if (!pm) return JSON.stringify({error: 'no ProseMirror'});
            
            // 分析 ProseMirror 及其父容器
            function getParents(el, depth) {
                var parents = [];
                var cur = el;
                for (var i = 0; i < depth && cur; i++) {
                    parents.push({
                        tag: cur.tagName,
                        id: cur.id || undefined,
                        class: (typeof cur.className === 'string' ? cur.className : '').substring(0, 300),
                        role: cur.getAttribute && cur.getAttribute('role') || undefined,
                        ariaLabel: cur.getAttribute && cur.getAttribute('aria-label') || undefined,
                        dataTestId: cur.getAttribute && cur.getAttribute('data-testid') || undefined
                    });
                    cur = cur.parentElement;
                }
                return parents;
            }
            
            // 分析 ProseMirror 自身属性
            var pmInfo = {
                tag: pm.tagName,
                class: pm.className,
                contentEditable: pm.contentEditable,
                role: pm.getAttribute('role'),
                ariaLabel: pm.getAttribute('aria-label'),
                placeholder: pm.getAttribute('data-placeholder') || pm.getAttribute('placeholder'),
                innerHTML: pm.innerHTML.substring(0, 500),
                rect: (function(r) { return {x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)}; })(pm.getBoundingClientRect()),
                allAttrs: Array.from(pm.attributes).map(a => a.name + '=' + a.value.substring(0, 100)),
                parents: getParents(pm.parentElement, 8)
            };

            // 找到输入框旁边的按钮（发送按钮区域）
            var inputContainer = pm.closest('form') || pm.closest('[class*="composer"]') || pm.parentElement.parentElement.parentElement;
            var nearbyButtons = [];
            if (inputContainer) {
                var btns = inputContainer.querySelectorAll('button');
                for (var i = 0; i < btns.length; i++) {
                    var b = btns[i];
                    var r = b.getBoundingClientRect();
                    nearbyButtons.push({
                        class: (typeof b.className === 'string' ? b.className : '').substring(0, 200),
                        ariaLabel: b.getAttribute('aria-label') || '',
                        text: (b.textContent || '').substring(0, 50),
                        visible: r.width > 0 && r.height > 0,
                        rect: {x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)}
                    });
                }
            }
            
            return JSON.stringify({
                prosemirror: pmInfo,
                inputContainerTag: inputContainer ? inputContainer.tagName : 'none',
                inputContainerClass: inputContainer ? (typeof inputContainer.className === 'string' ? inputContainer.className : '').substring(0, 300) : '',
                nearbyButtons: nearbyButtons
            }, null, 2);
        })()`,
        returnByValue: true
    });
});

ws.on('message', (data) => {
    const msg = JSON.parse(data.toString());
    
    if (msg.id === 1) {
        fs.writeFileSync('/tmp/codex_input_area.json', msg.result.result.value);
        console.log('Input area analysis saved');
        
        // 2. 分析主内容区域（聊天消息）
        send('Runtime.evaluate', {
            expression: `(function() {
                // 找 main 区域
                var main = document.querySelector('main');
                if (!main) return JSON.stringify({error: 'no main'});
                
                // 获取 main 区域的详细结构（深度8）
                function dumpDetail(el, depth) {
                    if (depth > 8 || !el) return null;
                    var r = el.getBoundingClientRect();
                    var info = {
                        tag: el.tagName,
                        id: el.id || undefined,
                        class: (typeof el.className === 'string' ? el.className : '').substring(0, 250),
                        role: el.getAttribute && el.getAttribute('role') || undefined,
                        ariaLabel: el.getAttribute && el.getAttribute('aria-label') || undefined,
                        dataTestId: el.getAttribute && el.getAttribute('data-testid') || undefined,
                        dataMessageAuthorRole: el.getAttribute && el.getAttribute('data-message-author-role') || undefined,
                        contentEditable: el.contentEditable === 'true' ? 'true' : undefined,
                        textShort: el.children && el.children.length === 0 ? (el.textContent || '').substring(0, 80) : undefined,
                        rect: {x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)},
                        children: []
                    };
                    Object.keys(info).forEach(k => { if (info[k] === undefined || info[k] === null || info[k] === '') delete info[k]; });
                    if (el.children) {
                        for (var i = 0; i < el.children.length && i < 30; i++) {
                            var child = dumpDetail(el.children[i], depth + 1);
                            if (child) info.children.push(child);
                        }
                    }
                    if (info.children && info.children.length === 0) delete info.children;
                    return info;
                }
                
                return JSON.stringify(dumpDetail(main, 0), null, 2);
            })()`,
            returnByValue: true
        });
    }
    
    if (msg.id === 2) {
        fs.writeFileSync('/tmp/codex_main_area.json', msg.result.result.value);
        console.log('Main area analysis saved');
        
        // 3. 查找所有跟聊天消息相关的元素
        send('Runtime.evaluate', {
            expression: `(function() {
                // 查找各种可能的消息容器
                var searches = {};
                
                // 查找 data-message-author-role 属性
                var roleEls = document.querySelectorAll('[data-message-author-role]');
                searches['data-message-author-role'] = Array.from(roleEls).map(function(el) {
                    return {
                        role: el.getAttribute('data-message-author-role'),
                        tag: el.tagName,
                        class: (typeof el.className === 'string' ? el.className : '').substring(0, 200),
                        text: (el.innerText || '').substring(0, 200)
                    };
                });
                
                // 查找 role="article"
                var articles = document.querySelectorAll('[role="article"]');
                searches['role-article'] = articles.length;
                
                // 查找 markdown 容器
                var markdownEls = document.querySelectorAll('[class*="markdown"]');
                searches['markdown-classes'] = Array.from(markdownEls).map(function(el) {
                    return {
                        tag: el.tagName,
                        class: (typeof el.className === 'string' ? el.className : '').substring(0, 200),
                        text: (el.innerText || '').substring(0, 100)
                    };
                });
                
                // 查找所有 data-testid
                var testIds = document.querySelectorAll('[data-testid]');
                searches['data-testids'] = Array.from(testIds).map(function(el) {
                    return el.getAttribute('data-testid');
                });
                
                // 查找 select-text 类
                var selectTexts = document.querySelectorAll('[class*="select-text"]');
                searches['select-text-count'] = selectTexts.length;
                
                // 查找 turn / message 相关类
                var turnEls = document.querySelectorAll('[class*="turn"], [class*="message"], [class*="chat-message"], [data-turn], [data-message]');
                searches['turn-message-elements'] = Array.from(turnEls).slice(0, 20).map(function(el) {
                    return {
                        tag: el.tagName,
                        class: (typeof el.className === 'string' ? el.className : '').substring(0, 200),
                        attrs: Array.from(el.attributes).filter(a => a.name.startsWith('data-')).map(a => a.name + '=' + a.value.substring(0, 50))
                    };
                });
                
                // 查找 prose 类
                var proseEls = document.querySelectorAll('[class*="prose"]');
                searches['prose-elements'] = Array.from(proseEls).slice(0, 10).map(function(el) {
                    return {
                        tag: el.tagName,
                        class: (typeof el.className === 'string' ? el.className : '').substring(0, 200)
                    };
                });
                
                return JSON.stringify(searches, null, 2);
            })()`,
            returnByValue: true
        });
    }
    
    if (msg.id === 3) {
        fs.writeFileSync('/tmp/codex_chat_elements.json', msg.result.result.value);
        console.log('Chat elements analysis saved');
        
        // 4. 截图
        send('Page.captureScreenshot', { format: 'png', quality: 80 });
    }
    
    if (msg.id === 4) {
        if (msg.result && msg.result.data) {
            fs.writeFileSync('/tmp/codex_screenshot.png', Buffer.from(msg.result.data, 'base64'));
            console.log('Screenshot saved');
        }
        setTimeout(() => { ws.close(); process.exit(0); }, 300);
    }
});

ws.on('error', (e) => { console.error('Error:', e.message); process.exit(1); });
setTimeout(() => { console.log('Timeout'); process.exit(1); }, 20000);
