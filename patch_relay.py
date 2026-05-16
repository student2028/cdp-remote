import sys
import re

file_path = '/Users/student2028/code/cc/cdp-remote/relay-dev/cdp_relay.js'
with open(file_path, 'r', encoding='utf8') as f:
    content = f.read()

# 1. /targets endpoint
old_targets = """    // ── /targets — 核心：返回所有发现的 CDP 实例 ──
    if (req.url === '/targets') {
        const relayHost = req.headers.host || `${BIND_ADDR}:${RELAY_PORT}`;
        const targets = [];
        for (const [port, t] of activeCdpTargets) {
            const pages = t.pages.map(p => ({
                ...p,
                webSocketDebuggerUrl: p.webSocketDebuggerUrl
                    ? p.webSocketDebuggerUrl
                        .replace(`ws://127.0.0.1:${port}/`, `ws://${relayHost}/cdp/${port}/`)
                        .replace(`ws://localhost:${port}/`, `ws://${relayHost}/cdp/${port}/`)
                    : '',
                devtoolsFrontendUrl: p.devtoolsFrontendUrl || ''
            }));
            targets.push({ cdpPort: port, appName: t.appName, appEmoji: t.appEmoji, workspace: getWorkspaceForPort(port), pages });
        }
        res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
        res.end(JSON.stringify({ targets }));
        return;
    }"""

new_targets = """    // ── /targets — 核心：返回所有发现的 CDP 实例 ──
    if (req.url.startsWith('/targets')) {
        const parsed = urlModule.parse(req.url, true);
        const expandUitty = parsed.query.expandUitty === 'true';
        const relayHost = req.headers.host || `${BIND_ADDR}:${RELAY_PORT}`;
        
        // 由于需要 evaluateOnCdpPage 获取终端信息，改为 async
        (async () => {
            const targets = [];
            for (const [port, t] of activeCdpTargets) {
                if (expandUitty && t.appName.toLowerCase() === 'uitty') {
                    try {
                        const panesExpr = `(function(){
                            if (!window.tabs) return [];
                            let res = [];
                            window.tabs.forEach(tab => tab.panes.forEach(p => {
                                if (p.pid) res.push({ pid: p.pid, title: tab.label || 'zsh', cwd: p.cachedCwd });
                            }));
                            return res;
                        })()`;
                        const panes = await evaluateOnCdpPage(port, panesExpr, { timeoutMs: 2000 });
                        if (panes && panes.length > 0) {
                            for (const pane of panes) {
                                targets.push({
                                    cdpPort: port,
                                    appName: `uitty:${pane.pid}`,
                                    appEmoji: t.appEmoji,
                                    workspace: pane.cwd || getWorkspaceForPort(port),
                                    pages: [{
                                        id: `uitty-pane-${pane.pid}`,
                                        type: 'page',
                                        title: `${pane.title} (PID: ${pane.pid})`,
                                        url: 'uitty://pane',
                                        webSocketDebuggerUrl: '',
                                        devtoolsFrontendUrl: ''
                                    }]
                                });
                            }
                            continue;
                        }
                    } catch (e) { log(`⚠️ 获取 uitty terminals 失败: ${e.message}`); }
                }

                const pages = t.pages.map(p => ({
                    ...p,
                    webSocketDebuggerUrl: p.webSocketDebuggerUrl
                        ? p.webSocketDebuggerUrl
                            .replace(`ws://127.0.0.1:${port}/`, `ws://${relayHost}/cdp/${port}/`)
                            .replace(`ws://localhost:${port}/`, `ws://${relayHost}/cdp/${port}/`)
                        : '',
                    devtoolsFrontendUrl: p.devtoolsFrontendUrl || ''
                }));
                targets.push({ cdpPort: port, appName: t.appName, appEmoji: t.appEmoji, workspace: getWorkspaceForPort(port), pages });
            }
            res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
            res.end(JSON.stringify({ targets }));
        })();
        return;
    }"""

