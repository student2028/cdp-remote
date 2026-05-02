#!/usr/bin/env node
/**
 * 测试 CDP 右键点击是否能在 IDE 中弹出上下文菜单
 */
const WebSocket = require('ws');
const WS_URL = 'ws://127.0.0.1:9334/devtools/page/9F239A1A8997C8B8E1AD9FE4E92B5571';

let id = 1;
function cdpCall(ws, method, params = {}) {
    return new Promise((resolve, reject) => {
        const myId = id++;
        const handler = (raw) => {
            const msg = JSON.parse(raw);
            if (msg.id === myId) { ws.removeListener('message', handler); if (msg.error) reject(new Error(JSON.stringify(msg.error))); else resolve(msg.result); }
        };
        ws.on('message', handler);
        ws.send(JSON.stringify({ id: myId, method, params }));
        setTimeout(() => { ws.removeListener('message', handler); reject(new Error('timeout')); }, 8000);
    });
}
async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function main() {
    const ws = new WebSocket(WS_URL);
    await new Promise((resolve, reject) => { ws.on('open', resolve); ws.on('error', reject); });
    console.log('✅ 已连接\n');

    // 获取页面尺寸
    const dims = await cdpCall(ws, 'Runtime.evaluate', {
        expression: `JSON.stringify({w: document.documentElement.scrollWidth, h: document.documentElement.scrollHeight})`,
        returnByValue: true
    });
    const {w, h} = JSON.parse(dims.result.value);
    console.log(`页面尺寸: ${w} x ${h}`);

    // 在页面中央右键点击
    const x = Math.round(w * 0.5);
    const y = Math.round(h * 0.5);
    console.log(`\n在 (${x}, ${y}) 处执行右键点击...`);

    // 先移动到目标位置
    console.log('  mouseMoved...');
    await cdpCall(ws, 'Input.dispatchMouseEvent', {
        type: 'mouseMoved', x, y, button: 'none', clickCount: 0
    });
    await sleep(100);

    // 右键按下
    console.log('  mousePressed (right)...');
    const pressResult = await cdpCall(ws, 'Input.dispatchMouseEvent', {
        type: 'mousePressed', x, y, button: 'right', clickCount: 1
    });
    console.log('  pressResult:', JSON.stringify(pressResult));
    await sleep(100);

    // 右键释放
    console.log('  mouseReleased (right)...');
    const releaseResult = await cdpCall(ws, 'Input.dispatchMouseEvent', {
        type: 'mouseReleased', x, y, button: 'right', clickCount: 1
    });
    console.log('  releaseResult:', JSON.stringify(releaseResult));

    // 等一下看看有没有上下文菜单
    await sleep(1000);

    // 检查是否有上下文菜单
    const checkMenu = await cdpCall(ws, 'Runtime.evaluate', {
        expression: `
            (function() {
                // 检查常见的上下文菜单 DOM
                var menus = document.querySelectorAll('[class*="context-menu"], [class*="contextmenu"], .monaco-menu-container, .shadow-root-host');
                return '找到 ' + menus.length + ' 个可能的上下文菜单元素';
            })()
        `,
        returnByValue: true
    });
    console.log('\n菜单检测:', checkMenu.result.value);

    // 用 JS contextmenu 事件试试
    console.log('\n尝试用 JS dispatchEvent(contextmenu)...');
    const jsResult = await cdpCall(ws, 'Runtime.evaluate', {
        expression: `
            (function() {
                var el = document.elementFromPoint(${x}, ${y});
                if (!el) return 'no element at point';
                var evt = new MouseEvent('contextmenu', {
                    bubbles: true, cancelable: true,
                    clientX: ${x}, clientY: ${y},
                    button: 2, buttons: 2
                });
                var cancelled = !el.dispatchEvent(evt);
                return 'target=' + el.tagName + '.' + el.className.substring(0,50) + ' cancelled=' + cancelled;
            })()
        `,
        returnByValue: true
    });
    console.log('  JS contextmenu 结果:', jsResult.result.value);

    await sleep(2000);

    // 再检查一次
    const checkMenu2 = await cdpCall(ws, 'Runtime.evaluate', {
        expression: `
            (function() {
                var menus = document.querySelectorAll('[class*="context-menu"], [class*="contextmenu"], .monaco-menu-container, .shadow-root-host, [role="menu"]');
                var texts = [];
                menus.forEach(m => texts.push(m.tagName + '.' + m.className.substring(0,60)));
                return '菜单元素: ' + texts.join(' | ') + ' (共' + menus.length + '个)';
            })()
        `,
        returnByValue: true
    });
    console.log('菜单检测2:', checkMenu2.result.value);

    ws.close();
}

main().catch(e => { console.error('❌', e.message); process.exit(1); });
