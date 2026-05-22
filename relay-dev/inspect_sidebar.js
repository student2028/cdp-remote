const http = require('http');
const WebSocket = require('ws');

async function main() {
    try {
        const pages = await new Promise((resolve, reject) => {
            http.get('http://127.0.0.1:9333/json', res => {
                let d='';
                res.on('data', c => d += c);
                res.on('end', () => {
                    try {
                        resolve(JSON.parse(d));
                    } catch(e) {
                        reject(e);
                    }
                });
            }).on('error', reject);
        });
        const wb = pages.find(p => p.type==='page');
        if (!wb) return;
        
        const ws = new WebSocket(wb.webSocketDebuggerUrl);
        ws.on('open', () => {
            const expr = `(function(){
                var results = {};
                try {
                    var sections = document.querySelectorAll('div[class*="group/section"]');
                    results.sections = [];
                    for (var i = 0; i < sections.length; i++) {
                        var nameEl = sections[i].querySelector('div.text-sm.font-medium.truncate.m-0, h2');
                        var name = nameEl ? nameEl.textContent.trim() : 'NO_NAME_EL';
                        var pills = sections[i].querySelectorAll('[data-testid^="convo-pill"]');
                        results.sections.push({
                            index: i,
                            name: name,
                            pillsCount: pills.length
                        });
                    }
                } catch(e) {
                    results.error = e.message;
                }
                return JSON.stringify(results);
            })()`;
            ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression: expr, returnByValue: true } }));
        });
        ws.on('message', function(d) {
            var r = JSON.parse(d);
            if (r.id === 1) {
                console.log('Result:', JSON.parse(r.result.result.value));
                ws.close();
            }
        });
    } catch (e) {
        console.error('Error:', e.message);
    }
}
main();
