const WebSocket = require('ws');
const http = require('http');
const fs = require('fs');
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
        // Find coords and click
        const res = await send('Runtime.evaluate', {
            expression: `
            (function() {
                var btns = document.querySelectorAll('a, button, [role="button"]');
                for (var i = 0; i < btns.length; i++) {
                    if (!btns[i].offsetParent) continue;
                    var svg = btns[i].querySelector('svg');
                    if (svg && svg.innerHTML.includes('9 9 0 1 0 9-9')) {
                        var rect = btns[i].getBoundingClientRect();
                        return { x: rect.x + rect.width/2, y: rect.y + rect.height/2 };
                    }
                }
                return null;
            })()
            `, returnByValue: true
        });
        const coords = res.result.result.value;
        if(coords) {
            await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: coords.x, y: coords.y, button: 'left', clickCount: 1 });
            await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: coords.x, y: coords.y, button: 'left', clickCount: 1 });
            await new Promise(r => setTimeout(r, 1000));
        }
        
        const shot = await send('Page.captureScreenshot', { format: 'png' });
        fs.writeFileSync('shot.png', Buffer.from(shot.result.data, 'base64'));
        console.log("Saved shot.png");
        ws.close();
    });
}
main();
