const WebSocket = require('ws');
const http = require('http');
const https = require('https');

// 忽略自签名证书的 HTTPS agent
const insecureAgent = new https.Agent({ rejectUnauthorized: false });

let activePages = new Map(); // cdpPort -> { ws, lsPort, csrfToken, cascadeId, protocol }

function extractUuid(str) {
    const match = str?.match(/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/);
    return match ? match[0] : null;
}

async function attachLsMonitor(cdpPort, webSocketDebuggerUrl) {
    if (activePages.has(cdpPort)) return;
    
    console.log(`[LS-Monitor] Attaching to CDP:${cdpPort}`);
    const ws = new WebSocket(webSocketDebuggerUrl);
    activePages.set(cdpPort, { ws, lsPort: null, csrfToken: null, cascadeId: null, protocol: 'https' });
    
    ws.on('open', () => {
        ws.send(JSON.stringify({ id: 1, method: 'Network.enable' }));
        ws.send(JSON.stringify({ id: 2, method: 'Network.setRequestInterception', params: { patterns: [{ urlPattern: '*exa.language_server_pb*' }] } }));
    });

    ws.on('message', (msg) => {
        try {
            const data = JSON.parse(msg);
            if (data.method === 'Network.requestWillBeSent') {
                const req = data.params.request;
                if (req.url && req.url.includes('exa.language_server_pb')) {
                    const portMatch = req.url.match(/127\.0\.0\.1:(\d+)/);
                    const lsPort = portMatch ? parseInt(portMatch[1]) : null;
                    const protocol = req.url.startsWith('https') ? 'https' : 'http';
                    const csrfToken = req.headers['x-codeium-csrf-token'] || req.headers['X-Codeium-Csrf-Token'] || req.headers['x-csrf-token'];
                    
                    let postData = req.postData;
                    let cascadeId = null;
                    if (postData) {
                        cascadeId = extractUuid(postData);
                    }
                    
                    const state = activePages.get(cdpPort);
                    if (state) {
                        if (lsPort) state.lsPort = lsPort;
                        if (csrfToken) state.csrfToken = csrfToken;
                        if (cascadeId) state.cascadeId = cascadeId;
                        state.protocol = protocol;
                    }
                }
            }
        } catch(e) {}
    });

    ws.on('close', () => {
        console.log(`[LS-Monitor] Detached from CDP:${cdpPort}`);
        activePages.delete(cdpPort);
    });
    ws.on('error', () => {
        activePages.delete(cdpPort);
    });
}

/**
 * 自定义 HTTP/HTTPS 请求（支持自签名证书）
 */
function lsFetch(url, body, csrfToken) {
    return new Promise((resolve, reject) => {
        const parsed = new URL(url);
        const isHttps = parsed.protocol === 'https:';
        const mod = isHttps ? https : http;
        const opts = {
            hostname: parsed.hostname,
            port: parsed.port,
            path: parsed.pathname,
            method: 'POST',
            headers: {
                'content-type': 'application/json',
                'connect-protocol-version': '1',
                'x-codeium-csrf-token': csrfToken
            },
            rejectAuthorized: false  // 跳过自签名证书验证
        };
        if (isHttps) opts.agent = insecureAgent;
        
        const req = mod.request(opts, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                if (res.statusCode >= 200 && res.statusCode < 300) {
                    try { resolve(JSON.parse(data)); } catch(e) { reject(new Error('JSON parse failed: ' + data.substring(0, 100))); }
                } else {
                    reject(new Error(`HTTP ${res.statusCode}: ${data.substring(0, 100)}`));
                }
            });
        });
        req.on('error', reject);
        req.setTimeout(5000, () => { req.destroy(); reject(new Error('timeout')); });
        req.write(JSON.stringify(body));
        req.end();
    });
}

async function startLsAutoApprovalLoop() {
    setInterval(async () => {
        for (const [cdpPort, state] of activePages.entries()) {
            if (!state.lsPort || !state.csrfToken || !state.cascadeId) continue;
            
            try {
                // Fetch trajectory (自动使用正确的协议)
                const baseUrl = `${state.protocol}://127.0.0.1:${state.lsPort}`;
                const data = await lsFetch(
                    `${baseUrl}/exa.language_server_pb.LanguageServerService/GetCascadeTrajectorySteps`,
                    { cascadeId: state.cascadeId, stepOffset: 0 },
                    state.csrfToken
                );
                const steps = data.steps || [];
                
                // Process backwards
                for (let i = steps.length - 1; i >= 0; i--) {
                    const step = steps[i];
                    if (step.status === 9) { // 9 = WAITING_FOR_USER_ACTION
                        const trajectoryId = step.metadata?.trajectoryId || step.metadata?.trajectory_id || state.cascadeId;
                        
                        let interaction = null;
                        
                        if (step.runCommand || step.step?.runCommand) {
                            const cmd = step.runCommand || step.step.runCommand;
                            interaction = {
                                trajectoryId,
                                stepIndex: i,
                                runCommand: {
                                    confirm: true,
                                    action: 1, // ALLOW
                                    proposedCommandLine: cmd.commandLine || "",
                                    submittedCommandLine: cmd.commandLine || ""
                                }
                            };
                            console.log(`[LS-Monitor] Auto-approving runCommand: ${cmd.commandLine}`);
                        } 
                        else if (step.readUrlContent || step.step?.readUrlContent) {
                            const readUrl = step.readUrlContent || step.step.readUrlContent;
                            const url = readUrl.url || "";
                            let origin = "";
                            try { origin = new URL(url).origin; } catch(e){}
                            interaction = {
                                trajectoryId,
                                stepIndex: i,
                                readUrlContent: { action: 1, url, origin }
                            };
                            console.log(`[LS-Monitor] Auto-approving readUrlContent: ${url}`);
                        }
                        else if (step.mcpTool || step.step?.mcpTool) {
                            interaction = {
                                trajectoryId,
                                stepIndex: i,
                                mcpTool: { action: 1 } // Speculative payload for MCP Tool approval
                            };
                            console.log(`[LS-Monitor] Auto-approving mcpTool`);
                        }
                        
                        if (interaction) {
                            await lsFetch(
                                `${baseUrl}/exa.language_server_pb.LanguageServerService/HandleCascadeUserInteraction`,
                                { cascadeId: state.cascadeId, interaction },
                                state.csrfToken
                            );
                        }
                    }
                }
            } catch(e) {
                // Ignore errors to prevent loop crash
            }
        }
    }, 2000);
}

module.exports = { attachLsMonitor, startLsAutoApprovalLoop };
