const WebSocket = require('ws');
const fs = require('fs');
const WS_URL = 'ws://127.0.0.1:9666/devtools/page/2971DE172F6F4F32D93A55BC1F14F1FB';
const ws = new WebSocket(WS_URL);
let id = 1;
function send(method, params = {}) { ws.send(JSON.stringify({ id: id++, method, params })); }

ws.on('open', () => {
    // 点击 "查看voice7项目" (这个可能有更多对话)
    send('Runtime.evaluate', {
        expression: `(function() {
            var chatItems = document.querySelectorAll('[role="button"]');
            for (var i = 0; i < chatItems.length; i++) {
                var text = (chatItems[i].textContent || '').trim();
                if (text.indexOf('voice7') >= 0) {
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
        setTimeout(() => {
            // Dump 消息区域详细 DOM
            send('Runtime.evaluate', {
                expression: `(function() {
                    // 找到 thread 滚动区域
                    var scrollArea = document.querySelector('[data-app-action-timeline-scroll]');
                    if (!scrollArea) {
                        // 尝试其他方式
                        scrollArea = document.querySelector('.overflow-y-auto[class*="thread"]') || document.querySelector('main .overflow-y-auto');
                    }
                    if (!scrollArea) return JSON.stringify({error: 'no scroll area found'});
                    
                    function dumpDetail(el, depth) {
                        if (depth > 12 || !el) return null;
                        var r = el.getBoundingClientRect();
                        if (r.width === 0 && r.height === 0 && depth > 4) return null;
                        var info = {
                            tag: el.tagName,
                            id: el.id || undefined,
                            cls: (typeof el.className === 'string' ? el.className : '').substring(0, 200),
                            role: el.getAttribute && el.getAttribute('role') || undefined,
                            al: el.getAttribute && el.getAttribute('aria-label') || undefined,
                            ce: el.contentEditable === 'true' ? true : undefined,
                            da: Array.from(el.attributes || []).filter(a => a.name.startsWith('data-')).map(a => a.name + '=' + a.value.substring(0, 60)).join('|') || undefined,
                            txt: el.children && el.children.length === 0 ? (el.textContent || '').substring(0, 200) : undefined,
                            cc: el.children ? el.children.length : 0
                        };
                        Object.keys(info).forEach(k => { if (info[k] === undefined || info[k] === null || info[k] === '' || info[k] === 0 || info[k] === false) delete info[k]; });
                        if (el.children && el.children.length > 0 && el.children.length <= 40) {
                            info.c = [];
                            for (var i = 0; i < el.children.length; i++) {
                                var child = dumpDetail(el.children[i], depth + 1);
                                if (child) info.c.push(child);
                            }
                            if (info.c.length === 0) delete info.c;
                        }
                        return info;
                    }
                    
                    return JSON.stringify(dumpDetail(scrollArea, 0), null, 2);
                })()`,
                returnByValue: true
            });
        }, 3000);
    }
    if (msg.id === 2) {
        var val = msg.result && msg.result.result && msg.result.result.value;
        fs.writeFileSync('/tmp/codex_thread_dom.json', val || JSON.stringify(msg));
        console.log('Thread DOM saved');
        
        // 截图
        send('Page.captureScreenshot', { format: 'png', quality: 80 });
    }
    if (msg.id === 3) {
        if (msg.result && msg.result.data) {
            fs.writeFileSync('/tmp/codex_voice7_chat.png', Buffer.from(msg.result.data, 'base64'));
            console.log('Screenshot saved');
        }
        
        // 获取消息内容和角色信息
        send('Runtime.evaluate', {
            expression: `(function() {
                var scrollArea = document.querySelector('[data-app-action-timeline-scroll]') || document.querySelector('main .overflow-y-auto');
                if (!scrollArea) return JSON.stringify({error: 'no scroll'});
                
                // 找所有可能的消息块
                var results = {
                    // 方法1: data-turn-role
                    turnRoles: Array.from(scrollArea.querySelectorAll('[data-turn-role]')).map(function(el) {
                        return { role: el.getAttribute('data-turn-role'), text: (el.innerText || '').substring(0, 200), class: (typeof el.className === 'string' ? el.className : '').substring(0, 150) };
                    }),
                    // 方法2: data-message-role
                    messageRoles: Array.from(scrollArea.querySelectorAll('[data-message-role]')).map(function(el) {
                        return { role: el.getAttribute('data-message-role'), text: (el.innerText || '').substring(0, 200) };
                    }),
                    // 方法3: 包含 "user" / "assistant" 的 data 属性
                    dataAttrs: Array.from(scrollArea.querySelectorAll('*')).slice(0, 500).filter(function(el) {
                        return Array.from(el.attributes || []).some(function(a) { 
                            return a.name.startsWith('data-') && (a.value.indexOf('user') >= 0 || a.value.indexOf('assistant') >= 0 || a.value.indexOf('human') >= 0);
                        });
                    }).map(function(el) {
                        return {
                            tag: el.tagName,
                            attrs: Array.from(el.attributes).filter(a => a.name.startsWith('data-')).map(a => a.name + '=' + a.value.substring(0, 80)),
                            text: (el.innerText || '').substring(0, 100)
                        };
                    }),
                    // 方法4: 直接看 thread 的子元素
                    threadChildren: (function() {
                        var contentArea = scrollArea.querySelector('[class*="flex shrink-0 flex-col"]') || scrollArea.firstElementChild;
                        if (!contentArea) return [];
                        return Array.from(contentArea.children).slice(0, 20).map(function(el) {
                            return {
                                tag: el.tagName,
                                class: (typeof el.className === 'string' ? el.className : '').substring(0, 200),
                                text: (el.innerText || '').substring(0, 200),
                                childCount: el.children ? el.children.length : 0
                            };
                        });
                    })(),
                    // ProseMirror 输入框信息（在聊天视图中）
                    prosemirror: (function() {
                        var pm = document.querySelector('.ProseMirror');
                        if (!pm) return null;
                        var p = pm.querySelector('p');
                        return {
                            placeholder: p ? p.getAttribute('data-placeholder') : null,
                            innerHTML: pm.innerHTML.substring(0, 200),
                            parentClasses: (function() {
                                var cls = [];
                                var cur = pm.parentElement;
                                for (var i = 0; i < 3 && cur; i++) {
                                    cls.push((typeof cur.className === 'string' ? cur.className : '').substring(0, 100));
                                    cur = cur.parentElement;
                                }
                                return cls;
                            })()
                        };
                    })()
                };
                
                return JSON.stringify(results, null, 2);
            })()`,
            returnByValue: true
        });
    }
    if (msg.id === 4) {
        var val = msg.result && msg.result.result && msg.result.result.value;
        fs.writeFileSync('/tmp/codex_message_roles.json', val || JSON.stringify(msg));
        console.log('Message roles saved');
        setTimeout(() => { ws.close(); process.exit(0); }, 300);
    }
});

ws.on('error', (e) => { console.error('Error:', e.message); process.exit(1); });
setTimeout(() => { console.log('Timeout'); process.exit(1); }, 25000);