if old_targets in content:
    content = content.replace(old_targets, new_targets)
else:
    print("Error: Could not find /targets block")
    sys.exit(1)

# 2. /workflow/start parsing
old_start = """                const brainIde  = parsed.brain?.ide?.trim();
                const workerIde = parsed.worker?.ide?.trim();
                const initialTask = parsed.initial_task?.trim();"""
new_start = """                let brainIde  = parsed.brain?.ide?.trim();
                let workerIde = parsed.worker?.ide?.trim();
                const initialTask = parsed.initial_task?.trim();

                let brainPid = null;
                if (brainIde && brainIde.toLowerCase().startsWith('uitty:')) {
                    brainPid = Number(brainIde.split(':')[1]);
                    brainIde = 'uitty';
                }

                let workerPid = null;
                if (workerIde && workerIde.toLowerCase().startsWith('uitty:')) {
                    workerPid = Number(workerIde.split(':')[1]);
                    workerIde = 'uitty';
                }"""

if old_start in content:
    content = content.replace(old_start, new_start)
else:
    print("Error: Could not find /workflow/start vars")
    sys.exit(1)

# 2b. /workflow/start currentPipeline
content = content.replace(
    "currentPipeline.brain  = { ide: brainIde, port: brainPort };",
    "currentPipeline.brain  = { ide: brainIde, port: brainPort, pid: brainPid };"
)
content = content.replace(
    "currentPipeline.worker = { ide: workerIde, port: workerPort };",
    "currentPipeline.worker = { ide: workerIde, port: workerPort, pid: workerPid };"
)
content = content.replace(
    "brain:  { ide: brainIde,  port: brainPort  },",
    "brain:  { ide: brainIde,  port: brainPort, pid: brainPid  },"
)
content = content.replace(
    "worker: { ide: workerIde, port: workerPort },",
    "worker: { ide: workerIde, port: workerPort, pid: workerPid },"
)

# 3. /send parsing
old_send = """                const { message } = JSON.parse(body);
                if (!message) throw new Error("缺少 'message' 字段");
                if (!activeCdpTargets.has(cdpPort)) throw new Error(`CDP 端口 ${cdpPort} 不可用`);
                await sendMessageToIde(cdpPort, message);"""
new_send = """                const { message, targetPid } = JSON.parse(body);
                if (!message) throw new Error("缺少 'message' 字段");
                if (!activeCdpTargets.has(cdpPort)) throw new Error(`CDP 端口 ${cdpPort} 不可用`);
                await sendMessageToIde(cdpPort, message, null, targetPid);"""

if old_send in content:
    content = content.replace(old_send, new_send)
else:
    print("Error: Could not find /send handler")
    sys.exit(1)

# 4. schedulerExecuteTask parsing
old_scheduler = """    try {
        // 找目标 IDE 的 CDP 端口
        // 策略：
        //   1) targetPort > 0（用户明确选了端口）→ 精确匹配，找不到就跳过，绝不静默降级到别的实例
        //   2) targetPort == 0（旧任务 / 未指定端口）→ 按 IDE 名字匹配第一个在线实例
        let cdpPort = null;
        if (task.targetPort && task.targetPort > 0) {
            // 精确匹配模式
            if (activeCdpTargets.has(task.targetPort)) {
                const t = activeCdpTargets.get(task.targetPort);
                if (t.appName.toLowerCase() === task.targetIde.toLowerCase()) {"""

