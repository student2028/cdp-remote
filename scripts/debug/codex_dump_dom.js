const WebSocket = require('ws');

const WS_URL = 'ws://127.0.0.1:9666/devtools/page/2971DE172F6F4F32D93A55BC1F14F1FB';

const ws = new WebSocket(WS_URL);
let id = 1;

function send(method, params = {}) {
    const msg = { id: id++, method, params };
    ws.send(JSON.stringify(msg));
    return msg.id;
}

const results = {};

ws.on('open', () => {
    // 1. 获取完整 DOM 树
    send('DOM.getDocument', { depth: -1, pierce: true });
});

ws.on('message', (data) => {
    const msg = JSON.parse(data.toString());
    
    if (msg.id === 1) {
        // DOM.getDocument 结果 - 保存完整DOM树
        const fs = require('fs');
        fs.writeFileSync('/tmp/codex_dom_tree.json', JSON.stringify(msg.result, null, 2));
        console.log('DOM tree saved to /tmp/codex_dom_tree.json');
        
        // 2. 获取 outerHTML
        const rootNodeId = msg.result.root.nodeId;
        send('DOM.getOuterHTML', { nodeId: rootNodeId });
    }
    
    if (msg.id === 2) {
        // outerHTML 结果
        const fs = require('fs');
        fs.writeFileSync('/tmp/codex_outer_html.html', msg.result.outerHTML || '');
        console.log('outerHTML saved to /tmp/codex_outer_html.html');
        console.log('HTML length: ' + (msg.result.outerHTML || '').length);
        
        // 3. evaluate 获取更多 DOM 信息
        send('Runtime.evaluate', { 
            expression: `(function() {
                function dumpInfo(el, depth) {
                    if (depth > 6) return null;
                    var info = {
                        tag: el.tagName,
                        id: el.id || undefined,
                        class: el.className && typeof el.className === 'string' ? el.className.substring(0, 200) : undefined,
                        role: el.getAttribute && el.getAttribute('role') || undefined,
                        type: el.getAttribute && el.getAttribute('type') || undefined,
                        placeholder: el.getAttribute && el.getAttribute('placeholder') || undefined,
                        ariaLabel: el.getAttribute && el.getAttribute('aria-label') || undefined,
                        contentEditable: el.getAttribute && el.getAttribute('contenteditable') || undefined,
                        dataTestId: el.getAttribute && el.getAttribute('data-testid') || undefined,
                        text: el.children && el.children.length === 0 ? (el.textContent || '').substring(0, 100) : undefined,
                        children: []
                    };
                    // Clean undefined
                    Object.keys(info).forEach(k => { if (info[k] === undefined || info[k] === null || info[k] === '') delete info[k]; });
                    if (el.children) {
                        for (var i = 0; i < el.children.length && i < 50; i++) {
                            var child = dumpInfo(el.children[i], depth + 1);
                            if (child) info.children.push(child);
                        }
                    }
                    if (info.children.length === 0) delete info.children;
                    return info;
                }
                return JSON.stringify(dumpInfo(document.body, 0), null, 2);
            })()`,
            returnByValue: true
        });
    }
    
    if (msg.id === 3) {
        const fs = require('fs');
        const val = msg.result && msg.result.result && msg.result.result.value;
        fs.writeFileSync('/tmp/codex_dom_structure.json', val || JSON.stringify(msg.result, null, 2));
        console.log('DOM structure saved to /tmp/codex_dom_structure.json');
        
        // 4. 获取所有交互元素
        send('Runtime.evaluate', {
            expression: `(function() {
                var items = [];
                // 找所有 input, textarea, button, [contenteditable], [role=textbox]
                var selectors = ['input', 'textarea', 'button', '[contenteditable="true"]', '[role="textbox"]', '[role="button"]', 'a[href]', '[data-testid]'];
                selectors.forEach(function(sel) {
                    var els = document.querySelectorAll(sel);
                    for (var i = 0; i < els.length; i++) {
                        var el = els[i];
                        var r = el.getBoundingClientRect();
                        items.push({
                            selector: sel,
                            tag: el.tagName,
                            id: el.id || '',
                            class: (typeof el.className === 'string' ? el.className : '').substring(0, 200),
                            role: el.getAttribute('role') || '',
                            ariaLabel: el.getAttribute('aria-label') || '',
                            placeholder: el.getAttribute('placeholder') || '',
                            type: el.getAttribute('type') || '',
                            dataTestId: el.getAttribute('data-testid') || '',
                            text: (el.textContent || '').substring(0, 80),
                            visible: r.width > 0 && r.height > 0,
                            rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }
                        });
                    }
                });
                return JSON.stringify(items, null, 2);
            })()`,
            returnByValue: true
        });
    }
    
    if (msg.id === 4) {
        const fs = require('fs');
        const val = msg.result && msg.result.result && msg.result.result.value;
        fs.writeFileSync('/tmp/codex_interactive_elements.json', val || JSON.stringify(msg.result, null, 2));
        console.log('Interactive elements saved to /tmp/codex_interactive_elements.json');
        
        // 完成
        setTimeout(() => {
            ws.close();
            process.exit(0);
        }, 500);
    }
});

ws.on('error', (e) => { console.error('WS Error:', e.message); process.exit(1); });

// 超时保护
setTimeout(() => { console.log('Timeout!'); process.exit(1); }, 15000);
