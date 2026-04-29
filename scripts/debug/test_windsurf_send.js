#!/usr/bin/env node
/**
 * v21: 不管按钮状态，直接 Input.insertText + onEnter
 * 按钮 opacity 只是视觉，不影响 onEnter 内部逻辑
 */
const WebSocket = require('ws');
const WS_URL = 'ws://127.0.0.1:9444/devtools/page/E503FEBD6082A3D29654304D48918133';

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
async function evaluate(ws, expr) { const r = await cdpCall(ws, 'Runtime.evaluate', { expression: expr, returnByValue: true }); return r.result?.value; }
async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function main() {
    const ws = new WebSocket(WS_URL);
    await new Promise((resolve, reject) => { ws.on('open', resolve); ws.on('error', reject); });
    console.log('✅ 已连接\n');

    // 1. 聚焦 Lexical 输入框
    console.log('聚焦...');
    const posStr = await evaluate(ws, `
        (function() {
            var el = document.querySelector('[data-lexical-editor="true"]');
            if (!el) return '{}';
            var r = el.getBoundingClientRect();
            return JSON.stringify({x: Math.round(r.x + 10), y: Math.round(r.y + r.height/2)});
        })()
    `);
    const p = JSON.parse(posStr);
    // CDP 鼠标点击确保真实聚焦
    await cdpCall(ws, 'Input.dispatchMouseEvent', { type: 'mousePressed', x: p.x, y: p.y, button: 'left', clickCount: 1 });
    await sleep(30);
    await cdpCall(ws, 'Input.dispatchMouseEvent', { type: 'mouseReleased', x: p.x, y: p.y, button: 'left', clickCount: 1 });
    await sleep(200);

    // 2. 全选 + 删除 (CDP 键盘)
    console.log('清空...');
    await cdpCall(ws, 'Input.dispatchKeyEvent', { type: 'keyDown', key: 'a', code: 'KeyA', modifiers: 4, windowsVirtualKeyCode: 65 });
    await cdpCall(ws, 'Input.dispatchKeyEvent', { type: 'keyUp', key: 'a', code: 'KeyA', modifiers: 4, windowsVirtualKeyCode: 65 });
    await sleep(50);
    await cdpCall(ws, 'Input.dispatchKeyEvent', { type: 'keyDown', key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 });
    await cdpCall(ws, 'Input.dispatchKeyEvent', { type: 'keyUp', key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 });
    await sleep(200);

    // 3. Input.insertText
    console.log('Input.insertText "你好CDP测试"...');
    await cdpCall(ws, 'Input.insertText', { text: '你好CDP测试' });
    await sleep(300);

    // 验证
    const dom = await evaluate(ws, `document.querySelector('[data-lexical-editor="true"]')?.textContent || ''`);
    console.log('DOM: "' + dom + '"');

    // 检查 Lexical state
    const lexState = await evaluate(ws, `
        (function() {
            var el = document.querySelector('[data-lexical-editor="true"]');
            var fiberKey = Object.keys(el).find(function(k) { return k.indexOf('__reactFiber') === 0; });
            var fiber = el[fiberKey];
            var current = fiber;
            for (var j = 0; j < 25 && current; j++) {
                if (current.memoizedProps && current.memoizedProps.editor && current.memoizedProps.editor._editorState) {
                    var state = current.memoizedProps.editor._editorState;
                    return JSON.stringify(state.toJSON()).substring(0, 200);
                }
                current = current.return;
            }
            return 'no-editor';
        })()
    `);
    console.log('Lexical state:', lexState);

    // 4. 直接调 onEnter（不管按钮状态）
    console.log('\nonEnter...');
    const result = await evaluate(ws, `
        (function() {
            var el = document.querySelector('[data-lexical-editor="true"]');
            var fiberKey = Object.keys(el).find(function(k) { return k.indexOf('__reactFiber') === 0; });
            var fiber = el[fiberKey];
            var current = fiber;
            for (var j = 0; j < 30 && current; j++) {
                if (current.memoizedProps && typeof current.memoizedProps.onEnter === 'function') {
                    var e = new KeyboardEvent('keydown', {
                        key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
                        shiftKey: false, altKey: false, ctrlKey: false, metaKey: false,
                        bubbles: true, cancelable: true
                    });
                    Object.defineProperty(e, 'preventDefault', { value: function() {} });
                    Object.defineProperty(e, 'stopPropagation', { value: function() {} });
                    var ret = current.memoizedProps.onEnter(e);
                    return 'ret=' + ret;
                }
                current = current.return;
            }
            return 'no-onEnter';
        })()
    `);
    console.log('结果:', result);

    await sleep(3000);
    const final = await evaluate(ws, `document.querySelector('[data-lexical-editor="true"]')?.textContent || ''`);
    console.log('剩余: "' + final + '"');
    console.log(final.trim() === '' ? '✅ 发送成功！' : '❌ 失败');

    ws.close();
}

main().catch(e => { console.error('❌', e.message); process.exit(1); });
