const WebSocket = require('ws');
const http = require('http');
function getTargets() {
    return new Promise((resolve, reject) => {
        http.get('http://127.0.0.1:9333/json', (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => resolve(JSON.parse(data)));
        }).on('error', reject);
    });
}
async function main() {
    const targets = await getTargets();
    const page = targets.find(t => t.type === 'page' && t.url && t.url.includes('workbench.html'));
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    let id = 1;
    function send(method, params = {}) {
        return new Promise(resolve => {
            const msgId = id++;
            ws.send(JSON.stringify({ id: msgId, method, params }));
            const listener = data => {
                const msg = JSON.parse(data.toString());
                if (msg.id === msgId) { ws.removeListener('message', listener); resolve(msg); }
            };
            ws.on('message', listener);
        });
    }
    ws.on('open', async () => {
        console.log("Sending ArrowUp");
        await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'ArrowUp', code: 'ArrowUp' });
        await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'ArrowUp', code: 'ArrowUp' });
        await new Promise(r => setTimeout(r, 100));
        console.log("Sending Enter");
        await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Enter', code: 'Enter' });
        await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter' });
        
        await new Promise(r => setTimeout(r, 1000));
        // Check active chat name
        const res = await send('Runtime.evaluate', {
            expression: `
            (function() {
                var el = document.querySelector('.flex.min-w-0.items-center.overflow-hidden.text-ellipsis.whitespace-nowrap.gap-1');
                return el ? el.textContent : 'none';
            })()
            `, returnByValue: true
        });
        console.log("Current session:", res.result.result.value);
        ws.close();
    });
}
main();
