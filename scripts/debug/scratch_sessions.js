const WebSocket = require('ws');
const http = require('http');

function getTargets() {
    return new Promise((resolve, reject) => {
        http.get('http://127.0.0.1:9333/json', (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                try { resolve(JSON.parse(data)); } catch (e) { reject(e); }
            });
        }).on('error', reject);
    });
}

async function main() {
    try {
        const targets = await getTargets();
        const page = targets.find(t => t.type === 'page' && t.url && t.url.includes('workbench.html'));
        if (!page) { console.error("Could not find workbench page."); process.exit(1); }

        const ws = new WebSocket(page.webSocketDebuggerUrl);
        let id = 1;
        let pendingCallbacks = {};

        function send(method, params = {}) {
            return new Promise((resolve) => {
                const msgId = id++;
                pendingCallbacks[msgId] = resolve;
                ws.send(JSON.stringify({ id: msgId, method, params }));
            });
        }

        ws.on('message', (data) => {
            const msg = JSON.parse(data.toString());
            if (pendingCallbacks[msg.id]) {
                pendingCallbacks[msg.id](msg);
                delete pendingCallbacks[msg.id];
            }
        });

        ws.on('open', async () => {
            const res = await send('Runtime.evaluate', {
                expression: `(async function() {
                    function getDocs(d) {
                        var out = [d];
                        var ifr = d.querySelectorAll('iframe');
                        for (var i = 0; i < ifr.length; i++) {
                            try { if (ifr[i].contentDocument) out = out.concat(getDocs(ifr[i].contentDocument)); } catch (e) {}
                        }
                        return out;
                    }
                    
                    function getItems() {
                        var items = document.querySelectorAll('[role="listitem"], [class*="history-item"], [class*="session-item"], .history-entry');
                        var visibleItems = [];
                        for(var i=0; i<items.length; i++) {
                            if(items[i].offsetParent) visibleItems.push(items[i]);
                        }
                        return visibleItems;
                    }

                    var historyBtn = null;
                    var docs = getDocs(document);
                    for (var di = 0; di < docs.length; di++) {
                        var doc = docs[di];
                        if (!doc || !doc.querySelectorAll) continue;
                        var btns = doc.querySelectorAll('a, button, [role="button"]');
                        for (var i = 0; i < btns.length; i++) {
                            if (!btns[i].offsetParent) continue;
                            var aria = (btns[i].getAttribute('aria-label') || '').toLowerCase();
                            var title = (btns[i].title || '').toLowerCase();
                            var text = (btns[i].textContent || '').trim().toLowerCase();
                            if (aria.includes('history') || title.includes('history') ||
                                aria.includes('recent') || title.includes('recent') ||
                                aria.includes('历史') || title.includes('历史') ||
                                text === 'history' || text === '历史记录' || text === 'recent sessions' ||
                                btns[i].getAttribute('data-tooltip-id') === 'history-tooltip') {
                                historyBtn = btns[i]; break;
                            }
                        }
                        if (historyBtn) break;
                    }
                    
                    if (historyBtn) {
                        historyBtn.click();
                        await new Promise(r => setTimeout(r, 600));
                        
                        var vItems2 = getItems();
                        var sessions = [];
                        for(var i=0; i<vItems2.length; i++) {
                            sessions.push(vItems2[i].textContent.trim().replace(/\\n/g, ' '));
                        }
                        return JSON.stringify({status:'clicked', count: vItems2.length, sessions: sessions});
                    }
                    
                    return JSON.stringify({status:'no-history-btn'});
                })()`,
                awaitPromise: true,
                returnByValue: true
            });
            
            console.log("Result:", res.result.result.value);
            ws.close();
        });
    } catch (e) {
        console.error(e);
    }
}
main();
