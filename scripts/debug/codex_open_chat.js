const WebSocket = require('ws');
const fs = require('fs');
const WS_URL = 'ws://127.0.0.1:9666/devtools/page/2971DE172F6F4F32D93A55BC1F14F1FB';
const ws = new WebSocket(WS_URL);
let id = 1;
function send(method, params = {}) { ws.send(JSON.stringify({ id: id++, method, params })); }

ws.on('open', () => {
    // 点击第一个聊天记录 "你看19336进程为什么没有啦"
    send('Runtime.evaluate', {
        expression: `(function() {
            var chatItems = document.querySelectorAll('[role="button"]');
            for (var i = 0; i < chatItems.length; i++) {
                var text = (chatItems[i].textContent || '').trim();
                if (text.indexOf('19336') >= 0 || text.indexOf('voice7') >= 0) {
                    chatItems[i].click();
                    return 'clicked: ' + text.substring(0, 60);
                }
            }
            return 'no chat found';
        })()`,
        returnByValue: true
    });
});

ws.on('message', (data) => {
    const msg = JSON.parse(data.toString());
    if (msg.id === 1) {
        console.log('Click result:', msg.result.result.value);
        
        // 等待 3 秒让聊天加载
        setTimeout(() => {
            // 截图
            send('Page.captureScreenshot', { format: 'png', quality: 80 });
        }, 3000);
    }
    if (msg.id === 2) {
        if (msg.result && msg.result.data) {
            fs.writeFileSync('/tmp/codex_chat_screenshot.png', Buffer.from(msg.result.data, 'base64'));
            console.log('Chat screenshot saved');
        }
        
        // dump 消息区域 DOM
        send('Runtime.evaluate', {
            expression: `(function() {
                var searches = {};
                
                // 1. data-message-author-role
                var roleEls = document.querySelectorAll('[data-message-author-role]');
                searches['data-message-author-role'] = Array.from(roleEls).map(function(el) {
                    return {
                        role: el.getAttribute('data-message-author-role'),
                        tag: el.tagName,
                        class: (typeof el.className === 'string' ? el.className : '').substring(0, 300),
                        text: (el.innerText || '').substring(0, 300)
                    };
                });
                
                // 2. data-testid
                var testIds = document.querySelectorAll('[data-testid]');
                searches['data-testids'] = Array.from(testIds).map(function(el) {
                    return {
                        testid: el.getAttribute('data-testid'),
                        tag: el.tagName,
                        text: (el.textContent || '').substring(0, 100)
                    };
                });
                
                // 3. markdown / rendered content
                var mdEls = document.querySelectorAll('[class*="markdown"], [class*="prose"], [class*="rendered"], [class*="message-content"]');
                searches['content-elements'] = Array.from(mdEls).slice(0, 20).map(function(el) {
                    return {
                        tag: el.tagName,
                        class: (typeof el.className === 'string' ? el.className : '').substring(0, 250),
                        text: (el.innerText || '').substring(0, 200)
                    };
                });
                
                // 4. select-text
                var stEls = document.querySelectorAll('[class*="select-text"]');
                searches['select-text'] = Array.from(stEls).slice(0, 10).map(function(el) {
                    return {
                        tag: el.tagName,
                        class: (typeof el.className === 'string' ? el.className : '').substring(0, 200),
                        text: (el.innerText || '').substring(0, 200)
                    };
                });
                
                // 5. turn / thread elements
                var turnEls = document.querySelectorAll('[class*="turn"], [data-turn], [class*="thread"], [class*="conversation"]');
                searches['turn-thread'] = Array.from(turnEls).slice(0, 15).map(function(el) {
                    return {
                        tag: el.tagName,
                        class: (typeof el.className === 'string' ? el.className : '').substring(0, 250),
                        dataAttrs: Array.from(el.attributes).filter(a => a.name.startsWith('data-')).map(a => a.name + '=' + a.value.substring(0, 80)),
                        text: (el.innerText || '').substring(0, 100)
                    };
                });
                
                // 6. role=list / listitem (消息列表可能用这些)
                var listEls = document.querySelectorAll('[role="list"], [role="listitem"], [role="log"]');
                searches['list-elements'] = Array.from(listEls).slice(0, 10).map(function(el) {
                    return {
                        tag: el.tagName,
                        role: el.getAttribute('role'),
                        class: (typeof el.className === 'string' ? el.className : '').substring(0, 200),
                        childCount: el.children.length
                    };
                });
                
                return JSON.stringify(searches, null, 2);
            })()`,
            returnByValue: true
        });
    }
    if (msg.id === 3) {
        fs.writeFileSync('/tmp/codex_chat_messages.json', msg.result.result.value);
        console.log('Chat messages analysis saved');
        
        // dump main area detail
        send('Runtime.evaluate', {
            expression: `(function() {
                var main = document.querySelector('main');
                if (!main) return JSON.stringify({error: 'no main'});
                
                function dumpDetail(el, depth) {
                    if (depth > 10 || !el) return null;
                    var r = el.getBoundingClientRect();
                    if (r.width === 0 && r.height === 0 && depth > 3) return null;
                    var info = {
                        tag: el.tagName,
                        id: el.id || undefined,
                        class: (typeof el.className === 'string' ? el.className : '').substring(0, 300),
                        role: el.getAttribute && el.getAttribute('role') || undefined,
                        ariaLabel: el.getAttribute && el.getAttribute('aria-label') || undefined,
                        dataTestId: el.getAttribute && el.getAttribute('data-testid') || undefined,
                        contentEditable: el.contentEditable === 'true' ? 'true' : undefined,
                        dataAttrs: Array.from(el.attributes || []).filter(a => a.name.startsWith('data-') && a.name !== 'data-testid').map(a => a.name + '=' + a.value.substring(0, 80)).join(', ') || undefined,
                        text: el.children && el.children.length === 0 ? (el.textContent || '').substring(0, 150) : undefined,
                        childCount: el.children ? el.children.length : 0
                    };
                    Object.keys(info).forEach(k => { if (info[k] === undefined || info[k] === null || info[k] === '' || info[k] === 0) delete info[k]; });
                    if (el.children && el.children.length > 0 && el.children.length <= 30) {
                        info.children = [];
                        for (var i = 0; i < el.children.length; i++) {
                            var child = dumpDetail(el.children[i], depth + 1);
                            if (child) info.children.push(child);
                        }
                        if (info.children.length === 0) delete info.children;
                    }
                    return info;
                }
                
                return JSON.stringify(dumpDetail(main, 0), null, 2);
            })()`,
            returnByValue: true
        });
    }
    if (msg.id === 4) {
        fs.writeFileSync('/tmp/codex_chat_main_dom.json', msg.result.result.value);
        console.log('Chat main DOM saved');
        setTimeout(() => { ws.close(); process.exit(0); }, 300);
    }
});

ws.on('error', (e) => { console.error('Error:', e.message); process.exit(1); });
setTimeout(() => { console.log('Timeout'); process.exit(1); }, 20000);
