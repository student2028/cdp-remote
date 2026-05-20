/**
 * 深度探测 Codex (端口 9666) 的 DOM 结构
 * 重点: 目录选择、会话管理、菜单按钮
 */
const http = require('http');
const WebSocket = require('ws');

const CODEX_PORT = process.argv[2] || '9666';

async function getPages() {
    const url = `http://127.0.0.1:${CODEX_PORT}/json`;
    return new Promise((resolve, reject) => {
        http.get(url, { timeout: 5000 }, res => {
            let d = ''; res.on('data', c => d += c);
            res.on('end', () => {
                try { resolve(JSON.parse(d)); } catch (e) { reject(e); }
            });
        }).on('error', reject);
    });
}

async function main() {
    console.log('=== Codex Full DOM Probe ===');
    const pages = await getPages();
    console.log(`Found ${pages.length} pages:`);
    pages.forEach((p, i) => console.log(`  [${i}] type=${p.type} title="${p.title}" url=${(p.url || '').substring(0, 100)}`));

    // 找到主页面 (app://-/index.html)
    const mainPage = pages.find(p => p.type === 'page' && p.url && (p.url.includes('index.html') || p.url.startsWith('app://')));
    if (!mainPage) { console.log('No main page found!'); return; }

    console.log(`\nUsing: ${mainPage.title} -> ${mainPage.webSocketDebuggerUrl}`);

    const ws = new WebSocket(mainPage.webSocketDebuggerUrl);
    let msgId = 0;

    function evaluate(expr) {
        return new Promise((resolve, reject) => {
            const id = ++msgId;
            const timer = setTimeout(() => reject(new Error('timeout')), 15000);
            const handler = d => {
                const r = JSON.parse(d);
                if (r.id === id) {
                    clearTimeout(timer);
                    ws.removeListener('message', handler);
                    if (r.result?.result?.value !== undefined) resolve(r.result.result.value);
                    else if (r.result?.exceptionDetails) reject(new Error(JSON.stringify(r.result.exceptionDetails)));
                    else resolve(r.result);
                }
            };
            ws.on('message', handler);
            ws.send(JSON.stringify({
                id, method: 'Runtime.evaluate',
                params: { expression: expr, returnByValue: true, timeout: 10000 }
            }));
        });
    }

    ws.on('open', async () => {
        try {
            // 1. 页面基本信息
            const dims = await evaluate(`window.innerWidth + 'x' + window.innerHeight`);
            const title = await evaluate(`document.title`);
            const url = await evaluate(`window.location.href`);
            console.log(`\n=== 页面信息 ===`);
            console.log(`尺寸: ${dims}, 标题: ${title}, URL: ${url}`);

            // 2. 完整的 body HTML 结构（只看前几层）
            const structure = await evaluate(`(function(){
                function getTree(el, depth) {
                    if (depth > 3) return '';
                    var result = '';
                    var children = el.children;
                    for (var i = 0; i < children.length; i++) {
                        var c = children[i];
                        var tag = c.tagName.toLowerCase();
                        var id = c.id ? '#' + c.id : '';
                        var cls = c.className && typeof c.className === 'string' ? '.' + c.className.split(' ').filter(function(s){return s}).join('.') : '';
                        var role = c.getAttribute('role') ? ' role=' + c.getAttribute('role') : '';
                        var text = '';
                        for (var j = 0; j < c.childNodes.length; j++) {
                            if (c.childNodes[j].nodeType === 3) text += c.childNodes[j].textContent;
                        }
                        text = text.trim();
                        if (text.length > 50) text = text.substring(0, 50) + '...';
                        var indent = '  '.repeat(depth);
                        result += indent + '<' + tag + id + cls + role + '>' + (text ? ' "' + text + '"' : '') + '\\n';
                        result += getTree(c, depth + 1);
                    }
                    return result;
                }
                return getTree(document.body, 0);
            })()`);
            console.log(`\n=== DOM 树结构 (前3层) ===`);
            console.log(structure);

            // 3. 所有可见文本（找菜单项、按钮等）
            const visibleText = await evaluate(`(function(){
                var res = [];
                var all = document.querySelectorAll('*');
                for (var i = 0; i < all.length; i++) {
                    if (!all[i].offsetParent && all[i].tagName !== 'BODY' && all[i].tagName !== 'HTML') continue;
                    var ownText = '';
                    for (var c = 0; c < all[i].childNodes.length; c++) {
                        if (all[i].childNodes[c].nodeType === 3) ownText += all[i].childNodes[c].textContent;
                    }
                    ownText = ownText.trim();
                    if (ownText.length < 2) continue;
                    var rect = all[i].getBoundingClientRect();
                    if (rect.width === 0 || rect.height === 0) continue;
                    res.push({
                        tag: all[i].tagName,
                        text: ownText.substring(0, 80),
                        cls: (typeof all[i].className === 'string' ? all[i].className : '').substring(0, 60),
                        x: Math.round(rect.x), y: Math.round(rect.y),
                        w: Math.round(rect.width), h: Math.round(rect.height)
                    });
                }
                return JSON.stringify(res);
            })()`);
            console.log(`\n=== 所有可见文本元素 ===`);
            try {
                JSON.parse(visibleText).forEach((el, i) => {
                    console.log(`[${i}] <${el.tag}> "${el.text}" cls="${el.cls}" pos=(${el.x},${el.y}) size=${el.w}x${el.h}`);
                });
            } catch(e) { console.log(visibleText); }

            // 4. 所有可点击元素
            const clickables = await evaluate(`(function(){
                var res = [];
                var sels = 'button, [role="button"], a, [role="menuitem"], [role="tab"], [role="option"], input, select, [tabindex], [data-testid]';
                var all = document.querySelectorAll(sels);
                for (var i = 0; i < all.length; i++) {
                    var el = all[i];
                    if (!el.offsetParent && el.tagName !== 'BODY') continue;
                    var rect = el.getBoundingClientRect();
                    if (rect.width === 0 || rect.height === 0) continue;
                    res.push({
                        tag: el.tagName,
                        text: (el.textContent || '').replace(/\\s+/g,' ').trim().substring(0, 80),
                        role: el.getAttribute('role') || '',
                        aria: el.getAttribute('aria-label') || '',
                        testid: el.getAttribute('data-testid') || '',
                        tabindex: el.getAttribute('tabindex') || '',
                        type: el.type || '',
                        placeholder: el.placeholder || '',
                        cls: (typeof el.className === 'string' ? el.className : '').substring(0, 80),
                        x: Math.round(rect.x), y: Math.round(rect.y),
                        w: Math.round(rect.width), h: Math.round(rect.height)
                    });
                }
                return JSON.stringify(res);
            })()`);
            console.log(`\n=== 所有可点击/交互元素 ===`);
            try {
                JSON.parse(clickables).forEach((el, i) => {
                    var extra = [];
                    if (el.role) extra.push('role=' + el.role);
                    if (el.aria) extra.push('aria="' + el.aria + '"');
                    if (el.testid) extra.push('testid="' + el.testid + '"');
                    if (el.placeholder) extra.push('placeholder="' + el.placeholder + '"');
                    if (el.type) extra.push('type=' + el.type);
                    console.log(`[${i}] <${el.tag}> "${el.text}" ${extra.join(' ')} pos=(${el.x},${el.y}) size=${el.w}x${el.h}`);
                });
            } catch(e) { console.log(clickables); }

            // 5. 搜索与目录/项目/会话相关的关键词
            const keywords = await evaluate(`(function(){
                var keywords = ['folder', 'directory', 'project', 'session', 'workspace', 'open', 'new', 'menu',
                    'file', 'chat', 'conversation', 'path', 'browse', 'select'];
                var res = [];
                var all = document.querySelectorAll('*');
                for (var i = 0; i < all.length; i++) {
                    var text = (all[i].textContent || '').toLowerCase();
                    var aria = (all[i].getAttribute('aria-label') || '').toLowerCase();
                    var testid = (all[i].getAttribute('data-testid') || '').toLowerCase();
                    var cls = (typeof all[i].className === 'string' ? all[i].className : '').toLowerCase();
                    
                    var matched = false;
                    for (var k = 0; k < keywords.length; k++) {
                        if (aria.indexOf(keywords[k]) >= 0 || testid.indexOf(keywords[k]) >= 0 || 
                            (cls.indexOf(keywords[k]) >= 0 && cls.length < 100)) {
                            matched = true; break;
                        }
                    }
                    if (!matched) continue;
                    
                    var rect = all[i].getBoundingClientRect();
                    if (rect.width === 0 || rect.height === 0) continue;
                    res.push({
                        tag: all[i].tagName,
                        text: (all[i].textContent || '').replace(/\\s+/g,' ').trim().substring(0, 80),
                        aria: all[i].getAttribute('aria-label') || '',
                        testid: all[i].getAttribute('data-testid') || '',
                        cls: (typeof all[i].className === 'string' ? all[i].className : '').substring(0, 80),
                        x: Math.round(rect.x), y: Math.round(rect.y),
                        w: Math.round(rect.width), h: Math.round(rect.height)
                    });
                    if (res.length > 40) break;
                }
                return JSON.stringify(res);
            })()`);
            console.log(`\n=== 目录/项目/会话相关元素 ===`);
            try {
                JSON.parse(keywords).forEach((el, i) => {
                    console.log(`[${i}] <${el.tag}> "${el.text}" aria="${el.aria}" testid="${el.testid}" cls="${el.cls}" pos=(${el.x},${el.y})`);
                });
            } catch(e) { console.log(keywords); }

            // 6. 检查是否有侧边栏、导航区域
            const layouts = await evaluate(`(function(){
                var sels = ['nav', 'aside', '[role="navigation"]', '[role="sidebar"]', 
                    '[class*="sidebar"]', '[class*="panel"]', '[class*="nav"]',
                    '[class*="menu"]', '[class*="header"]', '[class*="toolbar"]',
                    '[class*="session"]', '[class*="project"]', '[class*="folder"]',
                    '[class*="directory"]', '[class*="list"]'];
                var res = [];
                for (var s = 0; s < sels.length; s++) {
                    var els = document.querySelectorAll(sels[s]);
                    for (var i = 0; i < els.length; i++) {
                        var rect = els[i].getBoundingClientRect();
                        if (rect.width < 10 || rect.height < 10) continue;
                        var ownText = '';
                        for (var c = 0; c < els[i].childNodes.length; c++) {
                            if (els[i].childNodes[c].nodeType === 3) ownText += els[i].childNodes[c].textContent;
                        }
                        res.push({
                            sel: sels[s],
                            tag: els[i].tagName,
                            cls: (typeof els[i].className === 'string' ? els[i].className : '').substring(0, 100),
                            text: ownText.trim().substring(0, 50),
                            x: Math.round(rect.x), y: Math.round(rect.y),
                            w: Math.round(rect.width), h: Math.round(rect.height)
                        });
                    }
                }
                return JSON.stringify(res);
            })()`);
            console.log(`\n=== 布局/导航元素 ===`);
            try {
                JSON.parse(layouts).forEach((el, i) => {
                    console.log(`[${i}] sel="${el.sel}" <${el.tag}> cls="${el.cls}" text="${el.text}" pos=(${el.x},${el.y}) size=${el.w}x${el.h}`);
                });
            } catch(e) { console.log(layouts); }

        } catch (e) {
            console.error('Error:', e.message || e);
        }
        ws.close();
    });

    ws.on('error', (e) => console.error('WS Error:', e.message));
}

main().catch(console.error);