new_scheduler = """    try {
        let targetIdeName = task.targetIde;
        let targetPid = null;
        if (targetIdeName && targetIdeName.toLowerCase().startsWith('uitty:')) {
            targetPid = Number(targetIdeName.split(':')[1]);
            targetIdeName = 'uitty';
        }

        // 找目标 IDE 的 CDP 端口
        // 策略：
        //   1) targetPort > 0（用户明确选了端口）→ 精确匹配，找不到就跳过，绝不静默降级到别的实例
        //   2) targetPort == 0（旧任务 / 未指定端口）→ 按 IDE 名字匹配第一个在线实例
        let cdpPort = null;
        if (task.targetPort && task.targetPort > 0) {
            // 精确匹配模式
            if (activeCdpTargets.has(task.targetPort)) {
                const t = activeCdpTargets.get(task.targetPort);
                if (t.appName.toLowerCase() === targetIdeName.toLowerCase()) {"""

if old_scheduler in content:
    content = content.replace(old_scheduler, new_scheduler)
else:
    print("Error: Could not find schedulerExecuteTask logic")
    sys.exit(1)

# 4b. schedulerExecuteTask loop fallback
old_scheduler_2 = """            // 兼容模式：按名字匹配
            for (const [port, t] of activeCdpTargets) {
                if (t.appName.toLowerCase() === task.targetIde.toLowerCase()) {"""
new_scheduler_2 = """            // 兼容模式：按名字匹配
            for (const [port, t] of activeCdpTargets) {
                if (t.appName.toLowerCase() === targetIdeName.toLowerCase()) {"""

if old_scheduler_2 in content:
    content = content.replace(old_scheduler_2, new_scheduler_2)
else:
    print("Error: Could not find scheduler fallback loop")
    sys.exit(1)

# 4c. schedulerExecuteTask call
old_scheduler_3 = """        await sendMessageToIde(cdpPort, task.prompt, task.fixedSessionTitle);"""
new_scheduler_3 = """        await sendMessageToIde(cdpPort, task.prompt, task.fixedSessionTitle, targetPid);"""
if old_scheduler_3 in content:
    content = content.replace(old_scheduler_3, new_scheduler_3)
else:
    print("Error: Could not find scheduler call to sendMessageToIde")
    sys.exit(1)

# 5. workflow loops
content = content.replace(
    "await sendMessageToIde(port, prompt);",
    "await sendMessageToIde(port, prompt, null, isBrainAction ? pl.brain.pid : pl.worker.pid);"
)
content = content.replace(
    "await sendMessageToIde(port, rejectPrompt);",
    "await sendMessageToIde(port, rejectPrompt, null, isBrainAction ? pl.brain.pid : pl.worker.pid);"
)

# 6. sendMessageToIde definition and logic
old_send_def = """async function sendMessageToIde(cdpPort, message, fixedSessionTitle = null) {"""
new_send_def = """async function sendMessageToIde(cdpPort, message, fixedSessionTitle = null, targetPid = null) {"""
if old_send_def in content:
    content = content.replace(old_send_def, new_send_def)
else:
    print("Error: Could not find sendMessageToIde def")
    sys.exit(1)

old_send_logic = """        ws.on('open', async () => {
            try {
                if (fixedSessionTitle) {"""
new_send_logic = """        ws.on('open', async () => {
            try {
                if (targetAppName.toLowerCase() === 'uitty' && targetPid) {
                    const focusExpr = `(function() {
                        if (!window.tabs) return false;
                        for (const t of window.tabs) {
                            for (const p of t.panes) {
                                if (p.pid === ${targetPid}) {
                                    window.switchTab(t.id);
                                    window.focusPane(p.id);
                                    return true;
                                }
                            }
                        }
                        return false;
                    })()`;
                    await cdpCall('Runtime.evaluate', { expression: focusExpr, awaitPromise: true, returnByValue: true });
                    await new Promise(r => setTimeout(r, 200));
                }

                if (fixedSessionTitle) {"""
if old_send_logic in content:
    content = content.replace(old_send_logic, new_send_logic)
else:
    print("Error: Could not find sendMessageToIde logic")
    sys.exit(1)

with open(file_path, 'w', encoding='utf8') as f:
    f.write(content)
print("Successfully patched cdp_relay.js")
