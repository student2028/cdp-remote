const http = require('http');
const WebSocket = require('ws');

async function main() {
    const pages = await new Promise(r => {
        http.get('http://127.0.0.1:9334/json', res => {
            let d=''; res.on('data',c=>d+=c); res.on('end',()=>r(JSON.parse(d)));
        });
    });
    const wb = pages.find(p => p.type==='page' && p.url && p.url.includes('workbench') && !p.url.includes('workbench-jetski'));
    if (!wb) { console.log('No workbench'); return; }
    
    const ws = new WebSocket(wb.webSocketDebuggerUrl);
    ws.on('open', () => {
        // Try to get the full text from the agent conversation area
        const expr = `(function(){
            // Strategy 1: find agent-convo elements
            var agents = document.querySelectorAll('[class*="agent-convo"]');
            if (agents.length > 0) {
                var last = agents[agents.length - 1];
                var t = (last.innerText || '').trim();
                if (t.length > 10) return 'STRATEGY1_AGENT_CONVO|' + t;
            }
            
            // Strategy 2: find message blocks  
            var msgs = document.querySelectorAll('[class*="message-block"]');
            if (msgs.length > 0) {
                var last = msgs[msgs.length - 1];
                var t = (last.innerText || '').trim();
                if (t.length > 10) return 'STRATEGY2_MSG_BLOCK|' + t;
            }
            
            // Strategy 3: broader - find message elements containing TASK
            var allMsg = document.querySelectorAll('[class*="message"]');
            for (var i = allMsg.length - 1; i >= 0; i--) {
                var t = (allMsg[i].innerText || '').trim();
                if (t.indexOf('TASK_START') >= 0) return 'STRATEGY3_MSG_WITH_TASK|' + t;
                if (t.length > 50) return 'STRATEGY3_MSG_LONG|' + t;
            }
            
            // Strategy 4: agent class elements
            var agentEls = document.querySelectorAll('[class*="agent"]');
            for (var i = agentEls.length - 1; i >= 0; i--) {
                var t = (agentEls[i].innerText || '').trim();
                if (t.indexOf('TASK_START') >= 0) return 'STRATEGY4_AGENT|' + t;
            }
            
            return 'NONE_FOUND';
        })()`;
        ws.send(JSON.stringify({ id:1, method:'Runtime.evaluate', params:{ expression: expr, returnByValue: true } }));
    });
    ws.on('message', function(d) {
        var r = JSON.parse(d);
        if (r.id === 1) {
            var val = r.result.result.value || 'EMPTY';
            var pipe = val.indexOf('|');
            if (pipe > 0) {
                console.log('Strategy:', val.substring(0, pipe));
                var text = val.substring(pipe + 1);
                console.log('Text length:', text.length);
                console.log('Has TASK_START:', text.indexOf('TASK_START') >= 0);
                console.log('Has TASK_END:', text.indexOf('TASK_END') >= 0);
                console.log('--- First 500 chars ---');
                console.log(text.substring(0, 500));
                console.log('--- Last 300 chars ---');
                console.log(text.substring(text.length - 300));
            } else {
                console.log(val);
            }
            ws.close();
        }
    });
}
main();
