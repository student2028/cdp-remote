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
        const res = await send('Runtime.evaluate', {
            expression: `
            (function() {
                // Return all div texts in the side panel
                var panel = document.getElementById('windsurf.cascadePanel') || document.querySelector('.antigravity-agent-side-panel') || document.body;
                var els = panel.querySelectorAll('div, a');
                var texts = [];
                for(var i=0; i<els.length; i++) {
                    if(els[i].offsetParent && els[i].children.length === 0 && els[i].textContent.trim().length > 0) {
                        texts.push(els[i].className + " | " + els[i].textContent.trim());
                    }
                }
                return texts;
            })()
            `, returnByValue: true
        });
        console.log(JSON.stringify(res.result.result.value, null, 2));
        ws.close();
    });
}
main();
