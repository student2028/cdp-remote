/**
 * 探测 Codex 的 DOM 结构：
 * 1. 模型选择器按钮在哪里
 * 2. 页面尺寸
 * 3. 所有可见按钮的文本
 * 
 * 用法: node probe_codex.js [relay_port] [codex_cdp_port]
 * 例如: node probe_codex.js 19335 9223
 */
const http = require('http');
const WebSocket = require('ws');

const RELAY_PORT = process.argv[2] || '19335';
const CODEX_PORT = process.argv[3] || '';

async function getPages() {
    const url = CODEX_PORT
        ? `http://127.0.0.1:${RELAY_PORT}/cdp/${CODEX_PORT}/json`
        : `http://127.0.0.1:${RELAY_PORT}/json`;
    return new Promise((resolve, reject) => {
        http.get(url, res => {
            let d = ''; res.on('data', c => d += c);
            res.on('end', () => {
                try {
                    const parsed = JSON.parse(d);
                    resolve(Array.isArray(parsed) ? parsed : parsed.pages || []);
                } catch (e) { reject(e); }
            });
        }).on('error', reject);
    });
}

async function main() {
    console.log('--- Codex DOM Probe ---');
    const pages = await getPages();
    const wb = pages.find(p =>
        p.type === 'page' && p.url && (
            p.url.includes('workbench') ||
            p.url.startsWith('app://')
        ) && !p.url.includes('jetski')
    );
    if (!wb) {
        console.log('No workbench page found. Pages:', pages.map(p => ({ type: p.type, url: (p.url || '').substring(0, 80) })));
        return;
    }
    console.log('Found page:', wb.url.substring(0, 80));
    console.log('WS:', wb.webSocketDebuggerUrl);

    const ws = new WebSocket(wb.webSocketDebuggerUrl);
    let msgId = 0;

    function evaluate(expr, awaitPromise = false) {
        return new Promise((resolve, reject) => {
            const id = ++msgId;
            const handler = d => {
                const r = JSON.parse(d);
                if (r.id === id) {
                    ws.removeListener('message', handler);
                    if (r.result && r.result.result) {
                        resolve(r.result.result.value);
                    } else {
                        resolve(r.result || r);
                    }
                }
            };
            ws.on('message', handler);
            ws.send(JSON.stringify({
                id, method: 'Runtime.evaluate',
                params: { expression: expr, returnByValue: true, awaitPromise, timeout: 10000 }
            }));
        });
    }

    ws.on('open', async () => {
        try {
            // 1. 页面尺寸
            const dims = await evaluate(`window.innerWidth + 'x' + window.innerHeight`);
            console.log('\n=== 页面尺寸 ===');
            console.log(dims);

            // 2. 所有可见按钮
            const buttons = await evaluate(`(function(){
                var res = [];
                var btns = document.querySelectorAll('button, [role="button"]');
                for (var i = 0; i < btns.length; i++) {
                    if (!btns[i].offsetParent) continue;
                    var t = (btns[i].textContent || '').replace(/\\s+/g, ' ').trim();
                    var rect = btns[i].getBoundingClientRect();
                    var cls = (typeof btns[i].className === 'string') ? btns[i].className : '';
                    if (cls.length > 80) cls = cls.substring(0, 80) + '...';
                    res.push({
                        text: t.substring(0, 60),
                        tag: btns[i].tagName,
                        role: btns[i].getAttribute('role') || '',
                        aria: btns[i].getAttribute('aria-label') || '',
                        x: Math.round(rect.x), y: Math.round(rect.y),
                        w: Math.round(rect.width), h: Math.round(rect.height),
                        cls: cls.substring(0, 60)
                    });
                }
                return JSON.stringify(res);
            })()`);
            console.log('\n=== 可见按钮 ===');
            try {
                const parsed = JSON.parse(buttons);
                parsed.forEach((b, i) => {
                    console.log(`[${i}] "${b.text}" | ${b.tag} role=${b.role} aria="${b.aria}" pos=(${b.x},${b.y}) size=${b.w}x${b.h}`);
                });
            } catch (e) { console.log(buttons); }

            // 3. 找模型相关元素
            const modelElements = await evaluate(`(function(){
                var keywords = ['gpt', 'model', 'change model', '5.5', '5.4', 'extra high', 'intelligence'];
                var res = [];
                var all = document.querySelectorAll('*');
                for (var i = 0; i < all.length; i++) {
                    if (!all[i].offsetParent && all[i].tagName !== 'BODY') continue;
                    var ownText = '';
                    for (var c = 0; c < all[i].childNodes.length; c++) {
                        if (all[i].childNodes[c].nodeType === 3) ownText += all[i].childNodes[c].textContent;
                    }
                    ownText = ownText.trim().toLowerCase();
                    var fullText = (all[i].textContent || '').trim().toLowerCase();
                    if (ownText.length < 2 && fullText.length < 2) continue;
                    var match = false;
                    for (var k = 0; k < keywords.length; k++) {
                        if (ownText.indexOf(keywords[k]) >= 0 || (fullText.length < 40 && fullText.indexOf(keywords[k]) >= 0)) {
                            match = true; break;
                        }
                    }
                    if (!match) continue;
                    var rect = all[i].getBoundingClientRect();
                    res.push({
                        tag: all[i].tagName,
                        ownText: ownText.substring(0, 60),
                        fullText: fullText.substring(0, 60),
                        cls: ((typeof all[i].className === 'string') ? all[i].className : '').substring(0, 60),
                        x: Math.round(rect.x), y: Math.round(rect.y),
                        w: Math.round(rect.width), h: Math.round(rect.height)
                    });
                    if (res.length > 30) break;
                }
                return JSON.stringify(res);
            })()`);
            console.log('\n=== 模型相关元素 ===');
            try {
                const parsed = JSON.parse(modelElements);
                parsed.forEach((el, i) => {
                    console.log(`[${i}] <${el.tag}> own="${el.ownText}" full="${el.fullText}" cls="${el.cls}" pos=(${el.x},${el.y}) size=${el.w}x${el.h}`);
                });
            } catch (e) { console.log(modelElements); }

            // 4. 检查是否有 click handler 的非 button 元素（Codex 可能用 div）
            const clickables = await evaluate(`(function(){
                var res = [];
                var all = document.querySelectorAll('[onclick], [data-testid], [aria-haspopup]');
                for (var i = 0; i < all.length; i++) {
                    if (!all[i].offsetParent) continue;
                    var rect = all[i].getBoundingClientRect();
                    res.push({
                        tag: all[i].tagName,
                        text: (all[i].textContent || '').replace(/\\s+/g,' ').trim().substring(0, 60),
                        testid: all[i].getAttribute('data-testid') || '',
                        popup: all[i].getAttribute('aria-haspopup') || '',
                        x: Math.round(rect.x), y: Math.round(rect.y)
                    });
                }
                return JSON.stringify(res);
            })()`);
            console.log('\n=== aria-haspopup / data-testid 元素 ===');
            try {
                const parsed = JSON.parse(clickables);
                parsed.forEach((el, i) => {
                    console.log(`[${i}] <${el.tag}> text="${el.text}" testid="${el.testid}" popup="${el.popup}" pos=(${el.x},${el.y})`);
                });
            } catch (e) { console.log(clickables); }

        } catch (e) {
            console.error('Error:', e);
        }
        ws.close();
    });
}
main();
