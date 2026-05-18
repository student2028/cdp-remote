#!/usr/bin/env node
/**
 * CDP Relay Server (多实例版)
 *
 * 支持同时代理多个 Electron IDE 实例：
 *   1. 自动扫描端口范围，发现所有 CDP 实例
 *   2. 通过路径前缀 /cdp/{port}/ 路由到不同实例
 *   3. 保持原有单端口兼容性
 *   4. HTTP + WebSocket 双协议代理，自动 URL 重写
 *   5. OTA 更新分发
 *
 * 用户无需任何手动操作！安装后即忘。
 */

const http = require('http');
const urlModule = require('url');
const { WebSocketServer, WebSocket } = require('ws');
const { execSync, exec, spawn, execFile, execFileSync } = require('child_process');
const { promisify } = require('util');
const execFilePromise = promisify(execFile);
const os = require('os');
const ENABLE_WATCHDOG = process.env.ENABLE_WATCHDOG !== 'false';
const path = require('path');
const fs = require('fs');
const { parseBrainVerdict, parseBrainTask } = require('./workflow_utils');
const { detectAppType } = require('./cdp_target_detection');
const { executePipelineStages } = require('./scheduler_pipeline');
const { getCompletedRuns, getMaxRuns, markRunCompleted, shouldSkipScheduledRun } = require('./scheduler_run_limits');
const otaMeta = require('./ota_meta');
// 智能检测：如果上层目录有 app/ 和 build/（说明在 git 仓库里），就用上层；否则用当前目录（独立 npm 包）
const REPO_ROOT = fs.existsSync(path.join(__dirname, '..', 'app')) ? path.join(__dirname, '..') : __dirname;
const ORCHESTRA_SCRIPT = path.join(REPO_ROOT, 'scripts', 'orchestra.sh');
const REFERENCE_TRANSACTION_HOOK = path.join(REPO_ROOT, 'scripts', 'git-hooks', 'reference-transaction');

// ─── 目录历史持久化 ───
const CWD_HISTORY_DIR = path.join(os.homedir(), '.cdp-relay');
const CWD_HISTORY_FILE = path.join(CWD_HISTORY_DIR, 'cwd_history.json');
const CWD_HISTORY_MAX = 30;

function loadCwdHistory() {
    try {
        if (fs.existsSync(CWD_HISTORY_FILE)) {
            return JSON.parse(fs.readFileSync(CWD_HISTORY_FILE, 'utf8'));
        }
    } catch (e) { log(`⚠️ 读取目录历史失败: ${e.message}`); }
    return [];
}

function saveCwdHistory(history) {
    try {
        fs.mkdirSync(CWD_HISTORY_DIR, { recursive: true });
        fs.writeFileSync(CWD_HISTORY_FILE, JSON.stringify(history, null, 2));
    } catch (e) { log(`⚠️ 保存目录历史失败: ${e.message}`); }
}

function addCwdHistory(cwdPath, appName) {
    if (!cwdPath) return;
    let history = loadCwdHistory();
    history = history.filter(h => h.path !== cwdPath);
    history.unshift({ path: cwdPath, app: appName || '', time: new Date().toISOString() });
    if (history.length > CWD_HISTORY_MAX) history = history.slice(0, CWD_HISTORY_MAX);
    saveCwdHistory(history);
    log(`📁 记录目录历史: ${cwdPath} (${appName})`);
}

// ─── Codex workspace roots 持久化 ───
// Codex Desktop 的项目列表不来自启动参数，而来自全局状态文件。
// 传 cwd 给 open/launch 只能命中已经存在的项目；新项目必须先写入 workspace roots。
const CODEX_GLOBAL_STATE_FILE = path.join(os.homedir(), '.codex', '.codex-global-state.json');

function normalizeWorkspacePath(cwdPath) {
    const raw = String(cwdPath || '').trim();
    if (!raw) return '';
    const expanded = raw === '~' ? os.homedir() : raw.replace(/^~\//, `${os.homedir()}/`);
    const resolved = path.resolve(expanded);
    try {
        return fs.realpathSync(resolved);
    } catch (_) {
        return resolved;
    }
}

function readCodexGlobalState() {
    try {
        if (fs.existsSync(CODEX_GLOBAL_STATE_FILE)) {
            return JSON.parse(fs.readFileSync(CODEX_GLOBAL_STATE_FILE, 'utf8'));
        }
    } catch (e) {
        throw new Error(`读取 Codex 全局状态失败: ${e.message}`);
    }
    return {};
}

function writeCodexGlobalState(state) {
    fs.mkdirSync(path.dirname(CODEX_GLOBAL_STATE_FILE), { recursive: true });
    const tmp = `${CODEX_GLOBAL_STATE_FILE}.tmp-${process.pid}`;
    fs.writeFileSync(tmp, JSON.stringify(state, null, 2));
    fs.renameSync(tmp, CODEX_GLOBAL_STATE_FILE);
}

function ensureArray(value) {
    return Array.isArray(value) ? value.filter(v => typeof v === 'string' && v.trim()) : [];
}

function ensureCodexWorkspaceRoot(cwdPath, { activate = true } = {}) {
    const workspaceRoot = normalizeWorkspacePath(cwdPath);
    if (!workspaceRoot) throw new Error('缺少 Codex 工作目录');
    if (!fs.existsSync(workspaceRoot) || !fs.statSync(workspaceRoot).isDirectory()) {
        throw new Error(`Codex 工作目录不存在或不是目录: ${workspaceRoot}`);
    }

    const state = readCodexGlobalState();
    const savedRoots = ensureArray(state['electron-saved-workspace-roots']);
    const projectOrder = ensureArray(state['project-order']);

    const nextSavedRoots = [workspaceRoot, ...savedRoots.filter(p => p !== workspaceRoot)];
    const nextProjectOrder = [workspaceRoot, ...projectOrder.filter(p => p !== workspaceRoot)];

    let changed = JSON.stringify(savedRoots) !== JSON.stringify(nextSavedRoots)
        || JSON.stringify(projectOrder) !== JSON.stringify(nextProjectOrder);

    state['electron-saved-workspace-roots'] = nextSavedRoots;
    state['project-order'] = nextProjectOrder;

    if (activate) {
        const activeRoots = ensureArray(state['active-workspace-roots']);
        const nextActiveRoots = [workspaceRoot, ...activeRoots.filter(p => p !== workspaceRoot)];
        if (JSON.stringify(activeRoots) !== JSON.stringify(nextActiveRoots)) changed = true;
        state['active-workspace-roots'] = nextActiveRoots;
    }

    if (changed) {
        writeCodexGlobalState(state);
        log(`📦 Codex workspace 已${activate ? '添加并激活' : '添加'}: ${workspaceRoot}`);
    } else {
        log(`📦 Codex workspace 已存在: ${workspaceRoot}`);
    }

    return { workspaceRoot, changed, stateFile: CODEX_GLOBAL_STATE_FILE };
}

async function evaluateOnCdpPage(cdpPort, expression, { timeoutMs = 8000, pageMatcher = null } = {}) {
    const pages = await getJson(`http://${CDP_HOST}:${cdpPort}/json`, 3000);
    const page = pages.find(p =>
        p.type === 'page'
        && p.webSocketDebuggerUrl
        && (!pageMatcher || pageMatcher(p))
    );
    if (!page) throw new Error(`CDP :${cdpPort} 未找到可执行页面`);

    return await new Promise((resolve, reject) => {
        const ws = new WebSocket(page.webSocketDebuggerUrl);
        const timer = setTimeout(() => {
            try { ws.close(); } catch (_) {}
            reject(new Error('Runtime.evaluate timeout'));
        }, timeoutMs);

        ws.on('open', () => {
            ws.send(JSON.stringify({
                id: 1,
                method: 'Runtime.evaluate',
                params: { expression, awaitPromise: true, returnByValue: true }
            }));
        });
        ws.on('message', (buf) => {
            try {
                const msg = JSON.parse(buf.toString());
                if (msg.id !== 1) return;
                clearTimeout(timer);
                try { ws.close(); } catch (_) {}
                if (msg.error) reject(new Error(msg.error.message || 'Runtime.evaluate failed'));
                else resolve(msg.result?.result?.value);
            } catch (e) {
                clearTimeout(timer);
                try { ws.close(); } catch (_) {}
                reject(e);
            }
        });
        ws.on('error', (e) => {
            clearTimeout(timer);
            reject(e);
        });
    });
}

async function addCodexWorkspaceRootToRunningApp(cdpPort, workspaceRoot) {
    const rootLiteral = JSON.stringify(workspaceRoot);
    const expression = `
        (async function() {
            var rootPath = ${rootLiteral};
            try {
                if (!window.electronBridge || typeof window.electronBridge.sendMessageFromView !== 'function') {
                    return 'missing-electronBridge';
                }
                await window.electronBridge.sendMessageFromView({
                    type: 'electron-add-new-workspace-root-option',
                    root: rootPath
                });
                return 'ok';
            } catch (e) {
                return 'error:' + (e && e.message ? e.message : String(e));
            }
        })()
    `;
    return evaluateOnCdpPage(cdpPort, expression, {
        pageMatcher: isCodexMainPage
    });
}

function isCodexMainPage(p) {
    return p.url?.startsWith('app://') || p.url?.includes('index.html');
}

// ─── 用户环境变量加载（解决 LaunchAgent 缺少代理等环境变量问题）───
let _cachedUserEnv = null;
let _envCacheTime = 0;
const ENV_CACHE_TTL = 60 * 1000;

/** 从 macOS 系统代理设置 (/usr/sbin/scutil --proxy) 中读取代理配置 */
function getSystemProxy() {
    try {
        const raw = execSync('/usr/sbin/scutil --proxy 2>/dev/null', { timeout: 3000, encoding: 'utf8' });
        const result = {};
        const httpEnabled = /HTTPEnable\s*:\s*1/.test(raw);
        const httpsEnabled = /HTTPSEnable\s*:\s*1/.test(raw);
        const socksEnabled = /SOCKSEnable\s*:\s*1/.test(raw);
        if (httpEnabled) {
            const host = raw.match(/HTTPProxy\s*:\s*(\S+)/)?.[1];
            const port = raw.match(/HTTPPort\s*:\s*(\d+)/)?.[1];
            if (host && port) result.http_proxy = `http://${host}:${port}`;
        }
        if (httpsEnabled) {
            const host = raw.match(/HTTPSProxy\s*:\s*(\S+)/)?.[1];
            const port = raw.match(/HTTPSPort\s*:\s*(\d+)/)?.[1];
            if (host && port) result.https_proxy = `http://${host}:${port}`;
        }
        if (socksEnabled) {
            const host = raw.match(/SOCKSProxy\s*:\s*(\S+)/)?.[1];
            const port = raw.match(/SOCKSPort\s*:\s*(\d+)/)?.[1];
            if (host && port) result.all_proxy = `socks5://${host}:${port}`;
        }
        // 读取排除列表
        const exceptions = raw.match(/ExceptionsList\s*:\s*<array>\s*\{([^}]+)\}/s);
        if (exceptions) {
            const items = exceptions[1].match(/\d+\s*:\s*(\S+)/g);
            if (items) {
                result.no_proxy = items.map(i => i.replace(/^\d+\s*:\s*/, '')).join(',');
            }
        }
        return result;
    } catch (e) {
        return {};
    }
}

function getUserEnv() {
    const now = Date.now();
    if (_cachedUserEnv && (now - _envCacheTime) < ENV_CACHE_TTL) return _cachedUserEnv;
    try {
        const raw = execSync('/bin/zsh -ilc "env" 2>/dev/null', { timeout: 5000, encoding: 'utf8' });
        const env = {};
        for (const line of raw.split('\n')) {
            const idx = line.indexOf('=');
            if (idx > 0) { env[line.substring(0, idx)] = line.substring(idx + 1); }
        }

        // 如果 shell 环境里没有代理变量，从 macOS 系统代理设置中补充
        _applySystemProxyToEnv(env);

        _cachedUserEnv = env;
        const allProxyKeys = ['http_proxy', 'https_proxy', 'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'all_proxy', 'NO_PROXY', 'no_proxy'];
        const found = allProxyKeys.filter(k => env[k]);
        if (found.length > 0) {
            _envCacheTime = now; // 有代理：正常缓存 60 秒
            log(`🌐 启动环境代理变量: ${found.map(k => `${k}=${env[k]}`).join(', ')}`);
        } else {
            _envCacheTime = now - ENV_CACHE_TTL + 5000; // 无代理：仅缓存 5 秒，代理启动后快速生效
            log(`⚠️ 未检测到代理变量，5 秒后重新检测`);
        }
        return env;
    } catch (e) {
        log(`⚠️ 读取用户环境变量失败: ${e.message}，尝试系统代理兜底`);
        const env = { ...process.env };
        _applySystemProxyToEnv(env);
        return env;
    }
}

/** 将 macOS 系统代理补充到 env 对象中（如果 env 中尚无代理变量） */
function _applySystemProxyToEnv(env) {
    const proxyKeys = ['http_proxy', 'https_proxy', 'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'all_proxy'];
    if (proxyKeys.some(k => env[k])) return; // shell 已有代理，不覆盖
    const sysProxy = getSystemProxy();
    if (sysProxy.http_proxy) { env.http_proxy = sysProxy.http_proxy; env.HTTP_PROXY = sysProxy.http_proxy; }
    if (sysProxy.https_proxy) { env.https_proxy = sysProxy.https_proxy; env.HTTPS_PROXY = sysProxy.https_proxy; }
    if (sysProxy.all_proxy) { env.ALL_PROXY = sysProxy.all_proxy; env.all_proxy = sysProxy.all_proxy; }
    if (sysProxy.no_proxy) { env.NO_PROXY = sysProxy.no_proxy; env.no_proxy = sysProxy.no_proxy; }
    if (Object.keys(sysProxy).length > 0) {
        log(`🌐 从 macOS 系统代理读取: ${Object.entries(sysProxy).map(([k, v]) => `${k}=${v}`).join(', ')}`);
    }
}

/** 获取 Electron --proxy-server 启动参数（让 Chromium 层直接走代理） */
function getElectronProxyFlag() {
    const sysProxy = getSystemProxy();
    if (sysProxy.http_proxy) {
        return `--proxy-server=${sysProxy.http_proxy.replace(/^https?:\/\//, '')}`;
    }
    const env = getUserEnv();
    for (const k of ['http_proxy', 'HTTP_PROXY', 'https_proxy', 'HTTPS_PROXY']) {
        if (env[k]) return `--proxy-server=${env[k].replace(/^https?:\/\//, '')}`;
    }
    return '';
}

// ─── 配置 ───
const CDP_HOST = '127.0.0.1';
const CDP_PORT = parseInt(process.env.CDP_PORT || '9334');          // 默认/兼容端口
const CDP_PORT_MIN = parseInt(process.env.CDP_PORT_MIN || '9333');  // 扫描起始
const CDP_PORT_MAX = parseInt(process.env.CDP_PORT_MAX || '9800');  // 扫描结束（含 Codex 9666, Simple Code GUI 9777）
const RELAY_PORT = parseInt(process.env.RELAY_PORT || '19336');
const BIND_ADDR = process.env.BIND_ADDR || '0.0.0.0';
const CHECK_INTERVAL = 10000;
const SCAN_TIMEOUT = 2000;
const GRACEFUL_EXIT_TIMEOUT_MS = parseInt(process.env.RELAY_GRACEFUL_EXIT_TIMEOUT_MS || '8000', 10);
const FORCE_KILL_TIMEOUT_MS = parseInt(process.env.RELAY_FORCE_KILL_TIMEOUT_MS || '3000', 10);
const SCHEDULER_STAGE_TIMEOUT_MS = parseInt(process.env.SCHEDULER_STAGE_TIMEOUT_MS || String(30 * 60 * 1000), 10);
const SCHEDULER_STAGE_POLL_MS = parseInt(process.env.SCHEDULER_STAGE_POLL_MS || '3000', 10);

const RESTART_BACKOFF_SHORT_MS = parseInt(process.env.RELAY_RESTART_BACKOFF_SHORT_MS || '60000', 10);
const RESTART_BACKOFF_LONG_MS = parseInt(process.env.RELAY_RESTART_BACKOFF_LONG_MS || String(5 * 60 * 1000), 10);
const RESTART_FAIL_THRESHOLD = parseInt(process.env.RELAY_RESTART_FAIL_THRESHOLD || '2', 10);
const AGGRESSIVE_RESTART = /^1|true|yes$/i.test(String(process.env.RELAY_AGGRESSIVE_RESTART ?? ''));
const AUTO_LAUNCH = /^1|true|yes$/i.test(String(process.env.RELAY_AUTO_LAUNCH ?? ''));

let consecutiveLaunchFailures = 0;
let nextRestartAllowedAt = 0;

// 支持的 Electron 应用配置（用于自动启动）
const ELECTRON_APPS = [
    { name: 'Antigravity', bundleId: 'com.antigravity.app', appPath: '/Applications/Antigravity.app', binPath: '/Applications/Antigravity.app/Contents/MacOS/Electron' },
    { name: 'Codex', bundleId: 'com.openai.codex', appPath: '/Applications/Codex.app', binPath: '/Applications/Codex.app/Contents/MacOS/Codex' },
    { name: 'Cursor', bundleId: 'com.todesktop.230313mzl4w4u92', appPath: '/Applications/Cursor.app', binPath: '/Applications/Cursor.app/Contents/MacOS/Cursor' },
    { name: 'Windsurf', bundleId: 'com.codeium.windsurf', appPath: '/Applications/Windsurf.app', binPath: '/Applications/Windsurf.app/Contents/MacOS/Windsurf' }
];
const DEFAULT_WORKFLOW_IDE_PORTS = {
    Antigravity: 9333,
    Windsurf: 9444,
    Cursor: 9555,
    Codex: 9666,
};

// ─── 多实例目标管理 ───
// Map<port, { appName, appEmoji, pages[], lastSeen }>
const activeCdpTargets = new Map();
let connectionCount = 0;

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

function getCdpPidsByPort(port) {
    try {
        const out = execFileSync('lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN', '-t'], {
            timeout: 3000,
            encoding: 'utf8'
        }).trim();
        if (out) return [...new Set(out.split('\n').map(s => s.trim()).filter(Boolean))];
    } catch (_) {}

    try {
        const out = execFileSync('pgrep', ['-f', '--', `--remote-debugging-port=${port}`], {
            timeout: 3000,
            encoding: 'utf8'
        }).trim();
        return out ? [...new Set(out.split('\n').map(s => s.trim()).filter(Boolean))] : [];
    } catch (_) {
        return [];
    }
}

function isPidAlive(pid) {
    try {
        process.kill(Number(pid), 0);
        return true;
    } catch (_) {
        return false;
    }
}

async function waitForPidsExit(pids, timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        if (pids.every(pid => !isPidAlive(pid))) return true;
        await sleep(250);
    }
    return pids.every(pid => !isPidAlive(pid));
}

function getJson(url, timeoutMs = 3000) {
    return new Promise((resolve, reject) => {
        const req = http.get(url, { timeout: timeoutMs }, (res) => {
            let body = '';
            res.on('data', chunk => body += chunk);
            res.on('end', () => {
                try { resolve(JSON.parse(body)); }
                catch (e) { reject(e); }
            });
        });
        req.on('error', reject);
        req.on('timeout', () => {
            req.destroy();
            reject(new Error('request timeout'));
        });
    });
}

async function sendBrowserClose(cdpPort) {
    const version = await getJson(`http://${CDP_HOST}:${cdpPort}/json/version`);
    const wsUrl = version.webSocketDebuggerUrl;
    if (!wsUrl) throw new Error('CDP browser websocket not found');

    return new Promise((resolve, reject) => {
        const ws = new WebSocket(wsUrl);
        const timer = setTimeout(() => {
            try { ws.close(); } catch (_) {}
            reject(new Error('Browser.close timeout'));
        }, 8000);

        let closeSent = false;
        ws.on('open', () => {
            closeSent = true;
            ws.send(JSON.stringify({ id: 1, method: 'Browser.close' }));
        });
        ws.on('message', (buf) => {
            try {
                const msg = JSON.parse(buf.toString());
                if (msg.id === 1) {
                    clearTimeout(timer);
                    try { ws.close(); } catch (_) {}
                    if (msg.error) reject(new Error(msg.error.message || 'Browser.close failed'));
                    else resolve(true);
                }
            } catch (_) {}
        });
        ws.on('close', () => {
            clearTimeout(timer);
            if (closeSent) resolve(true);
        });
        ws.on('error', (e) => {
            clearTimeout(timer);
            reject(e);
        });
    });
}

async function gracefulExitCdpTarget(cdpPort, { force = false } = {}) {
    const pids = getCdpPidsByPort(cdpPort);
    if (pids.length === 0) {
        return { success: false, port: cdpPort, error: `端口 ${cdpPort} 无进程` };
    }

    let browserCloseError = null;
    try {
        log(`🚪 /kill: 请求 CDP :${cdpPort} 通过 Browser.close 优雅退出 (PIDs: ${pids.join(', ')})`);
        await sendBrowserClose(cdpPort);
        const exited = await waitForPidsExit(pids, GRACEFUL_EXIT_TIMEOUT_MS);
        if (exited) {
            activeCdpTargets.delete(cdpPort);
            return { success: true, port: cdpPort, mode: 'graceful', closedPids: pids };
        }
    } catch (e) {
        browserCloseError = e;
        log(`⚠️ /kill: CDP :${cdpPort} 优雅退出失败: ${e.message}`);
    }

    if (!force) {
        return {
            success: false,
            port: cdpPort,
            mode: 'graceful',
            pids,
            error: browserCloseError?.message || 'IDE 未在优雅退出超时内结束；如确需强制结束，请使用 force=1'
        };
    }

    log(`⚠️ /kill: force=1，向 CDP :${cdpPort} 发送 SIGTERM`);
    for (const pid of pids) {
        try { process.kill(Number(pid), 'SIGTERM'); } catch (_) {}
    }
    let exited = await waitForPidsExit(pids, FORCE_KILL_TIMEOUT_MS);
    const sigkillPids = pids.filter(isPidAlive);
    if (!exited && sigkillPids.length > 0) {
        log(`💥 /kill: SIGTERM 超时，向 PIDs ${sigkillPids.join(', ')} 发送 SIGKILL`);
        for (const pid of sigkillPids) {
            try { process.kill(Number(pid), 'SIGKILL'); } catch (_) {}
        }
        exited = await waitForPidsExit(sigkillPids, FORCE_KILL_TIMEOUT_MS);
    }
    if (exited) activeCdpTargets.delete(cdpPort);
    return { success: exited, port: cdpPort, mode: 'force', terminatedPids: pids };
}

// ═══════════════════════════════════════════════════════════════
// V2 Workflow 模块：双轨制多 Agent 协同流水线
// 详见 docs/git_driven_agent_workflow.md
// ═══════════════════════════════════════════════════════════════

// 流水线运行时状态（模块级，驻留进程生命周期）
// brain/worker 不再硬编码，由 /workflow/start 时由前端传入；默认值仅作占位。
const PIPELINE_STATE_FILE = path.join(CWD_HISTORY_DIR, 'pipeline_state.json');
// DONE / ABORT 是 verb（终态动词），不是合法的 pipeline state；
// 终态延迟自愈和状态恢复逻辑抽到 relay-dev/workflow_state.js 便于单元测试。
const {
    TERMINAL_VERBS,
    maybeSelfHealTerminalState,
    hydratePipelineState,
    statusInitialTask,
} = require('./workflow_state.js');

/** 从文件恢复上次的流水线状态（Relay 重启后不丢失进行中的流水线） */
function loadPipelineState() {
    try {
        if (fs.existsSync(PIPELINE_STATE_FILE)) {
            const saved = JSON.parse(fs.readFileSync(PIPELINE_STATE_FILE, 'utf8'));
            const restored = hydratePipelineState(saved, { maxEventLog: MAX_EVENT_LOG });
            if (restored) {
                log(`📂 恢复流水线状态: ${saved.state} (${saved.name || 'pair_programming'})`);
                return restored;
            }
        }
    } catch (e) { log(`⚠️ 读取流水线状态文件失败: ${e.message}`); }
    return null;
}

/** 持久化流水线状态到文件（每次状态变更时调用） */
function savePipelineState(pl) {
    try {
        fs.mkdirSync(CWD_HISTORY_DIR, { recursive: true });
        fs.writeFileSync(PIPELINE_STATE_FILE, JSON.stringify({
            name: pl.name,
            state: pl.state,
            state_entered_at: pl.state_entered_at,
            brain: pl.brain,
            worker: pl.worker,
            cwd: pl.cwd,
            timeouts: pl.timeouts,
            initialTask: statusInitialTask(pl.state, pl.initialTask),
            lastError: pl.lastError,
            lastErrorAt: pl.lastErrorAt,
            lastFinishedState: pl.lastFinishedState,
            lastFinishedAt: pl.lastFinishedAt,
            eventLog: (pl.eventLog || []).slice(-MAX_EVENT_LOG),
            reviewRound: pl.reviewRound || 0,
            minReviewRounds: pl.minReviewRounds || 3,
            lastReviewVerdict: pl.lastReviewVerdict || null,
            lastSeenPipelineHash: pl.lastSeenPipelineHash || null,
            lastSeenMasterHash: pl.lastSeenMasterHash || null,
            currentTaskHash: pl.currentTaskHash || null,
            workerBaselineStatus: pl.workerBaselineStatus || '',
        }, null, 2));
    } catch (e) { log(`⚠️ 保存流水线状态失败: ${e.message}`); }
}

const MAX_EVENT_LOG = 30;

// ─── 多轮审查配置 ─────────────────────────────────────────────
//
// 核心理念：代码质量不是一轮 review 出来的，是多轮打磨出来的。
// DONE 必须同时满足两个条件才允许触发：
//   1. reviewRound >= minReviewRounds          （硬性轮次门槛，默认 3）
//   2. 最近一轮审查 lastReviewVerdict = 'PASS'  （由 prompt 约束 brain 输出）
// 若 reviewRound 超过 MAX_REVIEW_ROUNDS 还没 DONE，自动 ABORT，避免死循环。
const DEFAULT_MIN_REVIEW_ROUNDS = 3;
const MAX_REVIEW_ROUNDS = 8;

let currentPipeline = loadPipelineState() || {
    name: 'pair_programming',
    state: 'IDLE',
    state_entered_at: Date.now(),
    brain:  { ide: 'Antigravity', port: null },
    worker: { ide: 'Cursor',      port: null },
    cwd: null,
    initialTask: '',
    timeouts: { default_ms: 180_000 },
    warned: false,
    lastError: null,
    lastErrorAt: null,
    lastFinishedState: null,
    lastFinishedAt: null,
    eventLog: [],
    reviewRound: 0,
    minReviewRounds: DEFAULT_MIN_REVIEW_ROUNDS,
    lastReviewVerdict: null,
    lastSeenPipelineHash: null,
    lastSeenMasterHash: null,
    currentTaskHash: null,
    workerBaselineStatus: '',
};
function pushEvent(pl, event) {
    pl.eventLog.push({ ...event, time: Date.now() });
    if (pl.eventLog.length > MAX_EVENT_LOG) pl.eventLog.shift();
}

// 串行锁：防止多个 /workflow/next 并发请求在 await sendMessageToIde 时交叉修改状态。
// 所有对 currentPipeline 的读写都通过 withWorkflowLock 串行化。
let workflowQueue = Promise.resolve();
function withWorkflowLock(fn) {
    const next = workflowQueue.then(fn, fn);
    workflowQueue = next.catch(() => {});
    return next;
}

// 工具：异步执行 shell 命令（不阻塞事件循环）
const execPromise = promisify(exec);
async function execAsync(cmd, opts) {
    const { stdout } = await execPromise(cmd, { ...opts, timeout: opts?.timeout ?? 10000 });
    return (stdout || '').trim();
}

// 状态转移表：每个状态允许接收哪些事件、转移到哪个状态。
// main  字段: 主分支 commit（带 Orchestra-Task trailer）是否被接受
// pipeline 字段: refs/orchestra/pipeline 上允许的 verb 列表
// next  字段: 事件触发后的下一状态；主分支 commit 用 '__main__' key
const WORKFLOW_TRANSITIONS = {
    IDLE: {
        main: false,
        pipeline: ['PLAN', 'TASK', 'REVIEW'],
        next: { PLAN: 'BRAIN_PLAN', TASK: 'WORKER_CODE', REVIEW: 'WORKER_CODE' },
    },
    BRAIN_PLAN: {
        main: false,
        pipeline: ['TASK', 'ABORT'],
        next: { TASK: 'WORKER_CODE', ABORT: 'ABORT' },
    },
    WORKER_CODE: {
        main: true,
        pipeline: ['FAIL', 'ABORT'],
        next: { __main__: 'BRAIN_REVIEW', FAIL: 'BRAIN_RECOVER', ABORT: 'ABORT' },
    },
    BRAIN_REVIEW: {
        main: false,
        pipeline: ['TASK', 'REVIEW', 'DONE', 'ABORT'],
        next: { TASK: 'WORKER_CODE', REVIEW: 'WORKER_CODE', DONE: 'DONE', ABORT: 'ABORT' },
    },
    BRAIN_RECOVER: {
        main: false,
        pipeline: ['TASK', 'ABORT'],
        next: { TASK: 'WORKER_CODE', ABORT: 'ABORT' },
    },
    // DONE 是终态，但允许 TASK 回退（Brain 竞态：先 done 后 task）
    DONE: {
        main: false,
        pipeline: ['TASK'],
        next: { TASK: 'WORKER_CODE' },
    },
};

function isTransitionAllowed(state, isMainCommit, verb) {
    const rule = WORKFLOW_TRANSITIONS[state];
    if (!rule) return false;
    return isMainCommit ? rule.main : rule.pipeline.includes(verb);
}

function nextWorkflowState(state, isMainCommit, verb) {
    // 终态自愈交给 /workflow/status 的延迟逻辑处理
    // 但 DONE + TASK 是 Brain 竞态（先 done 后 task），需要允许回退
    if (TERMINAL_VERBS.has(state) && !(state === 'DONE' && verb === 'TASK')) return state;
    const rule = WORKFLOW_TRANSITIONS[state];
    if (!rule) return state;
    return isMainCommit ? rule.next.__main__ : (rule.next[verb] || state);
}

// 按 IDE 名字在 activeCdpTargets 中查端口；找不到返回 null。
function getIdePortByName(ideName) {
    for (const [port, t] of activeCdpTargets) {
        if (t.appName?.toLowerCase() === ideName.toLowerCase()) return port;
    }
    return null;
}

// ─── 流水线 Watchdog（Git 轮询 + 大脑回复自动解析）──────────────
//
// 双保险机制：
//   主路径: Git reference-transaction Hook → /workflow/next（实时）
//   兜底:   Watchdog 定期轮询 Git refs + 读取大脑 IDE 回复（15s 延迟但可靠）
//
// 功能：
//   1. 超时告警（原有）
//   2. Git 轮询：定期 git rev-parse refs/orchestra/pipeline 和 refs/heads/master，
//      对比上次处理的 hash，发现新 commit 时模拟 hook 回调（Hook 的备份通道）
//   3. 大脑回复自动解析：BRAIN_PLAN/BRAIN_REVIEW 状态下通过 CDP 读取大脑 IDE 最后回复，
//      解析 TASK 或 VERDICT/NEXT_ACTION，Relay 自己写入 pipeline ref 推进。
//
const WATCHDOG_INTERVAL_MS = 15_000;
// 大脑回复解析至少等 30 秒再开始（给 AI 生成时间）
const BRAIN_REPLY_MIN_WAIT_MS = 30_000;
// 超时降级阈值：超过此时间未解析到标记，启用 fallback 模式
const BRAIN_PLAN_FALLBACK_MS = 3 * 60 * 1000;   // BRAIN_PLAN 3 分钟后降级
const BRAIN_REVIEW_FALLBACK_MS = 5 * 60 * 1000;  // BRAIN_REVIEW 5 分钟后降级
// WORKER_CODE 自动 commit：Worker IDE 修改文件后可能不自动 commit（如 Windsurf 等待 Accept）
// 当检测到 uncommitted changes 持续超过此阈值时，watchdog 自动 git add + commit
const WORKER_AUTO_COMMIT_DELAY_MS = 60_000;  // 60s 持续有 dirty files → 自动 commit
// 上次处理的 commit hash 追踪（已移入 currentPipeline 持久化）
// 防止并发执行
let _watchdogRunning = false;
// 大脑回复已处理标记（每次进入 BRAIN_REVIEW 重置）
let _brainReplyProcessed = false;
let _brainReviewEnteredAt = 0;
// 回复稳定检测：连续 N 轮读到相同内容 → AI 已停止生成
let _lastReplySnapshot = '';
let _replyStableCount = 0;
const REPLY_STABLE_THRESHOLD = 2; // 连续 2 轮（30s）相同 → 认为生成完成
// WORKER_CODE 自动 commit 状态追踪
let _workerDirtyDetectedAt = 0;   // 首次检测到 dirty files 的时间戳
let _workerLastDirtyFiles = '';     // 上次检测到的 dirty file 列表（用于稳定性判断）

/** 安全执行 git 命令，失败返回 null */
function gitRevParse(ref, cwd) {
    try {
        return execSync(`git rev-parse --verify ${ref} 2>/dev/null`, {
            cwd, encoding: 'utf8', timeout: 5000
        }).trim() || null;
    } catch (_) { return null; }
}

function gitLogMessage(hash, cwd) {
    try {
        return execSync(`git log -1 --pretty=%B ${hash} 2>/dev/null`, {
            cwd, encoding: 'utf8', timeout: 5000
        }).trim() || '';
    } catch (_) { return ''; }
}

function gitStatusPorcelain(cwd) {
    try {
        return execSync('git status --porcelain', {
            cwd, encoding: 'utf8', timeout: 5000
        }).trim();
    } catch (_) { return ''; }
}

function isUtilityCdpPage(page) {
    const haystack = `${page.title || ''}\n${page.url || ''}`.toLowerCase();
    return haystack.includes('settings')
        || haystack.includes('launchpad')
        || haystack.includes('workbench-jetski')
        || haystack.includes('devtools://')
        || haystack.includes('chrome://');
}

function scoreReplyPage(page, cwdHint) {
    if (page.type !== 'page' || !page.webSocketDebuggerUrl || isUtilityCdpPage(page)) return -1;
    const url = page.url || '';
    const title = page.title || '';
    let score = 0;
    if (url.includes('workbench')) score += 30;
    if (url.startsWith('app://')) score += 25;
    if (title && !title.toLowerCase().includes('settings')) score += 5;
    if (cwdHint) {
        const dirName = cwdHint.split('/').filter(Boolean).pop() || '';
        if (dirName && title.toLowerCase().includes(dirName.toLowerCase())) score += 50;
    }
    return score;
}

/** 通过 CDP 读取指定端口 IDE 的最后回复
 * @param {number} cdpPort
 * @param {string} [cwdHint] - pipeline 的 cwd，用于按 page title 精确定位窗口
 */
async function readBrainLastReply(cdpPort, cwdHint) {
    // Cursor 的 getLastReply DOM 查询（移植自 CursorCommands.kt）
    const CURSOR_LAST_REPLY_EXPR = `(function(){
        try {
            var conv = document.querySelector('.conversations');
            if (!conv) conv = document.querySelector('.composer-bar:not(.empty)');
            if (!conv) return '';
            var pairs = conv.querySelectorAll('.composer-human-ai-pair-container');
            for (var pi = pairs.length - 1; pi >= 0; pi--) {
                var pair = pairs[pi];
                var msgs = pair.querySelectorAll('.composer-rendered-message');
                for (var mi = msgs.length - 1; mi >= 0; mi--) {
                    var msg = msgs[mi];
                    var cls = (msg.className || '').toString();
                    if (cls.indexOf('composer-sticky-human-message') >= 0) continue;
                    if (msg.querySelector('.composer-human-message')) continue;
                    if (msg.querySelector('.human-message')) continue;
                    var md = msg.querySelector('.markdown-root');
                    if (md) { var t = (md.innerText || '').trim(); if (t.length > 0) return t; }
                    var t2 = (msg.innerText || '').trim();
                    if (t2.length > 2) return t2;
                }
            }
            var allMd = conv.querySelectorAll('.markdown-root');
            if (allMd.length > 0) {
                var last = allMd[allMd.length - 1];
                var t3 = (last.innerText || '').trim();
                if (t3.length > 0) return t3;
            }
            return '';
        } catch (e) { return ''; }
    })()`;

    // Antigravity / Windsurf 通用的 getLastReply（移植自 AntigravityCommands.kt）
    const GENERIC_LAST_REPLY_EXPR = `(function(){
        try {
            function visible(el) {
                if (!el) return false;
                var r = el.getBoundingClientRect();
                return r.width > 0 && r.height > 0;
            }
            function classStr(el) {
                var c = el.className;
                return (typeof c === 'string' ? c : (el.getAttribute && el.getAttribute('class')) || '') || '';
            }
            function matchesProse(el) {
                var c = classStr(el);
                return c.indexOf('select-text') >= 0 && c.indexOf('leading-relaxed') >= 0;
            }
            function hasSelectText(el) {
                return classStr(el).indexOf('select-text') >= 0;
            }
            function inUserTurn(el) {
                return el.closest && el.closest('[data-message-author-role="user"]');
            }
            function inChatInputArea(el) {
                if (!el || !el.closest) return false;
                var ta = el.closest('textarea');
                if (ta) return true;
                var ce = el.closest('[contenteditable="true"]');
                if (ce) {
                    var p = ce.closest('[class*="composer"], [class*="chat-input"], [class*="interactive"], [class*="aichat"]');
                    if (p) return true;
                }
                return false;
            }
            function isAssistantCandidate(el) {
                if (inChatInputArea(el) || inUserTurn(el)) return false;
                return true;
            }
            function flattenElements(root) {
                var out = [];
                function walk(node) {
                    if (!node) return;
                    if (node.nodeType === 1) {
                        out.push(node);
                        if (node.shadowRoot) walk(node.shadowRoot);
                    }
                    for (var c = node.firstChild; c; c = c.nextSibling) walk(c);
                }
                walk(root);
                return out;
            }
            function tryInPanel(panelNode) {
                var flat = flattenElements(panelNode);
                var combo = flat.filter(function(el) {
                    return matchesProse(el) && visible(el) && isAssistantCandidate(el);
                });
                if (combo.length) {
                    var t = (combo[combo.length - 1].innerText || '').trim();
                    if (t.length) return t;
                }
                var all = flat.filter(function(el) {
                    return hasSelectText(el) && visible(el) && isAssistantCandidate(el);
                });
                if (all.length) {
                    var t2 = (all[all.length - 1].innerText || '').trim();
                    if (t2.length) return t2;
                }
                var articles = panelNode.querySelectorAll('[role="article"], [class*="markdown"], [class*="rendered-markdown"]');
                for (var j = articles.length - 1; j >= 0; j--) {
                    var ar = articles[j];
                    if (inChatInputArea(ar) || !visible(ar)) continue;
                    var txt = (ar.innerText || '').trim();
                    if (txt.length > 2) return txt;
                }
                return null;
            }
            // 遍历所有文档（含 iframe）
            var roots = [document];
            var iframes = document.querySelectorAll('iframe');
            for (var i = 0; i < iframes.length; i++) {
                try { if (iframes[i].contentDocument) roots.push(iframes[i].contentDocument); } catch(e) {}
            }
            for (var di = 0; di < roots.length; di++) {
                var doc = roots[di];
                var asst = doc.querySelectorAll('[data-message-author-role="assistant"]');
                if (asst.length) {
                    var lastA = asst[asst.length - 1];
                    var prose = lastA.querySelector('[class*="select-text"]') || lastA;
                    if (prose && visible(prose) && !inChatInputArea(prose)) {
                        var ta = (prose.innerText || '').trim();
                        if (ta.length) return ta;
                    }
                }
                var p = doc.querySelector('.antigravity-agent-side-panel')
                    || doc.querySelector('[class*="antigravity-agent"]')
                    || doc.querySelector('[class*="interactive-session"]')
                    || doc.querySelector('[class*="aichat"]')
                    || doc.querySelector('[class*="composer"]')
                    || doc.querySelector('[class*="chat-view"]')
                    || doc.querySelector('[class*="cascade-scrollbar"]')
                    || doc.querySelector('[class*="chat-client-root"]')
                    || doc.body || doc;
                var r0 = tryInPanel(p);
                if (r0) return r0;
            }
            return '';
        } catch (e) { return ''; }
    })()`;

    return new Promise((resolve) => {
        const pages_req = http.get(`http://${CDP_HOST}:${cdpPort}/json`, { timeout: 5000 }, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                try {
                    const pages = JSON.parse(data);
                    const ranked = pages
                        .map(p => ({ page: p, score: scoreReplyPage(p, cwdHint) }))
                        .filter(x => x.score >= 0)
                        .sort((a, b) => b.score - a.score);
                    if (ranked.length === 0) { resolve(''); return; }
                    const wb = ranked[0].page;

                    // 根据 IDE 类型选择 DOM 查询
                    const target = activeCdpTargets.get(cdpPort);
                    const isCursor = target?.appName === 'Cursor';
                    const expr = isCursor ? CURSOR_LAST_REPLY_EXPR : GENERIC_LAST_REPLY_EXPR;

                    const ws = new WebSocket(wb.webSocketDebuggerUrl);
                    const timer = setTimeout(() => { ws.close(); resolve(''); }, 10_000);
                    ws.on('open', () => {
                        ws.send(JSON.stringify({
                            id: 1,
                            method: 'Runtime.evaluate',
                            params: { expression: expr, returnByValue: true }
                        }));
                    });
                    ws.on('message', (d) => {
                        try {
                            const r = JSON.parse(d);
                            if (r.id === 1) {
                                clearTimeout(timer);
                                ws.close();
                                resolve(r.result?.result?.value || '');
                            }
                        } catch (_) {}
                    });
                    ws.on('error', () => { clearTimeout(timer); resolve(''); });
                } catch (_) { resolve(''); }
            });
        });
        pages_req.on('error', () => resolve(''));
        pages_req.on('timeout', () => { pages_req.destroy(); resolve(''); });
    });
}

async function autoAcceptWorkerActions(cdpPort, cwdHint) {
    return new Promise((resolve) => {
        const pagesReq = http.get(`http://${CDP_HOST}:${cdpPort}/json`, { timeout: 5000 }, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                let ws;
                let finished = false;
                const done = (value) => {
                    if (finished) return;
                    finished = true;
                    try { if (ws) ws.close(); } catch (_) {}
                    resolve(value);
                };

                try {
                    const pages = JSON.parse(data);
                    const ranked = pages
                        .map(p => ({ page: p, score: scoreReplyPage(p, cwdHint) }))
                        .filter(x => x.score >= 0)
                        .sort((a, b) => b.score - a.score);
                    if (!ranked.length) { done({ found: false, reason: 'no-page' }); return; }

                    const expr = `
                        (function() {
                            function visible(el) {
                                if (!el || !el.getBoundingClientRect) return false;
                                var r = el.getBoundingClientRect();
                                var s = window.getComputedStyle ? getComputedStyle(el) : null;
                                return r.width > 0 && r.height > 0 && (!s || (s.display !== 'none' && s.visibility !== 'hidden'));
                            }
                            function textOf(el) {
                                return ((el.innerText || el.textContent || el.value || '') + '').trim();
                            }
                            function norm(text) {
                                return (text || '').toLowerCase().trim().replace(/\\s+/g, ' ');
                            }
                            function isApproval(text) {
                                var lower = norm(text);
                                var cmd = lower.replace(/[^a-z]/g, '');
                                if (!lower || lower.indexOf('cancel') >= 0 || lower.indexOf('esc') >= 0) return false;
                                return lower === 'run'
                                    || cmd === 'run'
                                    || lower === 'allow'
                                    || lower === 'approve'
                                    || lower === 'continue'
                                    || lower === 'yes'
                                    || lower === 'always allow'
                                    || lower === 'allow once'
                                    || lower === 'allow in workspace'
                                    || lower === 'approve action'
                                    || lower === 'run action'
                                    || (lower.indexOf('run') === 0 && lower.indexOf('(') >= 0)
                                    || (lower.indexOf('allow') === 0 && lower.indexOf('(') >= 0)
                                    || (lower.indexOf('approve') === 0 && lower.indexOf('(') >= 0);
                            }
                            function flatten(root) {
                                var out = [];
                                function walk(node) {
                                    if (!node) return;
                                    if (node.nodeType === 1) {
                                        out.push(node);
                                        if (node.shadowRoot) walk(node.shadowRoot);
                                    }
                                    for (var c = node.firstChild; c; c = c.nextSibling) walk(c);
                                }
                                walk(root);
                                return out;
                            }
                            function fullClick(el) {
                                var r = el.getBoundingClientRect();
                                var x = r.x + r.width / 2;
                                var y = r.y + r.height / 2;
                                ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(type) {
                                    el.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, clientX: x, clientY: y, button: 0 }));
                                });
                                if (typeof el.click === 'function') el.click();
                            }
                            var roots = Array.from(document.querySelectorAll(
                                '.composer-bar, .composer, .chat-view, .aichat, .interactive-session, .unified-agents-sidebar, .pane-body, body'
                            ));
                            if (!roots.length) roots = [document.body || document.documentElement];
                            var seen = new Set();
                            var candidates = [];
                            roots.forEach(function(root) {
                                flatten(root).forEach(function(el) {
                                    if (seen.has(el)) return;
                                    seen.add(el);
                                    var tag = (el.tagName || '').toLowerCase();
                                    var role = el.getAttribute && (el.getAttribute('role') || '');
                                    var cls = ((typeof el.className === 'string' ? el.className : '') || '').toLowerCase();
                                    if (tag !== 'button' && tag !== 'a' && role !== 'button' && cls.indexOf('button') < 0) return;
                                    if (!visible(el) || el.disabled || el.getAttribute('aria-disabled') === 'true') return;
                                    var txt = textOf(el);
                                    var aria = (el.getAttribute('aria-label') || '').trim();
                                    var title = (el.getAttribute('title') || '').trim();
                                    if (isApproval(txt) || isApproval(aria) || isApproval(title) || cls.indexOf('ui-shell-tool-call__run-btn') >= 0) {
                                        candidates.push({ el: el, text: txt || aria || title || cls, cls: cls });
                                    }
                                });
                            });
                            if (!candidates.length) return JSON.stringify({ found: false, reason: 'no-approval-button' });
                            var choice = candidates.find(function(c) { return c.cls.indexOf('ui-shell-tool-call__run-btn') >= 0; }) || candidates[0];
                            fullClick(choice.el);
                            var rect = choice.el.getBoundingClientRect();
                            return JSON.stringify({ found: true, text: choice.text, x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 });
                        })()
                    `;

                    ws = new WebSocket(ranked[0].page.webSocketDebuggerUrl);
                    const timer = setTimeout(() => done({ found: false, reason: 'timeout' }), 10_000);
                    ws.on('open', () => {
                        ws.send(JSON.stringify({
                            id: 1,
                            method: 'Runtime.evaluate',
                            params: { expression: expr, returnByValue: true }
                        }));
                    });
                    ws.on('message', (d) => {
                        try {
                            const r = JSON.parse(d);
                            if (r.id !== 1) return;
                            clearTimeout(timer);
                            const raw = r.result?.result?.value || '{}';
                            done(JSON.parse(raw));
                        } catch (e) {
                            done({ found: false, reason: e.message });
                        }
                    });
                    ws.on('error', e => done({ found: false, reason: e.message }));
                } catch (e) {
                    done({ found: false, reason: e.message });
                }
            });
        });
        pagesReq.on('error', e => resolve({ found: false, reason: e.message }));
        pagesReq.on('timeout', () => { pagesReq.destroy(); resolve({ found: false, reason: 'http-timeout' }); });
    });
}

    // replaced in workflow_utils.js

/** 执行 scripts/orchestra.sh 命令 */
async function execOrchestra(verb, message, cwd) {
    const scriptPath = ORCHESTRA_SCRIPT;
    if (!fs.existsSync(scriptPath)) {
        log(`⚠️ orchestra.sh 不存在: ${scriptPath}`);
        throw new Error('ORCHESTRA_SCRIPT_MISSING');
    }
    try {
        const args = message ? [verb, message] : [verb];
        await execFilePromise("bash", [scriptPath, ...args], { cwd, timeout: 15000 });
        log(`✅ watchdog 自动执行: orchestra ${verb}`);
        return true;
    } catch (e) {
        log(`❌ watchdog orchestra 执行失败: ${e.message}`);
        throw e;
    }
}

/** 模拟 hook 回调：读取新 commit 的 message 并 POST 到 /workflow/next */
async function simulateHookForCommit(ref, hash, cwd) {
    const message = gitLogMessage(hash, cwd);
    if (!message) return;
    log(`🔁 watchdog 补发: ref=${ref} hash=${hash.substring(0,7)}`);
    const body = JSON.stringify({ message, ref, hash });
    return new Promise((resolve) => {
        const req = http.request({
            hostname: '127.0.0.1', port: RELAY_PORT,
            path: '/workflow/next', method: 'POST',
            headers: { 'Content-Type': 'application/json' },
        }, (res) => {
            let data = '';
            res.on('data', c => data += c);
            res.on('end', () => {
                log(`🔁 watchdog 补发结果: ${data.substring(0, 200)}`);
                resolve();
            });
        });
        req.on('error', (e) => { log(`⚠️ watchdog 补发失败: ${e.message}`); resolve(); });
        req.setTimeout(30000);
        req.end(body);
    });
}

async function workflowWatchdogTick() {
    const pl = currentPipeline;
    if (pl.state === 'IDLE' || TERMINAL_VERBS.has(pl.state)) return;

    // ── 1. 超时告警（原有逻辑）──
    const elapsed = Date.now() - pl.state_entered_at;
    const limit = pl.timeouts?.default_ms ?? 180_000;
    if (elapsed > limit && !pl.warned) {
        log(`⏰ 流水线状态 ${pl.state} 已停留 ${Math.round(elapsed/1000)}s (> ${Math.round(limit/1000)}s)，疑似卡住`);
        pl.warned = true;
    }

    // ── 2. Git 轮询：检测 Hook 遗漏的新 commit ──
    const cwd = pl.cwd;
    if (!cwd || !fs.existsSync(path.join(cwd, '.git'))) return;

    // 检查 refs/orchestra/pipeline
    const pipelineHash = gitRevParse('refs/orchestra/pipeline', cwd);
    if (pipelineHash && pipelineHash !== pl.lastSeenPipelineHash) {
        const prev = pl.lastSeenPipelineHash;
        pl.lastSeenPipelineHash = pipelineHash;
        savePipelineState(pl);
        log(`👁️ watchdog 检测到 pipeline ref 变化: ${prev?.substring(0,7)} → ${pipelineHash.substring(0,7)}`);
        await simulateHookForCommit('refs/orchestra/pipeline', pipelineHash, cwd);
        return; // 本轮只处理一个事件
    }

    // 检查 refs/heads/master (或 main)
    let masterRef = 'refs/heads/master';
    let masterHash = gitRevParse(masterRef, cwd);
    if (!masterHash) {
        masterRef = 'refs/heads/main';
        masterHash = gitRevParse(masterRef, cwd);
    }
    if (masterHash && masterHash !== pl.lastSeenMasterHash) {
        const prev = pl.lastSeenMasterHash;
        pl.lastSeenMasterHash = masterHash;
        savePipelineState(pl);
        log(`👁️ watchdog 检测到 ${masterRef} 变化: ${prev?.substring(0,7)} → ${masterHash.substring(0,7)}`);
        await simulateHookForCommit(masterRef, masterHash, cwd);
        return;
    }

    // ── 2b. WORKER_CODE 自动 commit：检测到 uncommitted changes 持续超时 → 自动 commit ──
    if (pl.state === 'WORKER_CODE') {
        try {
            const workerPort = (pl.worker?.port && activeCdpTargets.has(pl.worker.port))
                ? pl.worker.port : getIdePortByName(pl.worker?.ide);
            if (workerPort) {
                const accepted = await autoAcceptWorkerActions(workerPort, cwd);
                if (accepted?.found) {
                    log(`✅ watchdog WORKER_CODE: 自动审批 Worker action (${accepted.text || 'approval'})`);
                }
            }

            const statusOutput = gitStatusPorcelain(cwd);
            if (statusOutput) {
                if (pl.workerBaselineStatus) {
                    if (!_workerDirtyDetectedAt) {
                        log('👁️ watchdog WORKER_CODE: 目标仓库启动前已有未提交修改，跳过自动 commit，等待 Worker 显式提交');
                        _workerDirtyDetectedAt = Date.now();
                        _workerLastDirtyFiles = statusOutput;
                    }
                    return;
                }
                // 有 uncommitted changes
                if (!_workerDirtyDetectedAt) {
                    _workerDirtyDetectedAt = Date.now();
                    _workerLastDirtyFiles = statusOutput;
                    log(`👁️ watchdog WORKER_CODE: 检测到未提交修改 (${statusOutput.split('\n').length} 个文件)`);
                } else {
                    const dirtyDuration = Date.now() - _workerDirtyDetectedAt;
                    const filesChanged = statusOutput !== _workerLastDirtyFiles;
                    if (filesChanged) {
                        // 文件列表还在变化，Worker 还在写代码，重置计时器
                        _workerLastDirtyFiles = statusOutput;
                        _workerDirtyDetectedAt = Date.now();
                    } else if (dirtyDuration >= WORKER_AUTO_COMMIT_DELAY_MS) {
                        // 文件列表稳定超过阈值 → Worker 已停止写入 → 自动 commit
                        log(`🔧 watchdog WORKER_CODE: 文件稳定 ${Math.round(dirtyDuration/1000)}s，自动 commit...`);
                        try {
                            execSync('git add -A', { cwd, timeout: 10000 });
                            const fileCount = statusOutput.split('\n').length;
                            const taskHash = (pl.currentTaskHash || pl.lastSeenPipelineHash || 'unknown').toString().substring(0, 12);
                            const pipelineName = pl.name || 'pair_programming';
                            // 用 spawn 异步执行 git commit，避免 reference-transaction hook 回调死锁
                            // watchdog 下一轮轮询会通过 hash 变化检测到 commit 完成
                            const { spawn } = require('child_process');
                            const child = spawn('git', [
                                'commit', '--no-verify',
                                '-m', `Auto-commit: Worker changes (${fileCount} files)`,
                                '-m', `Orchestra-Task: ${taskHash}`,
                                '-m', `Orchestra-Pipeline: ${pipelineName}`,
                            ], { cwd, stdio: 'ignore', detached: true });
                            child.unref(); // 不等待子进程
                            log(`✅ watchdog 自动 commit 已提交 (${fileCount} 个文件，异步执行)`);
                            _workerDirtyDetectedAt = 0;
                            _workerLastDirtyFiles = '';
                            // commit 会触发 git hook 或下一轮 watchdog 轮询检测到 hash 变化
                        } catch (commitErr) {
                            log(`⚠️ watchdog 自动 commit 失败: ${commitErr.message}`);
                            _workerDirtyDetectedAt = 0; // 重置避免无限重试
                        }
                    }
                }
            } else {
                // 无 uncommitted changes，重置
                if (_workerDirtyDetectedAt) {
                    _workerDirtyDetectedAt = 0;
                    _workerLastDirtyFiles = '';
                }
            }
        } catch (_) { /* git status 失败，忽略 */ }
    } else {
        // 非 WORKER_CODE 状态，重置自动 commit 追踪
        _workerDirtyDetectedAt = 0;
        _workerLastDirtyFiles = '';
    }

    // ── 3a. BRAIN_PLAN 状态：读取大脑输出的任务并自动派发给工人 ──
    if (pl.state === 'BRAIN_PLAN' && !_brainReplyProcessed) {
        const planElapsed = Date.now() - pl.state_entered_at;
        if (planElapsed < BRAIN_REPLY_MIN_WAIT_MS) return;

        const brainPort = (pl.brain?.port && activeCdpTargets.has(pl.brain.port))
            ? pl.brain.port : getIdePortByName(pl.brain?.ide);
        if (!brainPort) { log(`👁️ watchdog BRAIN_PLAN: brain IDE 不在线 (port=${pl.brain?.port})`); return; }

        try {
            const reply = await readBrainLastReply(brainPort, cwd);
            if (!reply || reply.length < 30) {
                log(`👁️ watchdog BRAIN_PLAN: 回复太短或为空 (len=${reply?.length ?? 0}, port=${brainPort}, elapsed=${Math.round(planElapsed/1000)}s)`);
                // 超时后即使回复短，也尝试降级
                if (planElapsed > BRAIN_PLAN_FALLBACK_MS && reply && reply.length > 10) {
                    log(`⏰ watchdog BRAIN_PLAN: 超时降级（短回复），直接作为任务派发`);
                    try {
                        await execOrchestra('task', reply, cwd);
                        _brainReplyProcessed = true;
                        log(`✅ watchdog 超时降级成功`);
                    } catch (err) { log(`⚠️ 超时降级失败: ${err.message}`); }
                }
                return;
            }

            // 回复稳定检测：连续 N 轮内容相同 → 生成已完成
            const replyHash = reply.substring(0, 200) + '|' + reply.length;
            if (replyHash === _lastReplySnapshot) {
                _replyStableCount++;
            } else {
                _lastReplySnapshot = replyHash;
                _replyStableCount = 0;
            }
            const replyStable = _replyStableCount >= REPLY_STABLE_THRESHOLD;

            // 超时降级：超过阈值 + 回复已稳定 → 启用 fallbackFull
            const useFallback = planElapsed > BRAIN_PLAN_FALLBACK_MS && replyStable;
            const taskContent = parseBrainTask(reply, { fallbackFull: useFallback });

            if (!taskContent) {
                if (useFallback) {
                    log(`⏰ watchdog BRAIN_PLAN: 超时 ${Math.round(planElapsed/1000)}s + 回复已稳定，但内容过短无法降级`);
                } else if (replyStable) {
                    log(`👁️ watchdog BRAIN_PLAN: 回复已稳定但未检测到标记（等待超时降级，剩余 ${Math.round((BRAIN_PLAN_FALLBACK_MS - planElapsed)/1000)}s）`);
                } else {
                    log(`👁️ watchdog BRAIN_PLAN: 未检测到标记（回复仍在变化中）`);
                }
                return;
            }

            const method = useFallback ? '超时降级(完整回复)' : '标记解析';
            log(`👁️ watchdog BRAIN_PLAN: 检测到任务（${method}），自动派发给工人`);
            try {
                await execOrchestra('task', taskContent, cwd);
                _brainReplyProcessed = true;
                _lastReplySnapshot = '';
                _replyStableCount = 0;
                log(`✅ watchdog 已自动执行 orchestra task，流水线推进到 WORKER_CODE`);
            } catch (err) {
                log(`⚠️ watchdog BRAIN_PLAN 自动派发失败: ${err.message}`);
                if (err.message === 'ORCHESTRA_SCRIPT_MISSING') {
                    _brainReplyProcessed = true;
                }
            }
        } catch (e) {
            log(`⚠️ watchdog BRAIN_PLAN 读取失败: ${e.message}`);
        }
        return;
    }

    // ── 3. BRAIN_REVIEW 状态：自动读取大脑回复并推进 ──
    if (pl.state === 'BRAIN_REVIEW' && !_brainReplyProcessed) {
        // 等待至少 BRAIN_REPLY_MIN_WAIT_MS 再开始检测（给 AI 生成时间）
        const reviewElapsed = Date.now() - (_brainReviewEnteredAt || pl.state_entered_at);
        if (reviewElapsed < BRAIN_REPLY_MIN_WAIT_MS) return;

        const brainPort = (pl.brain?.port && activeCdpTargets.has(pl.brain.port))
            ? pl.brain.port : getIdePortByName(pl.brain?.ide);
        if (!brainPort) return;

        try {
            const reply = await readBrainLastReply(brainPort, cwd);
            if (!reply || reply.length < 50) return; // 回复太短，可能还在生成

            // 回复稳定检测（复用 BRAIN_PLAN 的变量）
            const replyHash = reply.substring(0, 200) + '|' + reply.length;
            if (replyHash === _lastReplySnapshot) {
                _replyStableCount++;
            } else {
                _lastReplySnapshot = replyHash;
                _replyStableCount = 0;
            }
            const replyStable = _replyStableCount >= REPLY_STABLE_THRESHOLD;

            let parsed = parseBrainVerdict(reply);

            // 超时降级：超过阈值 + 回复已稳定 + 未解析到 VERDICT → 默认 NEEDS_REWORK
            if (!parsed && reviewElapsed > BRAIN_REVIEW_FALLBACK_MS && replyStable) {
                log(`⏰ watchdog BRAIN_REVIEW: 超时 ${Math.round(reviewElapsed/1000)}s + 回复已稳定，降级为 NEEDS_REWORK`);
                parsed = {
                    verdict: 'NEEDS_REWORK',
                    issues: '（审查超时降级）大脑未输出标准 VERDICT 格式，请根据上次审查意见继续改进',
                    summary: '超时降级',
                };
            }

            if (!parsed) {
                if (replyStable) {
                    log(`👁️ watchdog: 大脑回复已稳定但未检测到 VERDICT（等待超时降级，剩余 ${Math.round((BRAIN_REVIEW_FALLBACK_MS - reviewElapsed)/1000)}s）`);
                } else {
                    log(`👁️ watchdog: 大脑回复中未检测到 VERDICT（可能还在生成或格式不匹配）`);
                }
                return;
            }

            log(`👁️ watchdog 检测到大脑 VERDICT: ${parsed.verdict} (summary: ${parsed.summary})`);

            const round = pl.reviewRound || 0;
            const minRounds = pl.minReviewRounds || DEFAULT_MIN_REVIEW_ROUNDS;

            let success = false;
            try {
                if (parsed.verdict === 'NEEDS_REWORK') {
                    // 需要返工：提取 ISSUES 作为返工消息
                    const reviewMsg = parsed.issues || parsed.summary || '请修复审查发现的问题';
                    success = await execOrchestra('review', reviewMsg, cwd);
                } else if (parsed.verdict === 'PASS') {
                    if (parsed.nextAction === 'TASK' && parsed.nextTask) {
                        success = await execOrchestra('task', parsed.nextTask, cwd);
                    } else if (parsed.nextAction === 'TASK') {
                        log('⚠️ watchdog BRAIN_REVIEW: NEXT_ACTION=TASK 但未找到 NEXT_TASK，等待 Brain 补全任务块');
                        return;
                    } else if (round >= minRounds) {
                        // 通过且轮次足够：完成
                        success = await execOrchestra('done', null, cwd);
                    } else {
                        // 通过但轮次不足：直接递增轮次，重置回复处理标记让 Brain 重新审查
                        // 不回退到 WORKER_CODE（Worker 已经 PASS 了，无事可做会卡住）
                        pl.reviewRound = round + 1;
                        savePipelineState(pl);
                        log(`🔄 质量门：PASS 但轮次不足(${round}/${minRounds})，递增到 round=${round+1}，保持 BRAIN_REVIEW 等待下一轮审查`);
                        // 重置标记让 watchdog 下一轮重新读取 Brain 回复
                        // 注意：必须 return 提前退出，否则下方 success 分支会把
                        // _brainReplyProcessed 立刻覆盖回 true，导致多轮审查永久卡死。
                        _brainReplyProcessed = false;
                        _lastReplySnapshot = '';
                        _replyStableCount = 0;
                        _brainReviewEnteredAt = Date.now(); // 重置等待计时
                        pushEvent(pl, {
                            type: 'quality_gate',
                            from: 'BRAIN_REVIEW',
                            to: 'BRAIN_REVIEW',
                            verb: 'GATE',
                            summary: `质量门：PASS(${round}/${minRounds}) → 继续审查`,
                        });
                        return; // 提前退出，不走 success 后处理
                    }
                } else {
                    success = true;
                }

                if (success) {
                    _brainReplyProcessed = true; // 仅在成功推进流水线后标记
                    _lastReplySnapshot = '';
                    _replyStableCount = 0;
                }
            } catch (err) {
                log(`⚠️ watchdog 自动推进流水线失败: ${err.message}`);
                if (err.message === 'ORCHESTRA_SCRIPT_MISSING') {
                    _brainReplyProcessed = true; // 文件缺失，不再重试
                }
            }
        } catch (e) {
            log(`⚠️ watchdog 大脑回复读取失败: ${e.message}`);
        }
    }
}

let _workflowWatchdog = null;
if (ENABLE_WATCHDOG) {
    _workflowWatchdog = setInterval(async () => {
        if (_watchdogRunning) return; // 防止上一轮还没结束
        _watchdogRunning = true;
        try { await workflowWatchdogTick(); }
        catch (e) { log(`⚠️ watchdog tick: ${e.message}`); }
        finally { _watchdogRunning = false; }
    }, WATCHDOG_INTERVAL_MS);
    if (typeof _workflowWatchdog.unref === 'function') _workflowWatchdog.unref();
}

/** 从进程命令行参数中提取 IDE 的工作目录（最后一个绝对路径参数） */
function getWorkspaceForPort(port) {
    try {
        const raw = execSync(
            `ps -eo args | grep "remote-debugging-port=${port}" | grep -v "type=" | grep -v grep | head -1`,
            { encoding: 'utf8', timeout: 3000 }
        ).trim();
        if (!raw) return null;
        // 参数从后往前找第一个存在的绝对路径目录
        const parts = raw.split(/\s+/);
        for (let i = parts.length - 1; i >= 0; i--) {
            const arg = parts[i];
            if (arg.startsWith('/') && !arg.includes('.app/') && !arg.startsWith('--')) {
                try { if (fs.statSync(arg).isDirectory()) return arg; } catch (_) {}
            }
        }
    } catch (_) {}
    return null;
}

function openCwdInRunningIde(port, appName, cwd) {
    if (!cwd) return;
    const target = activeCdpTargets.get(port);
    const resolvedAppName = appName || target?.appName || '';
    const appInfo = ELECTRON_APPS.find(a => a.name.toLowerCase() === resolvedAppName.toLowerCase());
    if (!appInfo) return;

    addCwdHistory(cwd, resolvedAppName);
    if (resolvedAppName.toLowerCase() === 'codex') {
        try {
            ensureCodexWorkspaceRoot(cwd, { activate: true });
        } catch (e) {
            log(`⚠️ Codex workspace 写入失败: ${e.message}`);
            throw e;
        }
    }
    let extractedUserDataDir = null;
    try {
        const pgrepOut = execSync(`pgrep -f -- "--remote-debugging-port=${port}" 2>/dev/null || true`).toString().trim();
        const pids = pgrepOut.split('\n').filter(Boolean);
        if (pids.length > 0) {
            const psOut = execSync(`ps -ww -o command= -p ${pids[0]}`).toString();
            const match = psOut.match(/--user-data-dir=(["']?)([^"'\s]+)\1/);
            if (match) extractedUserDataDir = match[2];
        }
    } catch (e) {
        log(`⚠️ 获取 CDP :${port} PID 信息失败: ${e.message}`);
    }

    const openArgs = [];
    if (extractedUserDataDir) openArgs.push(`--user-data-dir=${extractedUserDataDir}`);
    const proxyFlag = getElectronProxyFlag();
    if (proxyFlag) openArgs.push(proxyFlag);
    openArgs.push(cwd);

    log(`📂 CDP :${port} 打开工作目录: ${cwd}`);
    const userEnv = getUserEnv();
    spawn('/usr/bin/open',
        ['-n', '-a', appInfo.appPath, '--args', ...openArgs],
        { detached: true, stdio: 'ignore', env: { ...process.env, ...userEnv } }).unref();
}

async function reloadCdpPage(port, { pageMatcher = null } = {}) {
    try {
        const pages = await getJson(`http://${CDP_HOST}:${port}/json`, 3000);
        const page = pages.find(p =>
            p.type === 'page' &&
            p.webSocketDebuggerUrl &&
            (!pageMatcher || pageMatcher(p))
        );
        if (!page) return { reloaded: false, error: 'page target not found' };

        return await new Promise((resolve) => {
            const ws = new WebSocket(page.webSocketDebuggerUrl);
            const timer = setTimeout(() => {
                try { ws.close(); } catch (_) {}
                resolve({ reloaded: false, error: 'Page.reload timeout' });
            }, 5000);

            ws.on('open', () => {
                ws.send(JSON.stringify({ id: 1, method: 'Page.reload', params: { ignoreCache: true } }));
            });
            ws.on('message', (buf) => {
                try {
                    const msg = JSON.parse(buf.toString());
                    if (msg.id === 1) {
                        clearTimeout(timer);
                        try { ws.close(); } catch (_) {}
                        if (msg.error) resolve({ reloaded: false, error: msg.error.message || 'Page.reload failed' });
                        else resolve({ reloaded: true });
                    }
                } catch (_) {}
            });
            ws.on('error', (e) => {
                clearTimeout(timer);
                resolve({ reloaded: false, error: e.message });
            });
        });
    } catch (e) {
        return { reloaded: false, error: e.message };
    }
}

function defaultWorkflowPortForIde(ideName) {
    const key = Object.keys(DEFAULT_WORKFLOW_IDE_PORTS)
        .find(name => name.toLowerCase() === String(ideName || '').toLowerCase());
    return key ? DEFAULT_WORKFLOW_IDE_PORTS[key] : null;
}

async function maybeSwitchCursorComposerModel(port, modelName = 'Composer 2') {
    const scriptPath = path.join(os.homedir(), '.agents', 'skills', 'cursor', 'scripts', 'cursor_switch_model.js');
    if (!fs.existsSync(scriptPath)) {
        log(`⚠️ Cursor 模型切换脚本不存在，跳过: ${scriptPath}`);
        return { ok: false, skipped: true, error: 'missing cursor_switch_model.js' };
    }
    try {
        const { stdout } = await execFilePromise(
            process.execPath,
            [scriptPath, modelName, '--port', String(port), '--quiet'],
            { timeout: 45_000, env: { ...process.env, CURSOR_CDP_PORT: String(port) } }
        );
        log(`🎚️ Cursor:${port} 已尝试切换模型 → ${modelName}${stdout ? ` (${stdout.trim()})` : ''}`);
        return { ok: true, model: modelName };
    } catch (e) {
        const detail = (e.stderr || e.message || '').toString().trim();
        log(`⚠️ Cursor:${port} 切换模型到 ${modelName} 失败（不阻断流水线）: ${detail || e.message}`);
        return { ok: false, model: modelName, error: detail || e.message };
    }
}

async function ensureWorkflowIdeReady({ ideName, requestedPort, cwd, roleName }) {
    await scanAllCdpPorts();
    let port = requestedPort || getIdePortByName(ideName) || defaultWorkflowPortForIde(ideName);
    if (!port) {
        throw new Error(`${roleName} IDE ${ideName} 未指定端口，且没有默认端口配置`);
    }

    const existing = activeCdpTargets.get(port);
    if (existing) {
        if (existing.appName.toLowerCase() !== ideName.toLowerCase()) {
            throw new Error(`${roleName} 端口 ${port} 已被 ${existing.appName} 占用，不能作为 ${ideName}`);
        }
        openCwdInRunningIde(port, existing.appName, cwd);
        return { port, launched: false, appName: existing.appName };
    }

    log(`🚀 /workflow/start: ${roleName} ${ideName}:${port} 未在线，自动启动并打开 ${cwd}`);
    const ok = await autoLaunchWithCdp({ force: true, port, appName: ideName, cwd });
    await scanAllCdpPorts();
    if (!ok || !activeCdpTargets.has(port)) {
        throw new Error(`${roleName} ${ideName}:${port} 自动启动失败`);
    }
    const launched = activeCdpTargets.get(port);
    if (launched?.appName && launched.appName.toLowerCase() !== ideName.toLowerCase()) {
        throw new Error(`${roleName} ${ideName}:${port} 启动后识别为 ${launched.appName}`);
    }
    return { port, launched: true, appName: launched?.appName || ideName };
}

// ─── 多端口扫描 ───

function scanSinglePort(port) {
    return new Promise((resolve) => {
        const req = http.get(`http://${CDP_HOST}:${port}/json`, { timeout: SCAN_TIMEOUT }, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                try {
                    const pages = JSON.parse(data);
                    if (Array.isArray(pages) && pages.length > 0) {
                        const app = detectAppType(pages);
                        resolve({ port, pages, appName: app.name, appEmoji: app.emoji });
                    } else { resolve(null); }
                } catch (e) { resolve(null); }
            });
        });
        req.on('error', () => resolve(null));
        req.on('timeout', () => { req.destroy(); resolve(null); });
    });
}

async function scanAllCdpPorts() {
    const ports = new Set();
    for (let p = CDP_PORT_MIN; p <= CDP_PORT_MAX; p++) ports.add(p);
    if (CDP_PORT < CDP_PORT_MIN || CDP_PORT > CDP_PORT_MAX) ports.add(CDP_PORT);

    const results = await Promise.allSettled([...ports].map(p => scanSinglePort(p)));

    const foundPorts = new Set();
    for (const r of results) {
        if (r.status === 'fulfilled' && r.value) {
            const { port, pages, appName, appEmoji } = r.value;
            activeCdpTargets.set(port, { appName, appEmoji, pages, lastSeen: Date.now() });
            foundPorts.add(port);

        }
    }
    // 清理已消失的目标
    for (const port of activeCdpTargets.keys()) {
        if (!foundPorts.has(port)) activeCdpTargets.delete(port);
    }
    return activeCdpTargets.size;
}

/** 获取默认 CDP 端口（优先用配置的，否则取第一个发现的） */
function getDefaultCdpPort() {
    if (activeCdpTargets.has(CDP_PORT)) return CDP_PORT;
    const first = activeCdpTargets.keys().next();
    return first.done ? CDP_PORT : first.value;
}

/** 重写 CDP 响应中的 URL，加入 /cdp/{port} 路径前缀 */
function rewriteCdpUrls(body, cdpPort, relayHost) {
    return body
        .replace(new RegExp(`ws://127\\.0\\.0\\.1:${cdpPort}/`, 'g'), `ws://${relayHost}/cdp/${cdpPort}/`)
        .replace(new RegExp(`ws://localhost:${cdpPort}/`, 'g'), `ws://${relayHost}/cdp/${cdpPort}/`)
        .replace(new RegExp(`127\\.0\\.0\\.1:${cdpPort}`, 'g'), `${relayHost}/cdp/${cdpPort}`);
}

// ─── 自动启动（真·多实例版） ───

async function checkCdpHealthSingle(port) {
    return new Promise((resolve) => {
        const req = http.get(`http://${CDP_HOST}:${port}/json`, { timeout: 3000 }, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                try {
                    const pages = JSON.parse(data);
                    const workbench = pages.find(p => p.type === 'page' && p.url && p.url.includes('workbench'));
                    resolve({ available: true, pages: pages.length, workbench: !!workbench });
                } catch (e) { resolve({ available: false }); }
            });
        });
        req.on('error', () => resolve({ available: false }));
        req.on('timeout', () => { req.destroy(); resolve({ available: false }); });
    });
}

/**
 * 自动启动 IDE（真·多实例模式）
 *
 * 关键改动：
 *   - **绝不杀旧进程**，已有实例保持运行
 *   - 使用 `open -n -a` 启动全新独立实例（-n = new instance）
 *   - 每个实例拥有独立的 CDP 调试端口，互不干扰
 *
 * @param {Object} opts
 * @param {boolean} opts.force - 用户主动触发
 * @param {number} opts.port - CDP 端口（默认 CDP_PORT）
 * @param {string} opts.appName - 指定应用名（可选，如 'Antigravity'）
 * @param {boolean} opts.lite - 省内存模式：禁用 GPU、限制堆内存、禁用扩展和遥测
 */
async function autoLaunchWithCdp({ force = false, port = CDP_PORT, appName = '', cwd = '', lite = false } = {}) {
    const isCodexLaunch = String(appName || '').toLowerCase() === 'codex' || port === 9666;
    if (isCodexLaunch && cwd) ensureCodexWorkspaceRoot(cwd, { activate: true });

    const preCheck = await checkCdpHealthSingle(port);
    if (preCheck.available) {
        log(`✅ autoLaunch: CDP :${port} 已可用 (${preCheck.pages} 个页面)，跳过启动`);
        return true;
    }

    // 检查端口是否被其他非 CDP 进程占用
    try {
        const pgrep = execSync(`pgrep -f -- "--remote-debugging-port=${port}" 2>/dev/null || true`).toString().trim();
        if (pgrep) {
            log(`⚠️  端口 :${port} 可能已被占用 (PIDs: ${pgrep.replace(/\n/g, ', ')})`);
            if (!force) return false;
        }
    } catch (e) { }

    // 如果指定了 appName，只尝试该应用；否则遍历所有
    const appsToTry = appName
        ? ELECTRON_APPS.filter(a => a.name.toLowerCase() === appName.toLowerCase())
        : ELECTRON_APPS;

    for (const app of appsToTry) {
        try {
            if (!fs.existsSync(app.appPath)) continue;

            // 检查是否已有该应用的实例运行（仅做日志，不杀！）
            const running = execSync(`pgrep -f "${app.binPath}" 2>/dev/null || true`).toString().trim();
            if (running) {
                const existingPorts = [...activeCdpTargets.entries()]
                    .filter(([_, t]) => t.appName === app.name)
                    .map(([p, _]) => p);
                log(`ℹ️  ${app.name} 已有 ${running.split('\n').length} 个进程运行 (CDP 端口: ${existingPorts.join(', ') || '无'})`);
                log(`🚀 启动 ${app.name} 新实例 (CDP 端口: ${port})...`);
            } else {
                log(`🚀 首次启动 ${app.name} (CDP 端口: ${port})...`);
            }

            // 判断是否已有该 app 的实例在运行
            // 预制端口：9333-9336 (Antigravity), 9666 (Codex) 已预先复制了登录数据
            const PREPROVISIONED_PORTS = [9333, 9334, 9335, 9336, 9666];
            const isPreprovisioned = PREPROVISIONED_PORTS.includes(port);
            const launchArgs = [`--remote-debugging-port=${port}`];

            // ── 省内存模式：注入轻量化启动参数 ──
            if (lite) {
                launchArgs.push('--disable-gpu');
                launchArgs.push('--js-flags=--max-old-space-size=512');
                launchArgs.push('--disable-extensions');
                launchArgs.push('--disable-telemetry');
                log(`🪶 省内存模式已启用: --disable-gpu --max-old-space-size=512 --disable-extensions --disable-telemetry`);
            }

            if (!running && port === 9666 && app.name === 'Codex') {
                log(`📋 Codex 首实例 (端口 9666)，使用默认配置`);
            } else if (!running && port === 9333) {
                log(`📋 首实例 (端口 9333)，使用默认配置`);
            } else {
                // 多实例或预制端口：使用独立 user-data-dir
                const defaultDataDir = path.join(os.homedir(), 'Library/Application Support', app.name);
                const userDataDir = path.join(os.homedir(), '.cdp-instances', `${app.name}-${port}`);
                const hasUserData = fs.existsSync(path.join(userDataDir, 'User')) || fs.existsSync(path.join(userDataDir, 'Cookies'));
                const hasDefaultData = fs.existsSync(path.join(defaultDataDir, 'User')) || fs.existsSync(path.join(defaultDataDir, 'Cookies'));
                if (isPreprovisioned && hasUserData) {
                    log(`📋 使用预制数据目录: ${userDataDir} (端口 ${port})`);
                } else if (!hasUserData && hasDefaultData) {
                    log(`📋 复制用户配置: ${defaultDataDir} → ${userDataDir}`);
                    fs.mkdirSync(userDataDir, { recursive: true });
                    const authFiles = ['User', 'Cookies', 'Cookies-journal', 'Local Storage', 'Session Storage',
                        'Preferences', 'Trust Tokens', 'Trust Tokens-journal', 'TransportSecurity', 'Network Persistent State', 'machineid'];
                    for (const f of authFiles) {
                        const src = path.join(defaultDataDir, f);
                        const dst = path.join(userDataDir, f);
                        try {
                            if (fs.existsSync(src)) {
                                execSync(`cp -R "${src}" "${dst}"`);
                            }
                        } catch (e) { log(`⚠️ 复制 ${f} 失败: ${e.message}`); }
                    }
                } else {
                    fs.mkdirSync(userDataDir, { recursive: true });
                }
                launchArgs.push(`--user-data-dir=${userDataDir}`);

                // 注入关键设置：禁用 Workspace Trust 弹窗等阻断性对话框，
                // 防止 /workflow/start 拉起的 IDE 被模态窗口卡死。
                const instanceSettings = path.join(userDataDir, 'User', 'settings.json');
                try {
                    const REQUIRED_SETTINGS = {
                        'security.workspace.trust.enabled': false,
                        'security.workspace.trust.untrustedFiles': 'open',
                        'application.shellEnvironmentResolutionTimeout': 30,
                    };
                    let existing = {};
                    if (fs.existsSync(instanceSettings)) {
                        try { existing = JSON.parse(fs.readFileSync(instanceSettings, 'utf8')); } catch (_) {}
                    } else {
                        fs.mkdirSync(path.join(userDataDir, 'User'), { recursive: true });
                    }
                    let dirty = false;
                    for (const [k, v] of Object.entries(REQUIRED_SETTINGS)) {
                        if (existing[k] !== v) { existing[k] = v; dirty = true; }
                    }
                    if (dirty) {
                        fs.writeFileSync(instanceSettings, JSON.stringify(existing, null, 4));
                        log(`🔧 已注入关键设置 → ${instanceSettings}`);
                    }
                } catch (e) { log(`⚠️ 注入设置失败（不阻断启动）: ${e.message}`); }
            }
            const proxyFlag = getElectronProxyFlag();
            if (proxyFlag) launchArgs.push(proxyFlag);
            if (cwd) launchArgs.push(cwd);

            _cachedUserEnv = null; // 每次启动前强制刷新环境变量
            const userEnv = getUserEnv();
            log(`📋 执行: open -n -a "${app.appPath}" --args ${launchArgs.join(' ')}`);
            // 走 /usr/bin/open 经 LaunchServices 启动：新 App 在独立 session/进程组，
            // relay 被 launchd 重启时不会再连坐杀掉 IDE（对应 plist AbandonProcessGroup=true 的纵深防御）。
            const child = spawn('/usr/bin/open',
                ['-n', '-a', app.appPath, '--args', ...launchArgs],
                { detached: true, stdio: 'ignore', env: { ...process.env, ...userEnv } });
            child.unref();

            // 等待新实例 CDP 端口就绪
            for (let i = 0; i < 30; i++) {
                await sleep(1000);
                const health = await checkCdpHealthSingle(port);
                if (health.available) {
                    const totalInstances = [...activeCdpTargets.values()]
                        .filter(t => t.appName === app.name).length + 1;
                    log(`✅ ${app.name} 新实例 CDP :${port} 就绪 (${health.pages} 个页面，共 ${totalInstances} 个实例)`);
                    return true;
                }
            }
            log(`⚠️  ${app.name} 新实例启动超时 (端口 :${port} 未响应)`);
            return false;
        } catch (e) { log(`❌ ${app.name} 启动失败: ${e.message}`); }
    }
    return false;
}

// ─── 解析路径中的 CDP 端口 ───

const CDP_PATH_RE = /^\/cdp\/(\d+)(\/.*)/;

function parseCdpPath(url) {
    const m = url.match(CDP_PATH_RE);
    if (m) return { cdpPort: parseInt(m[1]), path: m[2] };
    return { cdpPort: getDefaultCdpPort(), path: url };
}

// ─── 全局崩溃防护 ───
// 防止未捕获异常搞垮 HTTP server（async handler 里的 throw 不会被 createServer 捕获）
process.on('uncaughtException', (err) => {
    log(`💥 [CRASH GUARD] uncaughtException: ${err.message}\n${err.stack}`);
    // 不退出进程——让 HTTP server 继续服务
});
process.on('unhandledRejection', (reason) => {
    log(`💥 [CRASH GUARD] unhandledRejection: ${reason}`);
});

// ─── HTTP 代理 ───

const server = http.createServer(async (req, res) => {
  try {
    const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS', 'Access-Control-Allow-Headers': '*' };

    // ── 全局 CORS 预检 ──
    // IDE Electron 的 fetch() 跨域（origin: vscode-file:// → http://127.0.0.1）
    // 浏览器会先发 OPTIONS 预检请求，必须在所有路由之前统一处理
    if (req.method === 'OPTIONS') {
        res.writeHead(204, cors);
        res.end();
        return;
    }

    const pathname = (urlModule.parse(req.url || '', true).pathname) || '';

    // ── uitty 「新建会话」向导脚本（uitty index 里 <script src="…/uitty_compat/open_new_session_wizard.js">）
    if (pathname === '/uitty_compat/open_new_session_wizard.js') {
        const wizPath = path.join(__dirname, 'uitty_compat', 'open_new_session_wizard.js');
        if (!fs.existsSync(wizPath)) {
            res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8', ...cors });
            res.end('uitty_compat script missing on relay');
            return;
        }
        res.writeHead(200, {
            'Content-Type': 'application/javascript; charset=utf-8',
            'Cache-Control': 'public, max-age=3600',
            ...cors
        });
        res.end(fs.readFileSync(wizPath, 'utf8'));
        return;
    }

    // ── /health ──
    if (req.url === '/health') {
        const targets = [];
        for (const [port, t] of activeCdpTargets) {
            targets.push({ port, appName: t.appName, pages: t.pages.length });
        }
        res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
        res.end(JSON.stringify({
            status: 'ok',
            relay: { port: RELAY_PORT, uptime: process.uptime() | 0 },
            cdp: { available: activeCdpTargets.size > 0, totalTargets: activeCdpTargets.size, targets }
        }));
        return;
    }

    // ── /targets — 核心：返回所有发现的 CDP 实例 ──
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
                            if (typeof tabs === 'undefined') return [];
                            let res = [];
                            tabs.forEach(tab => tab.panes.forEach(p => {
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
    }

    // ── /dirs (浏览服务器目录，辅助选择工作区) ──
    if (req.url.startsWith('/dirs')) {
        const parsed = urlModule.parse(req.url, true);
        let reqPath = parsed.query.path || os.homedir();

        // 允许特殊的 ~ 符号
        if (reqPath.startsWith('~')) {
            reqPath = path.join(os.homedir(), reqPath.slice(1));
        }

        let dirs = [];
        try {
            if (fs.existsSync(reqPath)) {
                const items = fs.readdirSync(reqPath, { withFileTypes: true });
                dirs = items
                    .filter(i => i.isDirectory() && !i.name.startsWith('.'))
                    .map(i => ({ name: i.name, path: path.join(reqPath, i.name) }));
            }
        } catch (e) {
            log('读取目录失败: ' + e.message);
        }

        // 返回父目录路径
        const parentPath = path.dirname(reqPath);

        res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
        res.end(JSON.stringify({ current: reqPath, parent: parentPath, dirs }));
        return;
    }

    // ── /mkdir (创建新目录) ──
    if (req.url.startsWith('/mkdir')) {
        const parsed = urlModule.parse(req.url, true);
        let reqPath = parsed.query.path;
        if (reqPath && reqPath.startsWith('~')) {
            reqPath = path.join(os.homedir(), reqPath.slice(1));
        }

        let success = false;
        let errorMsg = null;
        try {
            if (reqPath) {
                fs.mkdirSync(reqPath, { recursive: true });
                success = true;
            }
        } catch (e) {
            errorMsg = e.message;
            log('创建目录失败: ' + e.message);
        }
        res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
        res.end(JSON.stringify({ success, path: reqPath, error: errorMsg }));
        return;
    }

    // ── /cwd_history (目录历史管理) ──
    if (req.url.startsWith('/cwd_history')) {
        const parsed = urlModule.parse(req.url, true);
        if (req.method === 'GET') {
            res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
            res.end(JSON.stringify({ history: loadCwdHistory() }));
            return;
        }
        if (req.method === 'POST') {
            let body = '';
            req.on('data', chunk => body += chunk);
            req.on('end', () => {
                try {
                    const { path: cwdPath, app } = JSON.parse(body);
                    if (cwdPath) addCwdHistory(cwdPath, app);
                    res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({ ok: true }));
                } catch (e) {
                    res.writeHead(400, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({ error: e.message }));
                }
            });
            return;
        }
        if (req.method === 'DELETE') {
            const pathToRemove = parsed.query.path;
            if (pathToRemove) {
                let history = loadCwdHistory();
                history = history.filter(h => h.path !== pathToRemove);
                saveCwdHistory(history);
            }
            res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
            res.end(JSON.stringify({ ok: true }));
            return;
        }
        res.writeHead(405, { 'Content-Type': 'application/json', ...cors });
        res.end(JSON.stringify({ error: 'Method not allowed' }));
        return;
    }

    // ═══════════════════════════════════════════════════════════════
    // ── 调度引擎 (服务端定时任务) ─────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    // ── /scheduler API ──
    if (req.url.startsWith('/scheduler')) {
        const parsed = urlModule.parse(req.url, true);
        const subPath = parsed.pathname.replace(/^\/scheduler\/?/, '');

        if (req.method === 'GET' && (!subPath || subPath === '')) {
            // GET /scheduler — 列出所有任务
            const tasks = schedulerListTasks();
            res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
            res.end(JSON.stringify({ tasks }));
            return;
        }

        if (req.method === 'POST' && (!subPath || subPath === '')) {
            // POST /scheduler — 创建/更新任务
            let body = '';
            req.on('data', chunk => body += chunk);
            req.on('end', () => {
                try {
                    const task = JSON.parse(body);
                    const result = schedulerUpsertTask(task);
                    res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify(result));
                } catch (e) {
                    res.writeHead(400, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({ error: e.message }));
                }
            });
            return;
        }

        // POST /scheduler/pause?id=xxx — 暂停任务（停止定时器，保留任务）
        if (req.method === 'POST' && subPath === 'pause') {
            const taskId = parsed.query.id;
            if (!taskId) {
                res.writeHead(400, { 'Content-Type': 'application/json', ...cors });
                res.end(JSON.stringify({ error: '缺少 id 参数' }));
                return;
            }
            const ok = schedulerPauseTask(taskId);
            if (!ok) {
                res.writeHead(404, { 'Content-Type': 'application/json', ...cors });
                res.end(JSON.stringify({ error: '任务不存在' }));
                return;
            }
            res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
            res.end(JSON.stringify({ success: ok, id: taskId, paused: true }));
            return;
        }

        // POST /scheduler/resume?id=xxx — 恢复任务
        if (req.method === 'POST' && subPath === 'resume') {
            const taskId = parsed.query.id;
            if (!taskId) {
                res.writeHead(400, { 'Content-Type': 'application/json', ...cors });
                res.end(JSON.stringify({ error: '缺少 id 参数' }));
                return;
            }
            const ok = schedulerResumeTask(taskId);
            if (!ok) {
                res.writeHead(404, { 'Content-Type': 'application/json', ...cors });
                res.end(JSON.stringify({ error: '任务不存在' }));
                return;
            }
            res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
            res.end(JSON.stringify({ success: ok, id: taskId, paused: false }));
            return;
        }

        // POST /scheduler/trigger?id=xxx — 手动触发一次
        if (req.method === 'POST' && subPath === 'trigger') {
            const taskId = parsed.query.id;
            if (!taskId) {
                res.writeHead(400, { 'Content-Type': 'application/json', ...cors });
                res.end(JSON.stringify({ error: '缺少 id 参数' }));
                return;
            }
            const task = schedulerLoadTasks().find(t => t.id === taskId);
            if (!task) {
                res.writeHead(404, { 'Content-Type': 'application/json', ...cors });
                res.end(JSON.stringify({ error: '任务不存在' }));
                return;
            }
            schedulerExecuteTask({ ...task, __manualTrigger: true });
            res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
            res.end(JSON.stringify({ success: true, id: taskId, triggered: true }));
            return;
        }

        if (req.method === 'DELETE') {
            // DELETE /scheduler?id=xxx
            const taskId = parsed.query.id || subPath;
            if (!taskId) {
                res.writeHead(400, { 'Content-Type': 'application/json', ...cors });
                res.end(JSON.stringify({ error: '缺少 id 参数' }));
                return;
            }
            const ok = schedulerCancelTask(taskId);
            res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
            res.end(JSON.stringify({ success: ok, id: taskId }));
            return;
        }

        // OPTIONS
        if (req.method === 'OPTIONS') {
            res.writeHead(204, cors);
            res.end();
            return;
        }

        res.writeHead(405, { 'Content-Type': 'application/json', ...cors });
        res.end(JSON.stringify({ error: 'Method not allowed' }));
        return;
    }

    // ── /upload — 附件直传通道：Android 上传原始二进制，IDE 端通过 fetch() 下载 ──
    // POST /upload?filename=image.png&mime=image/png  body: raw binary
    // 返回: { url: "http://relay:19336/tmp/xxx_image.png" }
    // 文件自动清理：图片 60 秒，其他附件 10 分钟
    if (req.url.startsWith('/upload') && req.method === 'POST') {
        const parsed = urlModule.parse(req.url, true);
        const filename = (parsed.query.filename || 'image.bin').replace(/[^a-zA-Z0-9._\-]/g, '_');
        const mime = String(parsed.query.mime || 'application/octet-stream').replace(/[\r\n"]/g, '') || 'application/octet-stream';
        const UPLOAD_MAX = 100 * 1024 * 1024; // 100MB 上限（支持视频文件）
        const tmpDir = path.join(os.tmpdir(), 'cdp-relay-uploads');
        fs.mkdirSync(tmpDir, { recursive: true });
        const safeName = `${Date.now()}_${filename}`;
        const filePath = path.join(tmpDir, safeName);
        const metaPath = `${filePath}.json`;
        const out = fs.createWriteStream(filePath);
        let totalLen = 0;
        let tooLarge = false;
        let finished = false;

        const failUpload = (status, message) => {
            if (finished) return;
            finished = true;
            try { out.destroy(); } catch (_) {}
            try { fs.unlinkSync(filePath); } catch (_) {}
            try { fs.unlinkSync(metaPath); } catch (_) {}
            res.writeHead(status, { 'Content-Type': 'application/json', ...cors });
            res.end(JSON.stringify({ error: message }));
        };

        req.on('data', chunk => {
            if (tooLarge) return;
            totalLen += chunk.length;
            if (totalLen > UPLOAD_MAX) {
                tooLarge = true;
                failUpload(413, '文件超过 100MB 上限');
                return;
            }
            out.write(chunk);
        });
        req.on('error', e => failUpload(500, e.message));
        out.on('error', e => failUpload(500, e.message));
        req.on('end', () => {
            if (finished || tooLarge) return;
            try {
                out.end(() => {
                    if (finished) return;
                    finished = true;
                    fs.writeFileSync(metaPath, JSON.stringify({ mime, filename }));
                    const relayHost = req.headers.host || `${BIND_ADDR}:${RELAY_PORT}`;
                    const url = `http://${relayHost}/tmp/${safeName}`;
                    log(`📤 /upload: ${filePath} (${totalLen} bytes, ${mime}) → ${url}`);
                    const cleanupMs = mime.startsWith('image/') ? 60_000 : 600_000;
                    setTimeout(() => {
                        try { fs.unlinkSync(filePath); log(`🗑️ 已清理临时文件: ${safeName}`); } catch (_) {}
                        try { fs.unlinkSync(metaPath); } catch (_) {}
                    }, cleanupMs);
                    res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({ ok: true, url, size: totalLen, mime }));
                });
            } catch (e) {
                log(`❌ /upload 异常: ${e.message}`);
                failUpload(500, e.message);
            }
        });
        return;
    }

    // ── /tmp/:filename — 提供上传的临时文件下载 ──
    if (req.url.startsWith('/tmp/') && req.method === 'GET') {
        const safeName = req.url.slice(5).split('?')[0]; // 去掉 /tmp/ 前缀和查询参数
        if (!safeName || safeName.includes('..') || safeName.includes('/')) {
            res.writeHead(400, { 'Content-Type': 'application/json', ...cors });
            res.end(JSON.stringify({ error: '无效文件名' }));
            return;
        }
        const tmpDir = path.join(os.tmpdir(), 'cdp-relay-uploads');
        const filePath = path.join(tmpDir, safeName);
        if (!fs.existsSync(filePath)) {
            res.writeHead(404, { 'Content-Type': 'application/json', ...cors });
            res.end(JSON.stringify({ error: '文件不存在或已过期' }));
            return;
        }
        let mime = 'application/octet-stream';
        try {
            const meta = JSON.parse(fs.readFileSync(`${filePath}.json`, 'utf8'));
            if (meta.mime) mime = String(meta.mime);
        } catch (_) {
            const ext = path.extname(safeName).toLowerCase();
            const mimeMap = {
                '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png',
                '.gif': 'image/gif', '.webp': 'image/webp',
                '.mp4': 'video/mp4', '.mov': 'video/quicktime', '.webm': 'video/webm',
                '.avi': 'video/x-msvideo', '.mkv': 'video/x-matroska',
                '.pdf': 'application/pdf', '.txt': 'text/plain', '.json': 'application/json',
                '.zip': 'application/zip'
            };
            mime = mimeMap[ext] || mime;
        }
        const stat = fs.statSync(filePath);
        res.writeHead(200, { 'Content-Type': mime, 'Content-Length': stat.size, ...cors });
        fs.createReadStream(filePath).pipe(res);
        return;
    }

    // ── /version (OTA)：ota_meta.js 读 build/ota/version.json 或解析 APK，无需手改任何文件 ──
    if (req.url === '/version') {
        log(`📦 /version 请求来自: ${req.socket.remoteAddress}`);
        res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
        const meta = otaMeta.loadOtaMeta(REPO_ROOT);
        res.end(JSON.stringify(meta));
        return;
    }

    // ── /download_apk (OTA)：自动选择最新构建的 release 或 debug APK ──
    if (req.url === '/download_apk') {
        const chosen = otaMeta.findLatestApk(REPO_ROOT);
        if (chosen) {
            const stat = fs.statSync(chosen.path);
            res.writeHead(200, {
                'Content-Type': 'application/vnd.android.package-archive',
                'Content-Length': stat.size,
                'Content-Disposition': 'attachment; filename=' + chosen.fileName,
                ...cors
            });
            fs.createReadStream(chosen.path).pipe(res);
        } else {
            res.writeHead(404, { 'Content-Type': 'text/plain', ...cors });
            res.end('APK not found — run ./gradlew assembleDebug or assembleRelease in repo root.');
        }
        return;
    }

    // ── /kill?port=XXXX (兼容旧接口：默认优雅退出指定 CDP 端口的 IDE；force=1 才强制杀) ──
    if (req.url.startsWith('/kill')) {
        const parsed = urlModule.parse(req.url, true);
        const reqPort = parsed.query.port ? parseInt(parsed.query.port) : 0;
        res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
        if (!reqPort) {
            res.end(JSON.stringify({ success: false, error: '缺少 port 参数' }));
            return;
        }
        try {
            const force = /^(1|true|yes)$/i.test(String(parsed.query.force || ''));
            const result = await gracefulExitCdpTarget(reqPort, { force });
            res.end(JSON.stringify(result));
        } catch (e) {
            res.end(JSON.stringify({ success: false, error: e.message }));
        }
        return;
    }

    // ── Codex 项目管理：显式添加/激活 workspace root ──
    if (req.url.startsWith('/codex/workspace/add')) {
        const parsed = urlModule.parse(req.url, true);
        const reqPort = parsed.query.port ? parseInt(parsed.query.port) : 9666;
        const reqCwd = String(parsed.query.cwd || '').trim();
        const activate = !/^(0|false|no)$/i.test(String(parsed.query.activate || 'true'));
        const shouldReload = !/^(0|false|no)$/i.test(String(parsed.query.reload || 'true'));
        res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
        try {
            const codexWorkspace = ensureCodexWorkspaceRoot(reqCwd, { activate });
            await scanAllCdpPorts();
            let runtimeAddResult = null;
            if (activeCdpTargets.has(reqPort)) {
                try {
                    runtimeAddResult = await addCodexWorkspaceRootToRunningApp(reqPort, codexWorkspace.workspaceRoot);
                    log(`📦 Codex workspace runtime add :${reqPort} → ${runtimeAddResult}`);
                } catch (e) {
                    runtimeAddResult = `error:${e.message}`;
                    log(`⚠️ Codex workspace runtime add 失败 :${reqPort}: ${e.message}`);
                }
                if (runtimeAddResult !== 'ok') {
                    res.end(JSON.stringify({
                        success: false,
                        port: reqPort,
                        appName: 'Codex',
                        codexWorkspace,
                        runtimeAdd: runtimeAddResult,
                        error: `Codex runtime add failed: ${runtimeAddResult || 'unknown'}`
                    }));
                    return;
                }
            }
            let reloadResult = null;
            if (shouldReload && activeCdpTargets.has(reqPort)) {
                reloadResult = await reloadCdpPage(reqPort, { pageMatcher: isCodexMainPage });
                if (!reloadResult?.reloaded) {
                    res.end(JSON.stringify({
                        success: false,
                        port: reqPort,
                        appName: 'Codex',
                        codexWorkspace,
                        runtimeAdd: runtimeAddResult,
                        reload: reloadResult,
                        error: `Codex reload failed: ${reloadResult?.error || 'unknown'}`
                    }));
                    return;
                }
            }
            res.end(JSON.stringify({
                success: true,
                port: reqPort,
                appName: 'Codex',
                codexWorkspace,
                runtimeAdd: runtimeAddResult,
                reload: reloadResult
            }));
        } catch (e) {
            res.end(JSON.stringify({ success: false, port: reqPort, appName: 'Codex', error: e.message }));
        }
        return;
    }

    // ── /launch 支持 ?port=9444&app=Antigravity&cwd=/path/to/project ──
    if (req.url.startsWith('/launch')) {
        const parsed = urlModule.parse(req.url, true);
        const reqPort = parsed.query.port ? parseInt(parsed.query.port) : CDP_PORT;
        const reqApp = String(parsed.query.app || '');
        const reqCwd = String(parsed.query.cwd || '').trim();
        const reqLite = parsed.query.lite === '1' || parsed.query.lite === 'true';
        const isCodexLaunch = reqApp.toLowerCase() === 'codex' || reqPort === 9666;
        let codexWorkspace = null;
        res.writeHead(200, { 'Content-Type': 'application/json', ...cors });

        if (isCodexLaunch && reqCwd) {
            try {
                codexWorkspace = ensureCodexWorkspaceRoot(reqCwd, { activate: true });
            } catch (e) {
                res.end(JSON.stringify({ launched: false, port: reqPort, appName: 'Codex', error: e.message }));
                return;
            }
        }

        // 如果指定了端口且该端口已有实例
        if (activeCdpTargets.has(reqPort)) {
            const t = activeCdpTargets.get(reqPort);
            let reloadResult = null;
            let runtimeAddResult = null;
            if (isCodexLaunch && reqCwd) {
                try {
                    runtimeAddResult = await addCodexWorkspaceRootToRunningApp(reqPort, codexWorkspace.workspaceRoot);
                    log(`📦 /launch: Codex runtime add :${reqPort} → ${runtimeAddResult}`);
                } catch (e) {
                    runtimeAddResult = `error:${e.message}`;
                    log(`⚠️ /launch: Codex runtime add 失败 :${reqPort}: ${e.message}`);
                }
                if (runtimeAddResult !== 'ok') {
                    res.end(JSON.stringify({
                        launched: false,
                        already_running: true,
                        port: reqPort,
                        appName: t.appName,
                        codexWorkspace,
                        runtimeAdd: runtimeAddResult,
                        error: `Codex runtime add failed: ${runtimeAddResult || 'unknown'}`
                    }));
                    return;
                }
                log(`✅ /launch: Codex CDP :${reqPort} 已运行，刷新以激活 workspace: ${codexWorkspace.workspaceRoot}`);
                reloadResult = await reloadCdpPage(reqPort, { pageMatcher: isCodexMainPage });
                if (!reloadResult?.reloaded) {
                    res.end(JSON.stringify({
                        launched: false,
                        already_running: true,
                        port: reqPort,
                        appName: t.appName,
                        codexWorkspace,
                        runtimeAdd: runtimeAddResult,
                        reload: reloadResult,
                        error: `Codex reload failed: ${reloadResult?.error || 'unknown'}`
                    }));
                    return;
                }
            } else if (reqCwd) {
                log(`✅ /launch: CDP :${reqPort} 已运行，尝试打开新窗口目录: ${reqCwd}`);
                openCwdInRunningIde(reqPort, reqApp || t.appName, reqCwd);
            } else {
                log(`✅ /launch: CDP :${reqPort} 已运行 (${t.appName})`);
            }
            res.end(JSON.stringify({
                launched: true,
                already_running: true,
                port: reqPort,
                appName: t.appName,
                codexWorkspace,
                runtimeAdd: runtimeAddResult,
                reload: reloadResult
            }));
            return;
        }

        // 没有指定端口时，如果已有任意实例运行且无具体端口请求，返回已运行
        if (!parsed.query.port && activeCdpTargets.size > 0) {
            log('✅ /launch: 已有 CDP 实例运行');
            res.end(JSON.stringify({ launched: true, already_running: true, targets: activeCdpTargets.size }));
            return;
        }

        log(`🚀 /launch: 启动 ${reqApp || '自动检测'} 在端口 :${reqPort}...`);
        if (reqCwd) addCwdHistory(reqCwd, reqApp);
        const ok = await autoLaunchWithCdp({ force: true, port: reqPort, appName: reqApp, cwd: reqCwd, lite: reqLite });
        if (ok) await scanAllCdpPorts();
        res.end(JSON.stringify({ launched: ok, port: reqPort, codexWorkspace }));
        return;
    }

    // ── CDP HTTP 代理（支持 /cdp/{port}/... 和原始路径） ──
    const { cdpPort, path: cdpPath } = parseCdpPath(req.url);

    // ── 原生 /cdp/{port}/send 接口，用于跨 IDE 自动发送消息 (Orchestra 引擎) ──
    if (cdpPath === '/send' && req.method === 'POST') {
        let body = '';
        req.on('data', chunk => body += chunk);
        req.on('end', async () => {
            try {
                const { message, targetPid } = JSON.parse(body);
                if (!message) throw new Error("缺少 'message' 字段");
                if (!activeCdpTargets.has(cdpPort)) throw new Error(`CDP 端口 ${cdpPort} 不可用`);
                await sendMessageToIde(cdpPort, message, null, targetPid);
                res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
                res.end(JSON.stringify({ success: true, message: 'Message sent to IDE' }));
            } catch (e) {
                const status = e.message?.includes('不可用') || e.message?.includes('缺少') ? 400 : 500;
                res.writeHead(status, { 'Content-Type': 'application/json', ...cors });
                res.end(JSON.stringify({ error: e.message }));
            }
        });
        return;
    }

    // ── V2 架构：基于 Git 双轨制的流水线工作流 ──

    // GET /workflow/status — 查询当前流水线状态
    if (req.url === '/workflow/status' && req.method === 'GET') {
        const pl = currentPipeline;
        // 终态延迟自愈：保留 DONE/ABORT 一段时间供 App 轮询，之后归位 IDLE。
        const prevState = pl.state;
        const prevEnteredAt = pl.state_entered_at;
        if (maybeSelfHealTerminalState(pl)) {
            log(`🔧 /workflow/status: 自愈 ${prevState} → IDLE（已停留 ${Math.round((Date.now() - prevEnteredAt) / 1000)}s）`);
            savePipelineState(pl);
        }
        // P2#12: IDLE 时 elapsed_ms 返回 0
        const elapsed = pl.state === 'IDLE' ? 0 : (Date.now() - pl.state_entered_at);
        res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
        res.end(JSON.stringify({
            pipeline: pl.name,
            state: pl.state,
            elapsed_ms: elapsed,
            warned: pl.warned,
            brain:  { ide: pl.brain.ide,  port: pl.brain.port || getIdePortByName(pl.brain.ide)  },
            worker: { ide: pl.worker.ide, port: pl.worker.port || getIdePortByName(pl.worker.ide) },
            cwd: pl.cwd,
            initialTask: pl.initialTask || '',
            timeouts: pl.timeouts,
            lastError: pl.lastError,
            lastErrorAt: pl.lastErrorAt,
            lastFinishedState: pl.lastFinishedState,
            lastFinishedAt: pl.lastFinishedAt,
            // P1#5: 事件日志
            eventLog: pl.eventLog,
            // 多轮审查进度：App 端可据此显示「已审 N/M 轮」并判断是否可 done
            reviewRound: pl.reviewRound || 0,
            minReviewRounds: pl.minReviewRounds || DEFAULT_MIN_REVIEW_ROUNDS,
            lastReviewVerdict: pl.lastReviewVerdict || null,
        }));
        return;
    }

    // POST /workflow/upload_attachment — 保存附件到 .orchestra/attachments/
    // 新版：query: cwd, filename, mime; body 为原始文件流。旧版 JSON base64 仍保留兼容。
    if (req.url.startsWith('/workflow/upload_attachment') && req.method === 'POST') {
        const uploadUrl = urlModule.parse(req.url, true);
        const queryCwd = String(uploadUrl.query.cwd || '').trim();
        const queryFilename = String(uploadUrl.query.filename || '').trim();
        const queryMime = String(uploadUrl.query.mime || '').trim();
        if (queryCwd && queryFilename) {
            const MAX_UPLOAD_BYTES = 100 * 1024 * 1024;
            const safeName = queryFilename.replace(/[^a-zA-Z0-9._\-]/g, '_') || `attachment_${Date.now()}`;
            const attDir = path.join(queryCwd, '.orchestra', 'attachments');
            const filePath = path.join(attDir, safeName);
            let totalLen = 0;
            let settled = false;
            let out = null;

            const finish = (status, payload) => {
                if (settled) return;
                settled = true;
                try { out?.destroy(); } catch (_) {}
                if (status >= 400) {
                    try { if (fs.existsSync(filePath)) fs.unlinkSync(filePath); } catch (_) {}
                }
                res.writeHead(status, { 'Content-Type': 'application/json', ...cors });
                res.end(JSON.stringify(payload));
            };

            try {
                if (!fs.existsSync(attDir)) fs.mkdirSync(attDir, { recursive: true });
                out = fs.createWriteStream(filePath);
                out.on('error', e => {
                    log(`❌ /workflow/upload_attachment 写入失败: ${e.message}`);
                    finish(500, { error: e.message });
                });
                out.on('drain', () => {
                    if (!settled) req.resume();
                });
                req.on('data', chunk => {
                    if (settled) return;
                    totalLen += chunk.length;
                    if (totalLen > MAX_UPLOAD_BYTES) {
                        log(`⚠️ /workflow/upload_attachment 附件超过 100MB 上限: ${queryFilename}`);
                        finish(413, { error: `附件 ${queryFilename} 超过 100MB 上限` });
                        try { req.resume(); } catch (_) {}
                        return;
                    }
                    if (!out.write(chunk)) req.pause();
                });
                req.on('end', () => {
                    if (settled) return;
                    out.end(() => {
                        if (settled) return;
                        settled = true;
                        log(`📎 附件已保存: ${filePath} (${totalLen} bytes, ${queryMime || 'application/octet-stream'})`);
                        res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
                        res.end(JSON.stringify({ ok: true, path: filePath, safeName, size: totalLen, mime: queryMime }));
                    });
                });
                req.on('error', e => {
                    if (settled && e.code === 'ECONNRESET') return;
                    log(`❌ /workflow/upload_attachment 请求异常: ${e.message}`);
                    finish(500, { error: e.message });
                });
            } catch (e) {
                log(`❌ /workflow/upload_attachment 初始化失败: ${e.message}`);
                finish(500, { error: e.message });
            }
            return;
        }

        const MAX_BODY_LEN = 140 * 1024 * 1024 + 4096; // ~100MB decoded + JSON wrapper
        let body = '';
        let tooLarge = false;
        req.on('data', chunk => {
            if (tooLarge) return;           // 已超限，丢弃后续 chunk
            body += chunk;
            if (body.length > MAX_BODY_LEN) {
                tooLarge = true;
                body = '';                  // 释放内存
            }
        });
        req.on('end', () => {
            if (tooLarge) {
                log('⚠️ /workflow/upload_attachment 请求体超过 100MB 上限');
                res.writeHead(413, { 'Content-Type': 'application/json', ...cors });
                res.end(JSON.stringify({ error: '附件超过 100MB 上限' }));
                return;
            }
            try {
                const { cwd, filename, base64 } = JSON.parse(body);
                if (!cwd || !filename || !base64) {
                    res.writeHead(400, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({ error: '缺少 cwd, filename 或 base64' }));
                    return;
                }
                // 二次校验解码后大小
                const decoded = Buffer.from(base64, 'base64');
                if (decoded.length > 100 * 1024 * 1024) {
                    res.writeHead(413, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({ error: `附件 ${filename} 超过 100MB 上限 (${(decoded.length / 1024 / 1024).toFixed(1)}MB)` }));
                    return;
                }
                const attDir = path.join(cwd, '.orchestra', 'attachments');
                if (!fs.existsSync(attDir)) {
                    fs.mkdirSync(attDir, { recursive: true });
                }
                // 安全过滤文件名
                const safeName = filename.replace(/[^a-zA-Z0-9._\-]/g, '_');
                const filePath = path.join(attDir, safeName);
                fs.writeFileSync(filePath, decoded);
                log(`📎 附件已保存: ${filePath} (${decoded.length} bytes)`);
                res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
                res.end(JSON.stringify({ ok: true, path: filePath, safeName }));
            } catch (e) {
                log(`❌ /workflow/upload_attachment 异常: ${e.message}`);
                res.writeHead(500, { 'Content-Type': 'application/json', ...cors });
                res.end(JSON.stringify({ error: e.message }));
            }
        });
        return;
    }

    // POST /workflow/start — 从前端启动流水线
    // body: {
    //   pipeline: "pair_programming",            // 可选，默认 pair_programming
    //   brain:  { ide: "Antigravity" },          // 必填
    //   worker: { ide: "Cursor" },               // 必填
    //   initial_task: "实现 LoginViewModel",     // 必填
    //   cwd: "/path/to/repo"                     // 必填，Git 仓库路径
    // }
    if (req.url === '/workflow/start' && req.method === 'POST') {
        let body = '';
        req.on('data', chunk => body += chunk);
        req.on('end', () => withWorkflowLock(async () => {
            try {
                const parsed = JSON.parse(body || '{}');
                const plName = parsed.pipeline || 'pair_programming';
                let brainIde  = parsed.brain?.ide?.trim();
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
                }
                const cwd = parsed.cwd?.trim();

                const missing = [];
                if (!brainIde)    missing.push('brain.ide');
                if (!workerIde)   missing.push('worker.ide');
                if (!initialTask) missing.push('initial_task');
                if (!cwd)         missing.push('cwd');
                if (missing.length > 0) {
                    res.writeHead(400, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({ error: `缺少必填字段: ${missing.join(', ')}` }));
                    return;
                }
                // cwd 必须是 Git 仓库
                if (!fs.existsSync(path.join(cwd, '.git'))) {
                    res.writeHead(400, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({ error: `cwd 不是 Git 仓库: ${cwd}` }));
                    return;
                }

                // 两个 IDE 自动就绪：未打开则 launch，已打开则尽力打开同一个 cwd。
                let brainReady;
                let workerReady;
                try {
                    brainReady = await ensureWorkflowIdeReady({
                        ideName: brainIde,
                        requestedPort: parsed.brain?.port ? Number(parsed.brain.port) : null,
                        cwd,
                        roleName: 'Brain',
                    });
                    workerReady = await ensureWorkflowIdeReady({
                        ideName: workerIde,
                        requestedPort: parsed.worker?.port ? Number(parsed.worker.port) : null,
                        cwd,
                        roleName: 'Worker',
                    });
                } catch (e) {
                    res.writeHead(503, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({
                        error: e.message,
                        hint: 'Relay 已尝试自动启动 IDE；请确认应用已安装、端口未被其他应用占用',
                        available: [...activeCdpTargets.entries()].map(([port, t]) => ({ port, appName: t.appName })),
                    }));
                    return;
                }
                const brainPort = brainReady.port;
                const workerPort = workerReady.port;
                if (brainPort === workerPort) {
                    res.writeHead(400, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({ error: 'brain 和 worker 必须是不同的 IDE 实例' }));
                    return;
                }

                // 只有 IDLE 时才允许 start，避免抹掉正在运行的流水线
                if (currentPipeline.state !== 'IDLE') {
                    res.writeHead(409, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({
                        error: `当前流水线处于 ${currentPipeline.state} 状态`,
                        hint: '请先 /workflow/abort 或等待完成',
                        state: currentPipeline.state,
                    }));
                    return;
                }

                let workerModel = null;
                if (workerIde.toLowerCase() === 'cursor') {
                    workerModel = await maybeSwitchCursorComposerModel(workerPort, 'Composer 2');
                }

                // 先执行 orchestra.sh（确保成功后才更新 currentPipeline，避免竞态）
                // 使用 execAsync 避免阻塞事件循环（withWorkflowLock 保证串行安全）
                const scriptPath = ORCHESTRA_SCRIPT;

                // 自动安装 reference-transaction Hook（流水线的核心通信机制）
                const hookSrc = REFERENCE_TRANSACTION_HOOK;
                const targetHooksDir = path.join(cwd, '.git', 'hooks');
                const targetHook = path.join(targetHooksDir, 'reference-transaction');
                try {
                    if (fs.existsSync(hookSrc) && fs.existsSync(path.join(cwd, '.git'))) {
                        fs.mkdirSync(targetHooksDir, { recursive: true });
                        const needsInstall = !fs.existsSync(targetHook)
                            || fs.readFileSync(targetHook, 'utf8') !== fs.readFileSync(hookSrc, 'utf8');
                        if (needsInstall) {
                            if (fs.existsSync(targetHook)) {
                                fs.copyFileSync(targetHook, `${targetHook}.cdp-remote.bak`);
                            }
                            fs.copyFileSync(hookSrc, targetHook);
                            fs.chmodSync(targetHook, 0o755);
                            log(`📎 已安装 reference-transaction Hook → ${targetHook}`);
                        } else {
                            log(`📎 Hook 已存在，跳过安装`);
                        }
                    } else {
                        log(`⚠️ Hook 源或目标 .git 不存在: hookSrc=${hookSrc} cwd=${cwd}`);
                    }
                } catch (e) {
                    log(`⚠️ Hook 安装失败（不影响启动）: ${e.message}`);
                }

                let scriptOut = '';
                try {
                    scriptOut = await execAsync(
                        `${JSON.stringify(scriptPath)} plan ${JSON.stringify(initialTask)}`,
                        { cwd, timeout: 5000 }
                    );
                } catch (e) {
                    log(`❌ /workflow/start: orchestra.sh 失败: ${e.message}`);
                    res.writeHead(500, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({
                        error: 'orchestra.sh 执行失败',
                        detail: e.stderr?.toString() || e.message,
                    }));
                    return;
                }

                // orchestra.sh 成功，安全更新 currentPipeline
                currentPipeline.name   = plName;
                currentPipeline.brain  = { ide: brainIde, port: brainPort, pid: brainPid };
                currentPipeline.worker = { ide: workerIde, port: workerPort, pid: workerPid };
                currentPipeline.cwd    = cwd;
                currentPipeline.warned = false;
                currentPipeline.lastError = null;
                currentPipeline.lastErrorAt = null;
                currentPipeline.eventLog = [];
                // 新流水线：重置多轮审查计数器，默认最少 2 轮
                currentPipeline.reviewRound = 0;
                currentPipeline.minReviewRounds = Number.isInteger(parsed.min_review_rounds)
                    ? Math.max(1, Math.min(parsed.min_review_rounds, MAX_REVIEW_ROUNDS))
                    : DEFAULT_MIN_REVIEW_ROUNDS;
                currentPipeline.lastReviewVerdict = null;
                currentPipeline.initialTask = initialTask; // 保存原始完整需求，REVIEW 时用于判断是否全部完成
                // ★ 重置 watchdog 全局状态（前一轮遗留会导致 BRAIN_PLAN/REVIEW 被跳过）
                _brainReplyProcessed = false;
                _lastReplySnapshot = '';
                _replyStableCount = 0;
                _workerDirtyDetectedAt = 0;
                _workerLastDirtyFiles = '';
                _brainReviewEnteredAt = 0;
                currentPipeline.lastSeenPipelineHash = gitRevParse('refs/orchestra/pipeline', cwd);
                currentPipeline.lastSeenMasterHash = gitRevParse('refs/heads/master', cwd) || gitRevParse('refs/heads/main', cwd);
                currentPipeline.currentTaskHash = null;
                currentPipeline.workerBaselineStatus = gitStatusPorcelain(cwd);
                savePipelineState(currentPipeline); // 持久化初始状态
                pushEvent(currentPipeline, {
                    type: 'start',
                    from: 'IDLE',
                    to: 'IDLE',
                    verb: 'START',
                    summary: `${brainIde} → ${workerIde}`,
                });

                log(`🚀 /workflow/start: ${brainIde} → ${workerIde} @ ${cwd} | TASK="${initialTask.slice(0, 40)}"`);

                res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
                res.end(JSON.stringify({
                    ok: true,
                    pipeline: plName,
                    brain:  { ide: brainIde,  port: brainPort, pid: brainPid  },
                    worker: { ide: workerIde, port: workerPort, pid: workerPid },
                    cwd,
                    auto_launch: {
                        brain: brainReady.launched,
                        worker: workerReady.launched,
                    },
                    worker_model: workerModel,
                    script_output: scriptOut,
                    note: '状态将在 hook 到达后异步推进到 WORKER_CODE，可轮询 /workflow/status 查看',
                }));
            } catch (e) {
                log(`❌ /workflow/start 异常: ${e.message}`);
                if (!res.headersSent) {
                    res.writeHead(500, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({ error: e.message }));
                }
            }
        }));
        return;
    }

    // POST /workflow/abort — 人工中断当前流水线
    // body: { cwd?: "/path/to/repo" } — 可选，缺省用 start 时记录的 cwd
    if (req.url === '/workflow/abort' && req.method === 'POST') {
        let body = '';
        req.on('data', chunk => body += chunk);
        req.on('end', () => withWorkflowLock(async () => {
            try {
                const parsed = body ? JSON.parse(body) : {};
                const cwd = (parsed.cwd || currentPipeline.cwd || '').trim();

                // IDLE 和终态（DONE/ABORT）都视为已完成，无需中断
                if (currentPipeline.state === 'IDLE' || TERMINAL_VERBS.has(currentPipeline.state)) {
                    if (TERMINAL_VERBS.has(currentPipeline.state)) {
                        currentPipeline.lastFinishedState = currentPipeline.state;
                        currentPipeline.lastFinishedAt = currentPipeline.state_entered_at;
                        currentPipeline.state = 'IDLE';
                        currentPipeline.state_entered_at = Date.now();
                        savePipelineState(currentPipeline);
                    }
                    res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({ ok: true, state: 'IDLE', note: '已在 IDLE，无需中断' }));
                    return;
                }

                const prevState = currentPipeline.state;

                // 核心：立即设置 ABORT（不依赖 cwd/Hook/orchestra.sh）
                currentPipeline.state = 'ABORT';
                currentPipeline.state_entered_at = Date.now();
                currentPipeline.warned = false;

                // 尽力而为：往 Git 持久化 ABORT 记录（失败不影响状态）
                if (cwd && fs.existsSync(path.join(cwd, '.git'))) {
                    const scriptPath = ORCHESTRA_SCRIPT;
                    try {
                        await execAsync(
                            `${JSON.stringify(scriptPath)} abort`,
                            { cwd, timeout: 5000 }
                        );
                    } catch (e) {
                        log(`⚠️ /workflow/abort: orchestra.sh 失败（状态已更新）: ${e.message}`);
                    }
                } else {
                    log(`⚠️ /workflow/abort: cwd 无效 (${cwd || 'null'})，跳过 Git 持久化`);
                }

                log(`❌ /workflow/abort: ${prevState} → ABORT`);
                pushEvent(currentPipeline, {
                    type: 'abort',
                    from: prevState,
                    to: 'ABORT',
                    verb: 'ABORT',
                    summary: '用户手动中断',
                });
                savePipelineState(currentPipeline);
                res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
                res.end(JSON.stringify({ ok: true, prev: prevState, state: 'ABORT' }));
            } catch (e) {
                log(`❌ /workflow/abort 异常: ${e.message}`);
                if (!res.headersSent) {
                    res.writeHead(500, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({ error: e.message }));
                }
            }
        }));
        return;
    }

    // POST /workflow/next — Git Hook 触发的状态推进
    if (req.url === '/workflow/next' && req.method === 'POST') {
        // 安全：只接受本机请求。Git Hook 必然来自本机，远程调用一律拒绝，
        // 防止 tailscale / LAN 场景下有人向 IDE 注入任意 prompt。
        const remoteAddr = req.socket.remoteAddress || '';
        const isLocal = remoteAddr === '127.0.0.1'
                     || remoteAddr === '::1'
                     || remoteAddr === '::ffff:127.0.0.1';
        if (!isLocal) {
            log(`🚫 /workflow/next 拒绝非本机请求: ${remoteAddr}`);
            res.writeHead(403, { 'Content-Type': 'application/json', ...cors });
            res.end(JSON.stringify({ error: 'Forbidden: /workflow/next 仅接受本机请求' }));
            return;
        }

        let body = '';
        const MAX_NEXT_BODY = 64 * 1024; // 64KB 上限
        let tooLarge = false;
        req.on('data', chunk => {
            if (tooLarge) return;
            body += chunk;
            if (body.length > MAX_NEXT_BODY) { tooLarge = true; body = ''; }
        });
        req.on('end', () => {
            if (tooLarge) {
                res.writeHead(413, { 'Content-Type': 'application/json', ...cors });
                res.end(JSON.stringify({ error: '请求体超过 64KB 上限' }));
                return;
            }
            // 串行锁：所有状态转移串行执行，避免 await sendMessageToIde 期间并发请求交叉改状态
            withWorkflowLock(async () => {
                try {
                    const { message, ref, hash } = JSON.parse(body);

                    if (!message || !ref || !hash) {
                        res.writeHead(400, { 'Content-Type': 'application/json', ...cors });
                        res.end(JSON.stringify({ error: '缺少必要字段: message, ref, hash' }));
                        return;
                    }

                    const pl = currentPipeline;
                    const prevState = pl.state;
                    const isMainCommit = ref === 'refs/heads/main' || ref === 'refs/heads/master';
                    const isPipelineRef = ref === 'refs/orchestra/pipeline';

                    if (!isMainCommit && !isPipelineRef) {
                        log(`⚠️ /workflow/next: 未知 ref "${ref}"，忽略`);
                        res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
                        res.end(JSON.stringify({ ok: true, ignored: 'unknown-ref' }));
                        return;
                    }

                    // 主分支 commit 必须带 Orchestra-Task trailer（P0.2）
                    if (isMainCommit && !/^Orchestra-Task:\s*\S+/m.test(message)) {
                        log(`ℹ️ /workflow/next: 主分支 commit 无 Orchestra-Task trailer，忽略（${hash.substring(0,7)}）`);
                        res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
                        res.end(JSON.stringify({ ok: true, ignored: 'no-orchestra-trailer' }));
                        return;
                    }

                    // 解析 pipeline 消息的动词（主分支 commit 不参与此解析）
                    let verb = null;
                    let bodyMsg = '';
                    if (isPipelineRef) {
                        const m = message.trim().match(/^(\w+)(?::\s*(.*))?$/s);
                        verb = m ? m[1].toUpperCase() : null;
                        bodyMsg = m ? (m[2] || '').trim() : '';
                    }

                    log(`🔄 /workflow/next [${ref}] verb=${verb} state=${prevState}`);

                    // 状态机 guard：拒绝非法转移（P1.6）
                    if (!isTransitionAllowed(prevState, isMainCommit, verb)) {
                        log(`⚠️ /workflow/next: 非法转移 state=${prevState} ref=${ref} verb=${verb}，忽略`);
                        res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
                        res.end(JSON.stringify({
                            ok: true, ignored: 'illegal-transition',
                            state: prevState, verb, ref,
                        }));
                        return;
                    }

                    // 在发送前确认目标 IDE 在线（P1.7）
                    // 优先用 start 时存储的端口，否则按名字查找
                    const requireIde = (role) => {
                        const port = (role.port && activeCdpTargets.has(role.port))
                            ? role.port : getIdePortByName(role.ide);
                        if (!port) {
                            log(`❌ /workflow/next: 未发现 ${role.ide} 实例，请先启动 IDE`);
                            res.writeHead(503, { 'Content-Type': 'application/json', ...cors });
                            res.end(JSON.stringify({
                                error: `${role.ide} 未在线`,
                                hint: '请启动 IDE 或访问 /launch 自动启动',
                                available: [...activeCdpTargets.values()].map(t => t.appName),
                            }));
                            return null;
                        }
                        return port;
                    };

                    // P0#1 + P1#8: 封装带重试的消息发送（1 次重试，间隔 2 秒）
                    const sendWithRetry = async (port, prompt, roleName) => {
                        for (let attempt = 1; attempt <= 2; attempt++) {
                            try {
                                await sendMessageToIde(port, prompt, null, isBrainAction ? pl.brain.pid : pl.worker.pid);
                                // 成功：清除上一次错误
                                pl.lastError = null;
                                pl.lastErrorAt = null;
                                return;
                            } catch (e) {
                                log(`❌ sendMessageToIde 失败 (${roleName}:${port}) 第${attempt}次: ${e.message}`);
                                if (attempt < 2) {
                                    log(`⏳ 2 秒后重试...`);
                                    await new Promise(r => setTimeout(r, 2000));
                                } else {
                                    // 2 次都失败：记录错误，不转移状态
                                    pl.lastError = `消息发送失败 (${roleName}): ${e.message}`;
                                    pl.lastErrorAt = Date.now();
                                    res.writeHead(502, { 'Content-Type': 'application/json', ...cors });
                                    res.end(JSON.stringify({
                                        error: pl.lastError,
                                        state: pl.state,
                                        hint: `${roleName} IDE (port ${port}) 无法接收消息，请检查 IDE 是否活跃`,
                                    }));
                                }
                            }
                        }
                    };

                    // 执行动作（状态转移之前发消息，任何发送失败都不改状态）
                    if (isMainCommit) {
                        // 工人刚提交代码，马上进入新一轮审查
                        pl.reviewRound = (pl.reviewRound || 0) + 1;

                        // 硬上限保护：审查轮次超过 MAX_REVIEW_ROUNDS 还没 DONE，自动中止
                        // 避免 brain/worker 陷入「互相返工」死循环消耗算力
                        if (pl.reviewRound > MAX_REVIEW_ROUNDS) {
                            const reason = `审查轮次 ${pl.reviewRound} 超过上限 ${MAX_REVIEW_ROUNDS}`;
                            pl.lastError = reason;
                            pl.lastErrorAt = Date.now();
                            pl.state = 'ABORT';
                            pl.state_entered_at = Date.now();
                            pl.warned = true;
                            pushEvent(pl, {
                                type: 'abort',
                                from: prevState,
                                to: 'ABORT',
                                verb: 'MAX_ROUNDS',
                                summary: reason,
                            });
                            savePipelineState(pl);
                            log(`⛔ ${reason}，自动 ABORT`);
                            res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
                            res.end(JSON.stringify({ ok: true, state: 'ABORT', reason: 'max_review_rounds' }));
                            return;
                        }

                        const port = requireIde(pl.brain);
                        if (!port) return;
                        const firstLine = message.split('\n')[0];
                        const round = pl.reviewRound;
                        const minRounds = pl.minReviewRounds || DEFAULT_MIN_REVIEW_ROUNDS;
                        const prompt = [
                            `工人提交了代码：「${firstLine}」`,
                            ``,
                            `━━ 原始完整需求 ━━`,
                            `${pl.initialTask || '（未记录）'}`,
                            ``,
                            `━━ 第 ${round} 轮审查（共需至少 ${minRounds} 轮）━━`,
                            ``,
                            `第一步：git show ${hash}`,
                            ...(round > 1 ? [
                                `第二步：git log --grep='Orchestra-Rework-Round' --format='%h %s' -n 20`,
                                `  → 先核对上一轮 ISSUES 是否真的被修掉（没修掉直接标 blocker）`,
                                `  → 再找新的、更深层的问题，不要只复述上轮`,
                                `  → 如实在挑不出新问题，明确写「本轮无新增实质问题」`,
                            ] : []),
                            ``,
                            `审查维度（逐项写结论，不要跳项）：`,
                            `1. 功能正确性：是否真的满足任务需求`,
                            `2. 边界条件：空值 / 超大输入 / 并发 / 异常路径`,
                            `3. 错误处理：失败路径是否覆盖、是否静默吞错`,
                            `4. 结构与可维护性：命名、重复、职责、可读性`,
                            `5. 回归风险：是否影响既有功能、是否缺测试`,
                            ``,
                            `输出必须严格按此结构：`,
                            `---`,
                            `REVIEW_ROUND: ${round}`,
                            `VERDICT: NEEDS_REWORK | PASS`,
                            `NEXT_ACTION: REVIEW | TASK | DONE`,
                            `ISSUES:`,
                            `- [blocker|major|minor] <问题> / <为什么> / <建议改法>`,
                            `（没有就写 NONE）`,
                            `SUMMARY: <一句话结论>`,
                            `---NEXT_TASK_START---`,
                            `仅当 NEXT_ACTION 是 TASK 时，在这里写下一个子任务；否则留空`,
                            `---NEXT_TASK_END---`,
                            `---`,
                            ``,
                            `判定规则：`,
                            `- 任一 blocker 或 major → VERDICT 必须是 NEEDS_REWORK，NEXT_ACTION 必须是 REVIEW`,
                            `- 当前提交通过但原始需求仍有未实现部分 → VERDICT: PASS，NEXT_ACTION: TASK，并填写 NEXT_TASK`,
                            `- 当前提交通过且原始需求已全部完成 → VERDICT: PASS，NEXT_ACTION: DONE`,
                            ``,
                            `⚠️ 核心规则（非常重要）：`,
                            `你只需要输出上述结构化结果，Relay 会自动读取并推进流水线。`,
                            `不要执行 scripts/orchestra.sh，也不要执行任何推进流水线的 shell 命令。`,
                            `⚠️ 在判断"全部完成"前，请用 ls -R 或 find 确认文件确实存在。`,
                            `⚠️ DONE 意味着整个需求 100% 完成，不是当前子任务完成。`,
                        ].join('\n');
                        await sendWithRetry(port, prompt, 'brain');
                        if (pl.lastError) return;
                    } else if (verb === 'PLAN') {
                        const port = requireIde(pl.brain);
                        if (!port) return;
                        const prompt = [

                            `收到新需求：${bodyMsg}`,
                            ``,
                            `你是"大脑"角色，负责需求拆解与任务派发。`,
                            ``,
                            `请完成以下步骤：`,
                            `1. 分析需求，识别风险和约定（平台、框架、技术栈等）`,
                            `2. 将需求拆分为若干子任务，只关注第一步（最小可交付单元）`,
                            `3. 分析完成后，只输出下面的任务块：`,
                            ``,
                            `---TASK_START---`,
                            `<第一步的完整任务描述，包含目标、技术要求、完成标准>`,
                            `---TASK_END---`,
                            ``,
                            `⚠️ 重要规则：`,
                            `- 不要执行 scripts/orchestra.sh，也不要执行任何推进流水线的 shell 命令`,
                            `- 不要自己写代码，你的职责是统筹和拆解`,
                            `- Relay 会自动读取 TASK 块并派发给工人`,
                            `- 任务描述要完整，包含：目标、技术要求、完成标准`,
                        ].join('\n');
                        await sendWithRetry(port, prompt, 'brain');
                    } else if (verb === 'TASK' || verb === 'REVIEW') {
                        const port = requireIde(pl.worker);
                        if (!port) return;
                        pl.currentTaskHash = hash;
                        pl.workerBaselineStatus = gitStatusPorcelain(pl.cwd);
                        // P1 修复：新子任务(TASK)重置 reviewRound；返工(REVIEW)才累加
                        if (verb === 'TASK') {
                            log(`🔄 新子任务到来，reviewRound 重置: ${pl.reviewRound} → 0`);
                            pl.reviewRound = 0;
                        }
                        // 返工提交后会触发下一轮审查；把目标轮号告诉 worker 让它写进 commit
                        const nextRound = (pl.reviewRound || 0) + 1;
                        const isRework = verb === 'REVIEW';
                        // REVIEW = 大脑判定 NEEDS_REWORK；TASK = 新/后续子任务
                        if (isRework) pl.lastReviewVerdict = 'NEEDS_REWORK';
                        const prefix = isRework
                            ? `大脑第 ${pl.reviewRound} 轮审查结论：NEEDS_REWORK。返工要求：`
                            : `大脑派发任务：`;
                        const prompt = [
                            `${prefix}${bodyMsg}`,
                            ``,
                            `执行规则：`,
                            `1. 只修/实现审查/任务中明确要求的内容，不要顺手重构不相关代码。`,
                            isRework
                                ? `2. blocker / major 必须全部修掉；minor 建议一起修。`
                                : `2. 把功能做完，但保持最小影响面。`,
                            `3. 提交后会自动进入第 ${nextRound} 轮审查，请按维度修到位再提交。`,
                            ``,
                            `Commit message 必须包含：`,
                            `  一句话总结`,
                            ...(isRework ? [`  Orchestra-Rework-Round: ${nextRound}`] : []),
                            `  Orchestra-Task: ${hash.substring(0,7)}`,
                            `  Orchestra-Pipeline: ${pl.name}`,
                            ``,
                            ...(isRework ? [
                                `并按原审查 ISSUES 的顺序逐条回应：`,
                                `  [issue-N] 如何修的 / 为什么不修（不允许沉默跳过）。`,
                                ``,
                            ] : []),
                            `不要自己决定流水线是否结束，由大脑做终审。`,
                        ].join('\n');
                        await sendWithRetry(port, prompt, 'worker');
                        if (pl.lastError) return;
                    } else if (verb === 'FAIL') {
                        const port = requireIde(pl.brain);
                        if (!port) return;
                        const prompt = [
                            `工人无法完成任务，原因：${bodyMsg}`,
                            ``,
                            `请决策：`,
                            `  继续尝试 → orchestra task "简化后的任务/换一种方式"`,
                            `  放弃此任务 → orchestra abort`,
                        ].join('\n');
                        await sendWithRetry(port, prompt, 'brain');
                        if (pl.lastError) return;
                    } else if (verb === 'DONE') {
                        // 质量门：轮次不够不允许 DONE
                        const round = pl.reviewRound || 0;
                        const minRounds = pl.minReviewRounds || DEFAULT_MIN_REVIEW_ROUNDS;
                        if (round < minRounds) {
                            log(`🚧 DONE 被质量门拦下: round=${round} < min=${minRounds}`);
                            // 给 brain 发一条明确的「轮次不足」提示，状态保持 BRAIN_REVIEW
                            const port = requireIde(pl.brain);
                            if (port) {
                                const rejectPrompt = [
                                    `⚠️ 你执行了 orchestra done，但当前仅完成 ${round} 轮审查，流水线要求至少 ${minRounds} 轮。`,
                                    `此 DONE 不生效，流水线仍停留在 BRAIN_REVIEW。`,
                                    ``,
                                    `请立即对当前代码进入第 ${round + 1} 轮审查（不需要工人重新提交）。`,
                                    `重点寻找前 ${round} 轮未覆盖的深层问题：`,
                                    `- 性能 / 并发 / 资源泄露`,
                                    `- 异常路径 / 边界条件 / 回归风险`,
                                    `- 安全 / 输入校验 / 数据一致性`,
                                    ``,
                                    `⚠️ 你必须按以下结构输出审查结论（否则流水线无法推进）：`,
                                    `---`,
                                    `REVIEW_ROUND: ${round + 1}`,
                                    `VERDICT: NEEDS_REWORK | PASS`,
                                    `ISSUES:`,
                                    `- [blocker|major|minor] <问题> / <原因> / <建议>`,
                                    `（没有就写 NONE）`,
                                    `SUMMARY: <一句话结论>`,
                                    `---`,
                                    ``,
                                    `输出 VERDICT 后：`,
                                    `请直接提交回复，Relay 系统会自动读取你的判定结果并推进状态，绝对不要执行任何 shell 命令。`,
                                ].join('\n');
                                try {
                                    await sendMessageToIde(port, rejectPrompt, null, isBrainAction ? pl.brain.pid : pl.worker.pid);
                                } catch (e) {
                                    log(`⚠️ 发送「DONE 被拦」提示失败: ${e.message}`);
                                }
                            }
                            pushEvent(pl, {
                                type: 'done_rejected',
                                from: prevState,
                                to: prevState,
                                verb: 'DONE',
                                summary: `DONE 被质量门拦下（${round}/${minRounds}）`,
                            });
                            // 修复死锁：DONE 被拒后重置 Watchdog 追踪状态，
                            // 让 Watchdog 等待 Brain 对 rejectPrompt 生成新的 VERDICT 回复
                            _brainReplyProcessed = false;
                            _brainReviewEnteredAt = Date.now();
                            savePipelineState(pl);
                            res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
                            res.end(JSON.stringify({
                                ok: true,
                                ignored: 'min-rounds-not-met',
                                reviewRound: round,
                                minReviewRounds: minRounds,
                                state: prevState,
                            }));
                            return;
                        }
                        // 通过门槛：记录 PASS，允许进入 DONE
                        pl.lastReviewVerdict = 'PASS';
                        log(`✅ 流水线已完成（审查 ${round}/${minRounds} 轮）`);
                    } else if (verb === 'ABORT') {
                        log('❌ 流水线已中断');
                    }

                    // 转移状态
                    pl.state = nextWorkflowState(prevState, isMainCommit, verb);
                    pl.state_entered_at = Date.now();
                    pl.warned = false;
                    // P0 增强：DONE→WORKER_CODE 回退（Brain 追发 TASK）打明确日志
                    if (prevState === 'DONE' && pl.state === 'WORKER_CODE') {
                        log(`🔄 DONE→WORKER_CODE 回退成功：Brain 在 DONE 保留窗口内追发了 TASK，pipeline 上下文完整保留`);
                    }
                    if (isMainCommit) pl.lastSeenMasterHash = hash;
                    else pl.lastSeenPipelineHash = hash;
                    // 进入 BRAIN_REVIEW 时重置 watchdog 大脑回复追踪
                    if (pl.state === 'BRAIN_REVIEW') {
                        _brainReplyProcessed = false;
                        _brainReviewEnteredAt = Date.now();
                    }
                    // P1#5: 记录事件日志
                    pushEvent(pl, {
                        type: isMainCommit ? 'commit' : 'pipeline',
                        from: prevState,
                        to: pl.state,
                        verb: isMainCommit ? 'COMMIT' : verb,
                        hash: hash?.substring(0, 7),
                        summary: isMainCommit ? message.split('\n')[0].substring(0, 80) : (bodyMsg || '').substring(0, 80),
                    });
                    log(`🔄 状态转移: ${prevState} → ${pl.state}`);
                    savePipelineState(pl);
                    res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({ ok: true, state: pl.state, prev: prevState }));
                } catch (e) {
                    log(`❌ /workflow/next 异常: ${e.message}`);
                    if (!res.headersSent) {
                        res.writeHead(500, { 'Content-Type': 'application/json', ...cors });
                        res.end(JSON.stringify({ error: e.message }));
                    }
                }
            });
        });
        return;
    }

    if (activeCdpTargets.size === 0) {
        res.writeHead(503, { 'Content-Type': 'application/json', ...cors });
        res.end(JSON.stringify({ error: 'CDP 未就绪', hint: '请在电脑上启动 IDE，或访问 /launch 自动启动' }));
        return;
    }

    if (!activeCdpTargets.has(cdpPort)) {
        res.writeHead(404, { 'Content-Type': 'application/json', ...cors });
        res.end(JSON.stringify({ error: `CDP 端口 ${cdpPort} 未发现实例`, available: [...activeCdpTargets.keys()] }));
        return;
    }

    const proxyReq = http.request({
        hostname: CDP_HOST, port: cdpPort, path: cdpPath, method: req.method, headers: req.headers
    }, (proxyRes) => {
        let data = '';
        proxyRes.on('data', chunk => data += chunk);
        proxyRes.on('end', () => {
            const relayHost = req.headers.host || `${BIND_ADDR}:${RELAY_PORT}`;
            const rewritten = rewriteCdpUrls(data, cdpPort, relayHost);
            res.writeHead(proxyRes.statusCode, { ...proxyRes.headers, ...cors });
            res.end(rewritten);
        });
    });
    proxyReq.on('error', (e) => {
        res.writeHead(502, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'CDP 连接失败', message: e.message }));
    });
    proxyReq.end();
  } catch (handlerErr) {
    // 顶层 try-catch：防止单个请求的异常搞崩整个 HTTP server
    log(`💥 [HANDLER GUARD] 请求处理异常: ${handlerErr.message}`);
    try { if (!res.headersSent) { res.writeHead(500); res.end(JSON.stringify({ error: handlerErr.message })); } } catch (_) {}
  }
});

// 注意：上面的 createServer handler 中所有路由最终都走进了
// CDP proxy 分支（以 /cdp/ 开头），所以不需要额外的 fallback。
// 不以 /cdp/ 开头且未匹配任何已知路由的请求会到达这里之前被 return 拦截。

// ─── WebSocket 代理（支持路径路由） ───

const wss = new WebSocketServer({ server });
let startupDone = false;
let listenRetryTimer = null;
let listenRetryCount = 0;

function retryRelayListen(reason) {
    if (listenRetryTimer) return;
    const delayMs = Math.min(1000 + listenRetryCount * 500, 5000);
    listenRetryCount++;
    log(`⚠️ Relay 监听失败: ${reason}，${delayMs}ms 后重试`);
    listenRetryTimer = setTimeout(() => {
        listenRetryTimer = null;
        try {
            server.listen(RELAY_PORT, BIND_ADDR);
        } catch (e) {
            retryRelayListen(e.message);
        }
    }, delayMs);
    if (typeof listenRetryTimer.unref === 'function') listenRetryTimer.unref();
}

wss.on('error', e => {
    if (e.code === 'EADDRINUSE') {
        retryRelayListen(`端口 ${RELAY_PORT} 被占用`);
    } else {
        log(`❌ WebSocket 服务器错误: ${e.message}`);
    }
});

wss.on('connection', (clientWs, req) => {
    const id = ++connectionCount;
    const { cdpPort, path: cdpPath } = parseCdpPath(req.url);
    const targetUrl = `ws://${CDP_HOST}:${cdpPort}${cdpPath}`;
    log(`🔗 [${id}] 新连接 → CDP:${cdpPort} ${cdpPath}`);

    if (!activeCdpTargets.has(cdpPort)) {
        log(`❌ [${id}] CDP 端口 ${cdpPort} 不可用`);
        clientWs.close(1011, `CDP port ${cdpPort} not available`);
        return;
    }

    const targetWs = new WebSocket(targetUrl);
    let clientAlive = true, targetAlive = false;
    const pendingMessages = [];
    let lastWake = 0;

    clientWs.on('message', (d, isBinary) => {
        const msg = isBinary ? d : d.toString();

        // 自动解除 macOS 休眠/最小化状态以允许截图
        if (!isBinary && msg.includes('"Page.captureScreenshot"')) {
            const now = Date.now();
            if (now - lastWake > 5000) { // 每5秒最多唤醒一次
                lastWake = now;
                const t = activeCdpTargets.get(cdpPort);
                if (t && t.appName) {
                    try {
                        require('child_process').exec(`osascript -e 'tell application "${t.appName}" to activate'`);
                    } catch (e) { }
                }
            }
        }

        if (targetAlive) {
            try { targetWs.send(msg); } catch (e) { log(`❌ [${id}] 转发到 CDP 失败: ${e.message}`); }
        } else {
            pendingMessages.push(msg);
        }
    });

    targetWs.on('open', () => {
        targetAlive = true;
        log(`✅ [${id}] CDP:${cdpPort} WS 已连接，刷新 ${pendingMessages.length} 条缓存`);
        while (pendingMessages.length > 0) {
            try { targetWs.send(pendingMessages.shift()); } catch (e) { break; }
        }
        targetWs.on('message', (d, isBinary) => {
            if (clientAlive) try { clientWs.send(isBinary ? d : d.toString()); } catch (e) { }
        });
    });

    const safeCode = (c) => (!c || c === 1005 || c === 1006) ? 1000 : c;
    const pingTimer = setInterval(() => {
        if (clientAlive) try { clientWs.ping(); } catch (e) { }
        if (targetAlive) try { targetWs.ping(); } catch (e) { }
    }, 30000);

    targetWs.on('error', e => { log(`❌ [${id}] CDP WS 错误: ${e.message}`); clearInterval(pingTimer); if (clientAlive) { clientAlive = false; try { clientWs.close(1011, 'CDP error'); } catch (e) { } } });
    targetWs.on('close', (c, r) => { targetAlive = false; clearInterval(pingTimer); log(`🔌 [${id}] CDP WS 关闭: ${c}`); if (clientAlive) { clientAlive = false; try { clientWs.close(safeCode(c), r?.toString() || ''); } catch (e) { } } });
    clientWs.on('close', (c, r) => { clientAlive = false; clearInterval(pingTimer); log(`🔌 [${id}] 客户端 WS 关闭: ${c}`); if (targetAlive) { targetAlive = false; try { targetWs.close(safeCode(c), r?.toString() || ''); } catch (e) { } } });
    clientWs.on('error', e => { log(`❌ [${id}] 客户端 WS 错误: ${e.message}`); clearInterval(pingTimer); if (targetAlive) { targetAlive = false; try { targetWs.close(1011); } catch (e) { } } });
});

// ─── 监控循环 ───

function restartBackoffMs() {
    return consecutiveLaunchFailures >= RESTART_FAIL_THRESHOLD ? RESTART_BACKOFF_LONG_MS : RESTART_BACKOFF_SHORT_MS;
}

async function monitorLoop() {
    while (true) {
        const count = await scanAllCdpPorts();
        if (count > 0) {
            consecutiveLaunchFailures = 0;
            nextRestartAllowedAt = 0;
        } else if (AUTO_LAUNCH) {
            const now = Date.now();
            if (now < nextRestartAllowedAt) { await sleep(CHECK_INTERVAL); continue; }
            log('📡 无 CDP 实例，尝试自动启动...');
            const ok = await autoLaunchWithCdp();
            if (ok) {
                consecutiveLaunchFailures = 0;
                nextRestartAllowedAt = 0;
                await scanAllCdpPorts();
            } else {
                consecutiveLaunchFailures++;
                const wait = restartBackoffMs();
                nextRestartAllowedAt = Date.now() + wait;
                log(`⚠️ 自动启动未就绪，${Math.round(wait / 1000)}s 内不再重试（连续失败 ${consecutiveLaunchFailures} 次）`);
            }
        }
        await sleep(CHECK_INTERVAL);
    }
}

// ─── 启动 ───

function log(msg) {
    const ts = new Date().toLocaleTimeString('zh-CN', { hour12: false });
    console.log(`[${ts}] ${msg}`);
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

server.on('listening', async () => {
    if (startupDone) return;
    startupDone = true;
    listenRetryCount = 0;
    log('╔═══════════════════════════════════════════╗');
    log('║   CDP Relay Server (多实例版 v2.0)        ║');
    log('╚═══════════════════════════════════════════╝');
    log(`📡 Relay: ${BIND_ADDR}:${RELAY_PORT}`);
    log(`🔍 扫描范围: CDP 端口 ${CDP_PORT_MIN}-${CDP_PORT_MAX}`);

    // 首次扫描
    const count = await scanAllCdpPorts();
    if (count > 0) {
        log(`✅ 发现 ${count} 个 CDP 实例:`);
        for (const [port, t] of activeCdpTargets) {
            log(`   ${t.appEmoji} ${t.appName} → :${port} (${t.pages.length} 个页面)`);
        }
    } else if (AUTO_LAUNCH) {
        log('⏳ 未发现 CDP 实例，尝试自动启动...');
        await autoLaunchWithCdp();
        await scanAllCdpPorts();
    } else {
        log(`⏳ 未发现 CDP 实例；用 /launch 手动触发或设 RELAY_AUTO_LAUNCH=1`);
    }


    monitorLoop();
});

server.on('error', e => {
    if (e.code === 'EADDRINUSE') {
        retryRelayListen(`端口 ${RELAY_PORT} 被占用`);
    } else {
        log(`❌ 服务器错误: ${e.message}`);
        // 对于其他严重错误，仍然退出
        process.exit(1);
    }
});

server.listen(RELAY_PORT, BIND_ADDR);

// 辅助函数：查找现有的relay进程
function findExistingRelayProcess() {
    try {
        const { execSync } = require('child_process');
        // 尝试查找正在运行的node进程中的cdp_relay.js
        const output = execSync('ps aux | grep cdp_relay.js | grep -v grep', { encoding: 'utf8' });
        const lines = output.trim().split('\n');
        for (const line of lines) {
            const pid = parseInt(line.split(/\s+/)[1]);
            if (pid && pid !== process.pid) {
                return pid;
            }
        }
    } catch (e) {
        // 如果没有找到进程，返回null
    }
    return null;
}

process.on('SIGINT', () => { log('🛑 关闭中...'); wss.close(); server.close(); process.exit(0); });
process.on('SIGTERM', () => { log('🛑 关闭中...'); wss.close(); server.close(); process.exit(0); });

// ─── 自愈 Watchdog：检测 HTTP server 是否真正在监听 ───
// 根因：node --watch 下子进程崩溃后父进程不退出，launchd 以为服务还活着。
// 解决：每 30s 自检一次，连续 3 次失败则 process.exit(1) 让 launchd 重启。
let _selfCheckFails = 0;
const SELF_CHECK_INTERVAL = 30_000;
const SELF_CHECK_MAX_FAILS = 3;

setInterval(() => {
    const checkReq = http.get(`http://127.0.0.1:${RELAY_PORT}/health`, { timeout: 5000 }, (res) => {
        let body = '';
        res.on('data', c => body += c);
        res.on('end', () => {
            try {
                const j = JSON.parse(body);
                if (j.status === 'ok') {
                    _selfCheckFails = 0;
                    return;
                }
            } catch (_) {}
            _selfCheckFails++;
            log(`⚠️ [SELF-CHECK] 健康检查响应异常 (${_selfCheckFails}/${SELF_CHECK_MAX_FAILS})`);
            if (_selfCheckFails >= SELF_CHECK_MAX_FAILS) {
                log(`💀 [SELF-CHECK] 连续 ${SELF_CHECK_MAX_FAILS} 次健康检查失败，强制退出让 launchd 重启`);
                process.exit(1);
            }
        });
    });
    checkReq.on('error', () => {
        _selfCheckFails++;
        log(`⚠️ [SELF-CHECK] 无法连接自身 :${RELAY_PORT} (${_selfCheckFails}/${SELF_CHECK_MAX_FAILS})`);
        if (_selfCheckFails >= SELF_CHECK_MAX_FAILS) {
            log(`💀 [SELF-CHECK] 连续 ${SELF_CHECK_MAX_FAILS} 次健康检查失败，强制退出让 launchd 重启`);
            process.exit(1);
        }
    });
    checkReq.on('timeout', () => {
        checkReq.destroy();
        _selfCheckFails++;
        log(`⚠️ [SELF-CHECK] 健康检查超时 (${_selfCheckFails}/${SELF_CHECK_MAX_FAILS})`);
        if (_selfCheckFails >= SELF_CHECK_MAX_FAILS) {
            log(`💀 [SELF-CHECK] 连续 ${SELF_CHECK_MAX_FAILS} 次健康检查失败，强制退出让 launchd 重启`);
            process.exit(1);
        }
    });
}, SELF_CHECK_INTERVAL);

// ═══════════════════════════════════════════════════════════════
// ── 公共 CDP 消息发送工具 ──────────────────────────────────────
// ═══════════════════════════════════════════════════════════════

const CDP_TIMEOUT_MS = 8000;

/** 查找输入框并聚焦的 JS 表达式（各 IDE 通用） */
const FOCUS_INPUT_EXPR = `(function(){var ed=null;var ch=document.getElementById('chat');if(ch)ed=ch.querySelector('[contenteditable="true"]');if(!ed){var p=document.getElementById('windsurf.cascadePanel');if(p)ed=p.querySelector('[contenteditable="true"]');}if(!ed){var ces=document.querySelectorAll('div[contenteditable="true"]');for(var i=0;i<ces.length;i++){var e=ces[i];if(!e.offsetParent)continue;var c=e.className||'';var ro=e.getAttribute('role')||'';if(ro==='textbox'||c.indexOf('min-h-')>=0||c.indexOf('outline-none')>=0||c.indexOf('ProseMirror')>=0){ed=e;break;}}}if(!ed)return'no-input';ed.focus();ed.textContent='';return'ok';})()`;

function windsurfSendExpression(message) {
    return `
        (async function() {
            const text = ${JSON.stringify(message)};
            function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
            function findEditor() {
                var el = document.querySelector('[data-lexical-editor="true"]');
                if (el && el.offsetParent) return el;
                var panel = document.getElementById('windsurf.cascadePanel') || document;
                var editors = panel.querySelectorAll('[contenteditable="true"]');
                for (var i = 0; i < editors.length; i++) {
                    if (editors[i].offsetParent) return editors[i];
                }
                return null;
            }
            function findOnEnter(el) {
                var fiberKey = Object.keys(el).find(function(k) { return k.indexOf('__reactFiber') === 0; });
                if (!fiberKey) return null;
                var current = el[fiberKey];
                for (var j = 0; j < 40 && current; j++) {
                    if (current.memoizedProps && typeof current.memoizedProps.onEnter === 'function') {
                        return current.memoizedProps.onEnter;
                    }
                    current = current.return;
                }
                return null;
            }
            var el = findEditor();
            if (!el) return 'no-lexical';

            var onEnter = findOnEnter(el);
            if (onEnter) {
                try {
                    var probe = new KeyboardEvent('keydown', { key: 'Enter', altKey: true, shiftKey: false, bubbles: true, cancelable: true });
                    if (onEnter(probe) === false) return 'generating';
                } catch (_) {}
            }

            el.focus();
            var range = document.createRange();
            range.selectNodeContents(el);
            var sel = window.getSelection();
            sel.removeAllRanges();
            sel.addRange(range);
            document.execCommand('delete');
            await sleep(50);

            var inputEvent = new InputEvent('beforeinput', {
                inputType: 'insertText',
                data: text,
                bubbles: true,
                cancelable: true
            });
            el.dispatchEvent(inputEvent);
            document.execCommand('insertText', false, text);
            el.dispatchEvent(new InputEvent('input', {
                inputType: 'insertText',
                data: text,
                bubbles: true
            }));
            await sleep(150);

            onEnter = findOnEnter(el);
            if (onEnter) {
                var fakeEvent = new KeyboardEvent('keydown', {
                    key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
                    shiftKey: false, altKey: false, ctrlKey: false, metaKey: false,
                    bubbles: true, cancelable: true
                });
                try {
                    Object.defineProperty(fakeEvent, 'preventDefault', { value: function() {} });
                    Object.defineProperty(fakeEvent, 'stopPropagation', { value: function() {} });
                } catch (_) {}
                var ret = onEnter(fakeEvent);
                return ret ? 'sent' : 'rejected';
            }

            var btns = Array.from(document.querySelectorAll('button,[role="button"]')).filter(function(btn) {
                if (!btn.offsetParent || btn.disabled || btn.getAttribute('aria-disabled') === 'true') return false;
                var label = ((btn.getAttribute('aria-label') || '') + ' ' + (btn.title || '') + ' ' + (btn.textContent || '')).toLowerCase();
                return label.includes('send') || label.includes('submit') || label.includes('发送');
            });
            if (btns.length) {
                btns[btns.length - 1].click();
                return 'clicked-send';
            }
            return 'no-onEnter';
        })()
    `;
}

const WINDSURF_EDITOR_INFO_EXPR = `
    (function() {
        function findEditor() {
            var el = document.querySelector('[data-lexical-editor="true"]');
            if (el && el.offsetParent) return el;
            var panel = document.getElementById('windsurf.cascadePanel') || document;
            var editors = panel.querySelectorAll('[contenteditable="true"]');
            for (var i = 0; i < editors.length; i++) {
                if (editors[i].offsetParent) return editors[i];
            }
            return null;
        }
        function findOnEnter(el) {
            var fiberKey = Object.keys(el).find(function(k) { return k.indexOf('__reactFiber') === 0; });
            if (!fiberKey) return null;
            var current = el[fiberKey];
            for (var j = 0; j < 40 && current; j++) {
                if (current.memoizedProps && typeof current.memoizedProps.onEnter === 'function') {
                    return current.memoizedProps.onEnter;
                }
                current = current.return;
            }
            return null;
        }
        var el = findEditor();
        if (!el) return JSON.stringify({ ok: false, error: 'no-lexical' });
        var onEnter = findOnEnter(el);
        if (onEnter) {
            try {
                var probe = new KeyboardEvent('keydown', { key: 'Enter', altKey: true, shiftKey: false, bubbles: true, cancelable: true });
                if (onEnter(probe) === false) return JSON.stringify({ ok: false, error: 'generating' });
            } catch (_) {}
        }
        var r = el.getBoundingClientRect();
        el.focus();
        return JSON.stringify({ ok: true, x: Math.round(r.x + 10), y: Math.round(r.y + r.height / 2) });
    })()
`;

const WINDSURF_SUBMIT_EXPR = `
    (function() {
        var el = document.querySelector('[data-lexical-editor="true"]');
        if (!el) return 'no-lexical';
        var fiberKey = Object.keys(el).find(function(k) { return k.indexOf('__reactFiber') === 0; });
        if (fiberKey) {
            var current = el[fiberKey];
            for (var j = 0; j < 40 && current; j++) {
                if (current.memoizedProps && typeof current.memoizedProps.onEnter === 'function') {
                    var fakeEvent = new KeyboardEvent('keydown', {
                        key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
                        shiftKey: false, altKey: false, ctrlKey: false, metaKey: false,
                        bubbles: true, cancelable: true
                    });
                    try {
                        Object.defineProperty(fakeEvent, 'preventDefault', { value: function() {} });
                        Object.defineProperty(fakeEvent, 'stopPropagation', { value: function() {} });
                    } catch (_) {}
                    var ret = current.memoizedProps.onEnter(fakeEvent);
                    return ret ? 'sent' : 'rejected';
                }
                current = current.return;
            }
        }
        var btns = Array.from(document.querySelectorAll('button,[role="button"]')).filter(function(btn) {
            if (!btn.offsetParent || btn.disabled || btn.getAttribute('aria-disabled') === 'true') return false;
            var label = ((btn.getAttribute('aria-label') || '') + ' ' + (btn.title || '') + ' ' + (btn.textContent || '')).toLowerCase();
            return label.includes('send') || label.includes('submit') || label.includes('发送');
        });
        if (btns.length) {
            btns[btns.length - 1].click();
            return 'clicked-send';
        }
        return 'no-onEnter';
    })()
`;

async function fetchCdpPages(cdpPort) {
    return new Promise((resolve, reject) => {
        http.get(`http://${CDP_HOST}:${cdpPort}/json`, { timeout: 5000 }, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                try { resolve(JSON.parse(data)); } catch (e) { reject(e); }
            });
        }).on('error', reject).on('timeout', function () {
            this.destroy();
            reject(new Error('timeout'));
        });
    });
}

function selectIdePage(pages, appName = '') {
    const lower = String(appName || '').toLowerCase();
    let page = pages.find(p => p.type === 'page' && p.url && p.url.includes('workbench') && !p.url.includes('workbench-jetski'));
    if (!page && lower === 'uitty') {
        page = pages.find(p => p.type === 'page' && p.url && (p.title === 'uitty' || p.url.includes('uitty')));
    }
    if (!page && (lower === 'dsme' || lower.includes('deepseek'))) {
        page = pages.find(p => p.type === 'page' && p.url && /^https?:\/\/(localhost|127\.0\.0\.1):/i.test(p.url));
    }
    return page || null;
}

async function withIdeCdp(cdpPort, callback) {
    const targetAppName = activeCdpTargets.get(cdpPort)?.appName || '';
    const pages = await fetchCdpPages(cdpPort);
    const page = selectIdePage(pages, targetAppName);
    if (!page || !page.webSocketDebuggerUrl) {
        throw new Error(`未找到 ${targetAppName || 'IDE'} workbench 页面`);
    }

    const ws = new WebSocket(page.webSocketDebuggerUrl);
    let msgId = 1;
    const cdpCall = (method, params, timeoutMs = CDP_TIMEOUT_MS) => {
        const id = msgId++;
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error(`cdp timeout: ${method}`)), timeoutMs);
            const handler = d => {
                const r = JSON.parse(d);
                if (r.id === id) {
                    clearTimeout(timer);
                    ws.removeListener('message', handler);
                    if (r.error) reject(new Error(r.error.message || method));
                    else resolve(r.result);
                }
            };
            ws.on('message', handler);
            ws.send(JSON.stringify({ id, method, params: params || {} }));
        });
    };

    await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('WS超时')), 5000);
        ws.on('open', () => { clearTimeout(timer); resolve(); });
        ws.on('error', reject);
    });

    try {
        return await callback(cdpCall);
    } finally {
        try { ws.close(); } catch (_) {}
    }
}

function schedulerAppKey(appName) {
    const lower = String(appName || '').toLowerCase();
    if (lower.startsWith('uitty:') || lower.includes('uitty')) return 'uitty';
    if (lower.includes('cursor')) return 'cursor';
    if (lower.includes('windsurf')) return 'windsurf';
    if (lower.includes('codex')) return 'codex';
    if (lower.includes('claude')) return 'claude';
    if (lower.includes('dsme') || lower.includes('deepseek')) return 'dsme';
    if (lower.includes('antigravity')) return 'antigravity';
    return lower || 'unknown';
}

function parseJsonEvalValue(result, fallback = 'evaluate 无返回值') {
    const raw = result?.result?.value;
    if (raw == null) return { ok: false, err: fallback };
    if (typeof raw === 'object') return raw;
    try {
        return JSON.parse(String(raw));
    } catch (_) {
        return { ok: false, err: '解析结果失败', raw };
    }
}

async function runModelSwitchScript(scriptPath, cdpPort, modelName, env = {}) {
    if (!fs.existsSync(scriptPath)) {
        throw new Error(`模型切换脚本不存在: ${scriptPath}`);
    }
    try {
        const { stdout, stderr } = await execFilePromise(
            process.execPath,
            [scriptPath, modelName, '--port', String(cdpPort), '--quiet'],
            { timeout: 60_000, env: { ...process.env, ...env } }
        );
        return (stdout || stderr || '').trim();
    } catch (e) {
        const detail = (e.stderr || e.stdout || e.message || '').toString().trim();
        throw new Error(detail || e.message);
    }
}

async function switchAntigravityLikeModel(cdpPort, modelName) {
    const input = String(modelName || '').toLowerCase().trim();
    const result = await withIdeCdp(cdpPort, (cdpCall) => cdpCall('Runtime.evaluate', {
        expression: `
            (async function() {
                try {
                    var input = ${JSON.stringify(input)};
                    var dropdownXPath = '//*[@id="antigravity.agentSidePanelInputBox"]/div[3]/div[1]/div[3]/div/div/button';
                    var dropdownEl = document.evaluate(dropdownXPath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
                    if (!dropdownEl) dropdownEl = document.querySelector('button[aria-label^="Select model"]');
                    if (!dropdownEl) {
                        var keywords = ['claude', 'gpt', 'gemini', 'opus', 'sonnet', 'haiku', 'o1', 'o3', 'deepseek', 'swe', 'kimi', 'minimax'];
                        var allBtns = document.querySelectorAll('button');
                        for (var i = 0; i < allBtns.length; i++) {
                            if (!allBtns[i].offsetParent) continue;
                            var t = (allBtns[i].textContent || '').trim().toLowerCase();
                            for (var k = 0; k < keywords.length; k++) {
                                if (t.indexOf(keywords[k]) >= 0 && t.length < 60) {
                                    dropdownEl = allBtns[i];
                                    break;
                                }
                            }
                            if (dropdownEl) break;
                        }
                    }
                    if (!dropdownEl) return JSON.stringify({ ok:false, err:'找不到模型选择下拉框' });
                    dropdownEl.click();
                    await new Promise(function(r) { setTimeout(r, 500); });

                    var btnLabel = function(b) {
                        return ((b.textContent || '') + ' ' + (b.getAttribute('aria-label') || '')).toLowerCase();
                    };
                    var btns = Array.from(document.querySelectorAll('button, [role="menuitem"], [role="option"]'));
                    var targetBtn = null;
                    var bestBtn = null;
                    var maxMatches = 0;
                    var tokens = input.replace(/[()]/g, '').split(/\\s+/).filter(Boolean);
                    var available = [];

                    for (var i = 0; i < btns.length; i++) {
                        if (!btns[i].offsetParent) continue;
                        var label = btnLabel(btns[i]).replace(/\\s+/g, ' ').trim();
                        var aria = btns[i].getAttribute('aria-label') || '';
                        if (aria.startsWith('Select model')) continue;
                        if (label.length > 1 && label.length < 80) available.push(label);
                        if (label === input || label.indexOf(input) >= 0) {
                            targetBtn = btns[i];
                            break;
                        }
                        var matches = 0;
                        for (var t = 0; t < tokens.length; t++) {
                            if (label.indexOf(tokens[t]) >= 0) matches++;
                        }
                        if (matches > maxMatches && btns[i] !== dropdownEl) {
                            maxMatches = matches;
                            bestBtn = btns[i];
                        }
                    }
                    if (!targetBtn && maxMatches > 0) targetBtn = bestBtn;
                    if (!targetBtn) {
                        dropdownEl.click();
                        return JSON.stringify({ ok:false, err:'在列表中找不到匹配的模型: ' + input, available: available.slice(0, 20) });
                    }
                    targetBtn.click();
                    return JSON.stringify({ ok:true, info: (targetBtn.textContent || targetBtn.getAttribute('aria-label') || '').trim() });
                } catch (e) {
                    return JSON.stringify({ ok:false, err:e.message });
                }
            })()
        `,
        awaitPromise: true,
        returnByValue: true,
        timeout: 60000
    }, 60_000));
    const parsed = parseJsonEvalValue(result);
    if (!parsed.ok) throw new Error(parsed.err || '模型切换失败');
    return parsed.info || modelName;
}

async function switchWindsurfModel(cdpPort, modelName) {
    const input = String(modelName || '').toLowerCase().trim();
    const result = await withIdeCdp(cdpPort, (cdpCall) => cdpCall('Runtime.evaluate', {
        expression: `
            (async function() {
                try {
                    var input = ${JSON.stringify(input)};
                    var panel = document.getElementById('windsurf.cascadePanel');
                    if (!panel) return JSON.stringify({ ok:false, err:'找不到 cascadePanel' });
                    var modelBtn = null;
                    var precBtns = panel.querySelectorAll('button[class*="cursor-pointer"][class*="flex-row"][class*="items-center"]');
                    for (var pb = 0; pb < precBtns.length; pb++) {
                        if (!precBtns[pb].offsetParent) continue;
                        var pt = (precBtns[pb].textContent || '').trim();
                        if (pt.length > 0 && pt.length < 40) { modelBtn = precBtns[pb]; break; }
                    }
                    if (!modelBtn) {
                        var kw = ['adaptive','claude','gpt','gemini','sonnet','opus','haiku','deepseek','o1','o3','o4','flash','swe','kimi','codestral','mistral','llama','glm','grok','minimax'];
                        var roots = document.querySelectorAll('[class*="chat-client-root"]');
                        for (var ri = 0; ri < roots.length && !modelBtn; ri++) {
                            var rBtns = roots[ri].querySelectorAll('button');
                            for (var rb = 0; rb < rBtns.length; rb++) {
                                if (!rBtns[rb].offsetParent) continue;
                                var rt = (rBtns[rb].textContent || '').trim().toLowerCase();
                                for (var rk = 0; rk < kw.length; rk++) {
                                    if (rt.indexOf(kw[rk]) >= 0 && rt.length < 40) { modelBtn = rBtns[rb]; break; }
                                }
                                if (modelBtn) break;
                            }
                        }
                    }
                    if (!modelBtn) return JSON.stringify({ ok:false, err:'找不到模型选择按钮' });
                    modelBtn.click();
                    await new Promise(function(r) { setTimeout(r, 500); });

                    var allBtns = panel.querySelectorAll('button');
                    for (var sm = 0; sm < allBtns.length; sm++) {
                        if (!allBtns[sm].offsetParent) continue;
                        if ((allBtns[sm].textContent || '').trim() === 'See more') {
                            allBtns[sm].click();
                            await new Promise(function(r) { setTimeout(r, 400); });
                            break;
                        }
                    }

                    var menuItems = panel.querySelectorAll('button[class*="flex"][class*="w-full"][class*="flex-col"]');
                    var bestMatch = null;
                    var bestScore = -1;
                    var available = [];
                    for (var mi = 0; mi < menuItems.length; mi++) {
                        var item = menuItems[mi];
                        if (!item.offsetParent) continue;
                        var rect = item.getBoundingClientRect();
                        if (rect.y < 0) continue;
                        var walker = document.createTreeWalker(item, NodeFilter.SHOW_TEXT, null, false);
                        var firstText = '';
                        var node;
                        while (node = walker.nextNode()) {
                            var nt = node.textContent.trim();
                            if (nt) { firstText = nt; break; }
                        }
                        if (!firstText) continue;
                        var modelName = firstText.toLowerCase();
                        available.push(firstText);
                        if (modelName === input) { bestMatch = item; bestScore = 100; break; }
                        if (modelName.indexOf(input) >= 0 && bestScore < 50) { bestMatch = item; bestScore = 50; }
                        if (input.indexOf(modelName) >= 0 && bestScore < 40) { bestMatch = item; bestScore = 40; }
                        if (bestScore < 30) {
                            var inputWords = input.split(/[\\s\\-\\.]+/).filter(function(w){return w.length>0;});
                            var nameWords = modelName.split(/[\\s\\-\\.]+/).filter(function(w){return w.length>0;});
                            var allMatch = inputWords.length > 0;
                            for (var iw = 0; iw < inputWords.length && allMatch; iw++) {
                                var found = false;
                                for (var nw = 0; nw < nameWords.length; nw++) {
                                    if (nameWords[nw].indexOf(inputWords[iw]) >= 0 || inputWords[iw].indexOf(nameWords[nw]) >= 0) { found = true; break; }
                                }
                                if (!found) allMatch = false;
                            }
                            if (allMatch) { bestMatch = item; bestScore = 30; }
                        }
                    }
                    if (bestMatch) {
                        bestMatch.click();
                        var w2 = document.createTreeWalker(bestMatch, NodeFilter.SHOW_TEXT, null, false);
                        var clickedName = '';
                        var n2;
                        while (n2 = w2.nextNode()) {
                            var t2 = n2.textContent.trim();
                            if (t2) { clickedName = t2; break; }
                        }
                        return JSON.stringify({ ok:true, info: clickedName || bestMatch.textContent.trim() });
                    }
                    document.dispatchEvent(new KeyboardEvent('keydown', { key:'Escape', code:'Escape', bubbles:true }));
                    return JSON.stringify({ ok:false, err:'找不到匹配模型: ' + input, available: available.slice(0, 20) });
                } catch (e) {
                    return JSON.stringify({ ok:false, err:e.message });
                }
            })()
        `,
        awaitPromise: true,
        returnByValue: true,
        timeout: 60000
    }, 60_000));
    const parsed = parseJsonEvalValue(result);
    if (!parsed.ok) throw new Error(parsed.err || 'Windsurf 模型切换失败');
    return parsed.info || modelName;
}

async function switchCodexModel(cdpPort, modelName) {
    const input = String(modelName || '').toLowerCase().trim();
    return withIdeCdp(cdpPort, async (cdpCall) => {
        const centerResult = await cdpCall('Runtime.evaluate', {
            expression: `
                (function(){
                    function norm(el) { return (el.innerText || el.textContent || '').replace(/\\s+/g, ' ').trim(); }
                    function visible(el) {
                        var r = el.getBoundingClientRect();
                        var s = window.getComputedStyle(el);
                        return r.width > 0 && r.height > 0 && r.bottom > 0 && r.right > 0 &&
                            r.top < window.innerHeight && r.left < window.innerWidth &&
                            s.visibility !== 'hidden' && s.display !== 'none';
                    }
                    function scoreButton(btn) {
                        if (!visible(btn)) return -1;
                        var t = norm(btn).toLowerCase();
                        var r = btn.getBoundingClientRect();
                        if (/(automation|project|search|new chat|work locally|branch|copy|chat action|settings?)/.test(t)) return -1;
                        var hasModel = /\\b(\\d+\\.\\d+|gpt-\\d|codex|o\\d)\\b/.test(t);
                        var hasLevel = /\\b(extra high|high|medium|low)\\b/.test(t);
                        if (!hasModel && !hasLevel) return -1;
                        var score = 0;
                        if (r.y > window.innerHeight * 0.55) score += 10;
                        if (hasModel) score += 5;
                        if (hasLevel) score += 3;
                        if (btn.getAttribute('aria-haspopup') === 'menu') score += 2;
                        if (r.width >= 40 && r.width <= 240) score += 1;
                        return score;
                    }
                    var best = null, bestScore = 7;
                    var btns = document.querySelectorAll('button');
                    for (var i = 0; i < btns.length; i++) {
                        var score = scoreButton(btns[i]);
                        if (score > bestScore) { best = btns[i]; bestScore = score; }
                    }
                    if (!best) return '';
                    var r = best.getBoundingClientRect();
                    return r.x + r.width/2 + ',' + (r.y + r.height/2);
                })()
            `,
            returnByValue: true
        });
        const center = centerResult?.result?.value || '';
        if (!String(center).includes(',')) throw new Error('找不到 Codex 模型按钮');
        const [x, y] = String(center).split(',').map(Number);
        await cdpCall('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y });
        await cdpCall('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
        await cdpCall('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
        await new Promise(r => setTimeout(r, 600));

        const isLevel = ['low', 'medium', 'high', 'extra high'].includes(input);
        const isSpeed = ['fast', 'standard'].includes(input);
        if (isLevel) {
            const posResult = await cdpCall('Runtime.evaluate', {
                expression: `
                    (function(){
                        var input = ${JSON.stringify(input)};
                        var menus = document.querySelectorAll('[role=menu]');
                        if (!menus.length) return '';
                        var spans = menus[0].querySelectorAll('span');
                        for (var i = 0; i < spans.length; i++) {
                            if ((spans[i].textContent || '').trim().toLowerCase() === input) {
                                var r = spans[i].getBoundingClientRect();
                                return r.x + r.width/2 + ',' + (r.y + r.height/2);
                            }
                        }
                        return '';
                    })()
                `,
                returnByValue: true
            });
            const pos = posResult?.result?.value || '';
            if (!String(pos).includes(',')) throw new Error(`找不到 Codex Intelligence 等级: ${modelName}`);
            const [tx, ty] = String(pos).split(',').map(Number);
            await cdpCall('Input.dispatchMouseEvent', { type: 'mousePressed', x: tx, y: ty, button: 'left', clickCount: 1 });
            await cdpCall('Input.dispatchMouseEvent', { type: 'mouseReleased', x: tx, y: ty, button: 'left', clickCount: 1 });
            return modelName;
        }

        const hoverResult = await cdpCall('Runtime.evaluate', {
            expression: `
                (function(){
                    var isSpeed = ${isSpeed ? 'true' : 'false'};
                    var menus = document.querySelectorAll('[role=menu]');
                    for (var i = 0; i < menus.length; i++) {
                        var spans = menus[i].querySelectorAll('span');
                        for (var j = 0; j < spans.length; j++) {
                            var t = (spans[j].textContent || '').trim();
                            var hit = isSpeed
                                ? t.toLowerCase() === 'speed'
                                : (t.match(/^\\d+\\.\\d+/) || t.match(/^GPT-\\d/i) || t.match(/^gpt-\\d/i) || t.match(/^o\\d-/i) || t.match(/^codex/i));
                            if (hit) {
                                var r = spans[j].getBoundingClientRect();
                                return r.x + r.width/2 + ',' + (r.y + r.height/2);
                            }
                        }
                    }
                    return '';
                })()
            `,
            returnByValue: true
        });
        const hoverPos = hoverResult?.result?.value || '';
        if (!String(hoverPos).includes(',')) throw new Error('Codex 主菜单中找不到模型项');
        const [mx, my] = String(hoverPos).split(',').map(Number);
        await cdpCall('Input.dispatchMouseEvent', { type: 'mouseMoved', x: mx, y: my });
        await new Promise(r => setTimeout(r, 600));

        const targetResult = await cdpCall('Runtime.evaluate', {
            expression: `
                (function(){
                    var input = ${JSON.stringify(input)};
                    var normalizedInput = input.replace(/^gpt[-\\s]*/i, '').replace(/^openai[-\\s]*/i, '').trim();
                    var menus = document.querySelectorAll('[role=menu]');
                    if (menus.length < 2) return JSON.stringify({ err:'子菜单未展开', count:menus.length });
                    var sub = menus[menus.length - 1];
                    var spans = sub.querySelectorAll('span');
                    for (var i = 0; i < spans.length; i++) {
                        var text = (spans[i].textContent || '').trim();
                        var t = text.toLowerCase();
                        var normalizedText = t.replace(/^gpt[-\\s]*/i, '').replace(/^openai[-\\s]*/i, '').trim();
                        if ((t.indexOf(input) >= 0 || normalizedText.indexOf(normalizedInput) >= 0) &&
                            t !== 'change model' && t !== 'other models') {
                            var r = spans[i].getBoundingClientRect();
                            return JSON.stringify({ ok:true, x:r.x + r.width/2, y:r.y + r.height/2, text:text });
                        }
                    }
                    var avail = [];
                    for (var j = 0; j < spans.length; j++) {
                        var a = (spans[j].textContent || '').trim();
                        if (a.length > 1 && a !== 'Change model') avail.push(a);
                    }
                    return JSON.stringify({ err:'子菜单中无匹配', avail:avail });
                })()
            `,
            returnByValue: true
        });
        const parsed = parseJsonEvalValue(targetResult);
        if (!parsed.ok) throw new Error(`${parsed.err || 'Codex 模型切换失败'}${parsed.avail ? ` (可选: ${parsed.avail.join(', ')})` : ''}`);
        await cdpCall('Input.dispatchMouseEvent', { type: 'mousePressed', x: parsed.x, y: parsed.y, button: 'left', clickCount: 1 });
        await cdpCall('Input.dispatchMouseEvent', { type: 'mouseReleased', x: parsed.x, y: parsed.y, button: 'left', clickCount: 1 });
        return parsed.text || modelName;
    });
}

async function schedulerSwitchModel(cdpPort, task, modelName) {
    const appName = activeCdpTargets.get(cdpPort)?.appName || task.targetIde || '';
    const appKey = schedulerAppKey(appName);
    if (!String(modelName || '').trim()) return;

    log(`🎚️ [${task.id}] 切换模型: ${appName}:${cdpPort} → ${modelName}`);
    let picked = modelName;
    if (appKey === 'cursor') {
        const script = path.join(os.homedir(), '.agents', 'skills', 'cursor', 'scripts', 'cursor_switch_model.js');
        picked = await runModelSwitchScript(script, cdpPort, modelName, { CURSOR_CDP_PORT: String(cdpPort) }) || modelName;
    } else if (appKey === 'antigravity' || appKey === 'dsme') {
        picked = await switchAntigravityLikeModel(cdpPort, modelName);
    } else if (appKey === 'windsurf') {
        picked = await switchWindsurfModel(cdpPort, modelName);
    } else if (appKey === 'codex') {
        picked = await switchCodexModel(cdpPort, modelName);
    } else if (appKey === 'claude') {
        await sendMessageToIde(cdpPort, `/model ${modelName}`, task.fixedSessionTitle || null, null);
        picked = modelName;
    } else if (appKey === 'uitty') {
        throw new Error('uitty 终端没有全局模型选择器，不能在调度阶段自动切换模型');
    } else {
        throw new Error(`当前 IDE 不支持调度模型切换: ${appName || task.targetIde}`);
    }
    log(`✅ [${task.id}] 模型已切换: ${picked}`);
}

function schedulerGeneratingExpression(appName) {
    const appKey = schedulerAppKey(appName);
    if (appKey === 'uitty') return `(function(){ return false; })()`;
    return `
        (function() {
            function flatten(root) {
                var out = [];
                function walk(node) {
                    if (!node) return;
                    if (node.nodeType === 1) {
                        out.push(node);
                        if (node.shadowRoot) walk(node.shadowRoot);
                    }
                    for (var c = node.firstChild; c; c = c.nextSibling) walk(c);
                }
                walk(root);
                return out;
            }
            function docs(d) {
                var out = [d];
                var ifr = d.querySelectorAll ? d.querySelectorAll('iframe') : [];
                for (var i = 0; i < ifr.length; i++) {
                    try { if (ifr[i].contentDocument) out.push(ifr[i].contentDocument); } catch(e) {}
                }
                return out;
            }
            var roots = docs(document);
            for (var di = 0; di < roots.length; di++) {
                var doc = roots[di];
                if (!doc || !doc.querySelectorAll) continue;
                var nodes = flatten(doc);
                for (var i = 0; i < nodes.length; i++) {
                    var el = nodes[i];
                    if (!el.tagName) continue;
                    var tag = el.tagName.toLowerCase();
                    var role = el.getAttribute('role') || '';
                    var cls = (typeof el.className === 'string' ? el.className : '').toLowerCase();
                    var isButtonish = tag === 'button' || role === 'button' || cls.indexOf('button') >= 0 || tag.indexOf('button') >= 0;
                    if (!isButtonish) continue;
                    if (!el.offsetParent && !(el.getRootNode && el.getRootNode() instanceof ShadowRoot)) continue;
                    var text = (el.textContent || '').trim().toLowerCase();
                    var aria = (el.getAttribute('aria-label') || '').toLowerCase();
                    var title = (el.getAttribute('title') || '').toLowerCase();
                    if (text === 'stop' || text === 'cancel' || text === '停止生成' || text === '停止会话' ||
                        aria.indexOf('stop') >= 0 || aria.indexOf('cancel') >= 0 || aria.indexOf('停止') >= 0 ||
                        title.indexOf('stop') >= 0 || cls.indexOf('stop-button') >= 0 || cls.indexOf('cancel-button') >= 0) {
                        return true;
                    }
                    if (['run','allow','approve','continue','yes','always allow','allow once','allow in workspace','approve action','run action'].indexOf(text) >= 0) {
                        return true;
                    }
                }
                var spinners = doc.querySelectorAll('[class*="animate-spin"], [class*="loading"], [class*="spinner"]');
                for (var s = 0; s < spinners.length; s++) {
                    if (spinners[s].offsetParent) return true;
                }
            }
            return false;
        })()
    `;
}

async function isIdeGenerating(cdpPort) {
    const appName = activeCdpTargets.get(cdpPort)?.appName || '';
    const result = await withIdeCdp(cdpPort, (cdpCall) => cdpCall('Runtime.evaluate', {
        expression: schedulerGeneratingExpression(appName),
        returnByValue: true
    }));
    return result?.result?.value === true;
}

async function waitForSchedulerStageCompletion(cdpPort, task, stageIndex) {
    const appName = activeCdpTargets.get(cdpPort)?.appName || task.targetIde || '';
    if (schedulerAppKey(appName) === 'uitty') {
        log(`ℹ️ [${task.id}] uitty 无可靠完成检测，阶段 ${stageIndex + 1} 发送后视为完成`);
        return;
    }

    const start = Date.now();
    let sawGenerating = false;
    let idleTicks = 0;
    let neverBusyIdleTicks = 0;
    await new Promise(r => setTimeout(r, 1200));

    while (Date.now() - start < SCHEDULER_STAGE_TIMEOUT_MS) {
        if (!activeTimers.has(task.id)) {
            throw new Error('任务已取消');
        }
        let generating = false;
        try {
            generating = await isIdeGenerating(cdpPort);
        } catch (e) {
            log(`⚠️ [${task.id}] 阶段 ${stageIndex + 1}: 检测生成状态失败: ${e.message}`);
        }
        if (generating) {
            sawGenerating = true;
            idleTicks = 0;
            neverBusyIdleTicks = 0;
        } else if (sawGenerating) {
            idleTicks += 1;
            if (idleTicks >= 2) return;
        } else {
            neverBusyIdleTicks += 1;
            if (neverBusyIdleTicks >= 3) {
                log(`ℹ️ [${task.id}] 阶段 ${stageIndex + 1}: 未检测到生成中，连续空闲后视为完成`);
                return;
            }
        }
        await new Promise(r => setTimeout(r, SCHEDULER_STAGE_POLL_MS));
    }
    throw new Error(`阶段 ${stageIndex + 1} 等待完成超时 (${Math.round(SCHEDULER_STAGE_TIMEOUT_MS / 60000)} 分钟)`);
}

/**
 * 通过 CDP WebSocket 向指定端口的 IDE 发送消息。
 * 被 /cdp/{port}/send 路由和调度引擎共用。
 *
 * @param {number} cdpPort - 目标 IDE 的 CDP 调试端口
 * @param {string} message - 要发送的消息文本
 * @throws {Error} 当目标不可用、无 workbench、或 CDP 操作失败时抛出
 */
async function sendMessageToIde(cdpPort, message, fixedSessionTitle = null, targetPid = null) {
    const targetAppName = activeCdpTargets.get(cdpPort)?.appName || '';
    // 1. 获取 workbench 页面列表
    const pages = await new Promise((resolve, reject) => {
        http.get(`http://${CDP_HOST}:${cdpPort}/json`, { timeout: 5000 }, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                try { resolve(JSON.parse(data)); } catch (e) { reject(e); }
            });
        }).on('error', reject).on('timeout', function () { this.destroy(); reject(new Error('timeout')); });
    });

    let wb = pages.find(p => p.type === 'page' && p.url && p.url.includes('workbench') && !p.url.includes('workbench-jetski'));
    if (!wb && targetAppName.toLowerCase() === 'uitty') {
        wb = pages.find(p => p.type === 'page' && p.url && (p.title === 'uitty' || p.url.includes('uitty')));
    }
    if (!wb) throw new Error('未找到 workbench 或 uitty 页面');

    // 2. 建立 WebSocket 并发送消息
    const wsUrl = wb.webSocketDebuggerUrl;
    const ws = new WebSocket(wsUrl);
    let msgId = 1;

    function cdpCall(method, params) {
        const id = msgId++;
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error('cdp timeout')), CDP_TIMEOUT_MS);
            const handler = d => {
                const r = JSON.parse(d);
                if (r.id === id) { clearTimeout(timer); ws.removeListener('message', handler); resolve(r.result); }
            };
            ws.on('message', handler);
            ws.send(JSON.stringify({ id, method, params: params || {} }));
        });
    }

    await new Promise((resolve, reject) => {
        // P2#13: 全局超时保护（20 秒），防止 WebSocket 挂住
        const globalTimer = setTimeout(() => {
            ws.close();
            reject(new Error('sendMessageToIde 全局超时 (20s)'));
        }, 20_000);

        ws.on('open', async () => {
            try {
                if (targetAppName.toLowerCase() === 'uitty') {
                    const writeExpr = `(function() {
                        if (typeof tabs === 'undefined') return false;
                        let targetPane = null;
                        if (${targetPid ? 'true' : 'false'}) {
                            for (const t of tabs) {
                                for (const p of t.panes) {
                                    if (p.pid === ${targetPid}) {
                                        switchTab(t.id);
                                        focusPane(p.id);
                                        targetPane = p;
                                        break;
                                    }
                                }
                                if (targetPane) break;
                            }
                        } else {
                            targetPane = tabs.flatMap(t => t.panes).find(p => p.id === activePaneId);
                        }
                        if (targetPane && targetPane.ptyId) {
                            window.uitty.write(targetPane.ptyId, ${JSON.stringify(message + '\r')});
                            return true;
                        }
                        return false;
                    })()`;
                    const res = await cdpCall('Runtime.evaluate', { expression: writeExpr, awaitPromise: true, returnByValue: true });
                    if (!res?.result?.value) {
                        throw new Error('Uitty 注入失败：未找到指定进程的面板');
                    }
                    clearTimeout(globalTimer);
                    ws.close();
                    resolve();
                    return;
                }

                if (fixedSessionTitle) {
                    const switchScript = `
                        (async function() {
                            try {
                                var target = ${JSON.stringify(fixedSessionTitle)}.toLowerCase().trim();
                                
                                var historyBtn = document.querySelector('a[data-tooltip-id="history-tooltip"]');
                                if (!historyBtn) {
                                    var btns = document.querySelectorAll('a, button, [role="button"]');
                                    for (var i = 0; i < btns.length; i++) {
                                        if (!btns[i].offsetParent) continue;
                                        var tooltip = (btns[i].getAttribute('data-tooltip-id') || '').toLowerCase();
                                        var aria = (btns[i].getAttribute('aria-label') || '').toLowerCase();
                                        var text = (btns[i].textContent || '').trim().toLowerCase();
                                        if (tooltip.includes('history') || text === 'past conversations' || aria.includes('history') || aria.includes('recent') || text === '历史记录' || text === 'recent sessions') {
                                            historyBtn = btns[i]; break;
                                        }
                                    }
                                }

                                if (historyBtn) {
                                    historyBtn.click();
                                    await new Promise(r => setTimeout(r, 800));
                                }

                                var items = [];
                                var container = document.querySelector('[class*="bg-quickinput-background"]');
                                if (container) {
                                    var divs = container.querySelectorAll('div.cursor-pointer');
                                    for (var i = 0; i < divs.length; i++) {
                                        var cls = (typeof divs[i].className === 'string') ? divs[i].className : '';
                                        if (cls.includes('justify-between') && divs[i].offsetParent) items.push(divs[i]);
                                    }
                                }

                                if (items.length === 0) {
                                    var panel = document.getElementById('windsurf.cascadePanel');
                                    if (panel) items = Array.from(panel.querySelectorAll('[class*="cursor-pointer"]'));
                                }

                                for (var i = 0; i < items.length; i++) {
                                    var text = (items[i].textContent || '').trim().toLowerCase();
                                    if (text === target || text.startsWith(target)) {
                                        items[i].click();
                                        await new Promise(r => setTimeout(r, 800));
                                        return 'ok';
                                    }
                                }

                                document.dispatchEvent(new KeyboardEvent('keydown', {'key': 'Escape'}));
                                return 'not-found';
                            } catch (e) {
                                return 'error: ' + e.message;
                            }
                        })()
                    `;
                    let switchRes = await cdpCall('Runtime.evaluate', { expression: switchScript, awaitPromise: true, returnByValue: true });
                    if (!switchRes || !switchRes.result || switchRes.result.value !== 'ok') {
                        throw new Error(`固定会话切换失败: ${switchRes?.result?.value || '无返回结果'}`);
                    }
                }

                if (targetAppName.toLowerCase() === 'windsurf') {
                    let infoRes = await cdpCall('Runtime.evaluate', {
                        expression: WINDSURF_EDITOR_INFO_EXPR,
                        returnByValue: true,
                    });
                    let info = {};
                    try { info = JSON.parse(infoRes?.result?.value || '{}'); } catch (_) {}
                    if (info.error === 'no-lexical') {
                        log('📨 Windsurf 未找到输入框，尝试 Cmd+L 打开 Cascade 后重试');
                        await cdpCall('Input.dispatchKeyEvent', { type: 'keyDown', key: 'l', code: 'KeyL', modifiers: 4, windowsVirtualKeyCode: 76 });
                        await cdpCall('Input.dispatchKeyEvent', { type: 'keyUp', key: 'l', code: 'KeyL', modifiers: 4, windowsVirtualKeyCode: 76 });
                        await new Promise(r => setTimeout(r, 1500));
                        infoRes = await cdpCall('Runtime.evaluate', {
                            expression: WINDSURF_EDITOR_INFO_EXPR,
                            returnByValue: true,
                        });
                        try { info = JSON.parse(infoRes?.result?.value || '{}'); } catch (_) { info = {}; }
                    }
                    if (!info.ok) throw new Error(`Windsurf 发送失败: ${info.error || 'no-editor-info'}`);

                    await cdpCall('Input.dispatchMouseEvent', {
                        type: 'mousePressed', x: info.x, y: info.y, button: 'left', clickCount: 1,
                    });
                    await cdpCall('Input.dispatchMouseEvent', {
                        type: 'mouseReleased', x: info.x, y: info.y, button: 'left', clickCount: 1,
                    });
                    await new Promise(r => setTimeout(r, 100));
                    await cdpCall('Input.dispatchKeyEvent', { type: 'keyDown', key: 'a', code: 'KeyA', modifiers: 4, windowsVirtualKeyCode: 65 });
                    await cdpCall('Input.dispatchKeyEvent', { type: 'keyUp', key: 'a', code: 'KeyA', modifiers: 4, windowsVirtualKeyCode: 65 });
                    await cdpCall('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 });
                    await cdpCall('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 });
                    await new Promise(r => setTimeout(r, 150));
                    await cdpCall('Input.insertText', { text: message });
                    await new Promise(r => setTimeout(r, 300));

                    const sendRes = await cdpCall('Runtime.evaluate', {
                        expression: WINDSURF_SUBMIT_EXPR,
                        returnByValue: true,
                    });
                    const sendValue = sendRes?.result?.value || '';
                    log(`📨 Windsurf send result: ${sendValue}`);
                    if (sendValue !== 'sent' && sendValue !== 'clicked-send') {
                        throw new Error(`Windsurf 发送失败: ${sendValue || '无返回结果'}`);
                    }
                    clearTimeout(globalTimer);
                    ws.close();
                    resolve();
                    return;
                }

                let r = await cdpCall('Runtime.evaluate', { expression: FOCUS_INPUT_EXPR, returnByValue: true });
                if (r.result?.value === 'no-input') {
                    // Cmd+L 打开聊天面板
                    await cdpCall('Input.dispatchKeyEvent', { type: 'keyDown', key: 'l', code: 'KeyL', modifiers: 4, windowsVirtualKeyCode: 76 });
                    await cdpCall('Input.dispatchKeyEvent', { type: 'keyUp', key: 'l', code: 'KeyL', modifiers: 4, windowsVirtualKeyCode: 76 });
                    await new Promise(r => setTimeout(r, 1500));
                    await cdpCall('Runtime.evaluate', { expression: FOCUS_INPUT_EXPR, returnByValue: true });
                }
                await new Promise(r => setTimeout(r, 300));
                await cdpCall('Input.insertText', { text: message });
                await new Promise(r => setTimeout(r, 500));
                // 发送普通 Enter (兼容单行输入框)
                await cdpCall('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13 });
                await cdpCall('Input.dispatchKeyEvent', { type: 'char', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13 });
                await cdpCall('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13 });
                
                await new Promise(r => setTimeout(r, 200));
                
                // 发送 Cmd+Enter 以确保支持 Cursor Composer等多行输入框提交 (Modifiers: 4 代表 Meta/Command)
                await cdpCall('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Enter', code: 'Enter', modifiers: 4, windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13 });
                await cdpCall('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', modifiers: 4, windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13 });
                clearTimeout(globalTimer);
                ws.close();
                resolve();
            } catch (err) { clearTimeout(globalTimer); ws.close(); reject(err); }
        });
        ws.on('error', (err) => { clearTimeout(globalTimer); reject(err); });
    });
}

// ═══════════════════════════════════════════════════════════════
// ── 调度引擎实现 ───────────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════

const SCHEDULER_FILE = path.join(CWD_HISTORY_DIR, 'scheduler_tasks.json');
const activeTimers = new Map(); // taskId → { timer, config }
const taskExecCounts = new Map(); // taskId → count
const taskCurrentStage = new Map(); // taskId → current stage index (-1 = idle)

/** 加载持久化的任务 */
function schedulerLoadTasks() {
    try {
        if (fs.existsSync(SCHEDULER_FILE)) {
            return JSON.parse(fs.readFileSync(SCHEDULER_FILE, 'utf8'));
        }
    } catch (e) { log(`⚠️ 读取调度任务失败: ${e.message}`); }
    return [];
}

/** 保存任务到文件 */
function schedulerSaveTasks(tasks) {
    try {
        fs.mkdirSync(CWD_HISTORY_DIR, { recursive: true });
        fs.writeFileSync(SCHEDULER_FILE, JSON.stringify(tasks, null, 2));
    } catch (e) { log(`⚠️ 保存调度任务失败: ${e.message}`); }
}

/** 列出所有任务（含运行时信息） */
function schedulerListTasks() {
    const saved = schedulerLoadTasks();
    return saved.map(t => {
        const executionCount = getCompletedRuns(t, taskExecCounts);
        return {
            ...t,
            isRunning: activeTimers.has(t.id),
            paused: t.paused || false,
            executionCount,
            maxRuns: getMaxRuns(t),
            pipeline: t.pipeline || [],
            currentStage: taskCurrentStage.get(t.id) ?? -1
        };
    });
}

/** 创建或更新任务 */
function schedulerUpsertTask(task) {
    if (!task.targetIde || !task.prompt) throw new Error('缺少 targetIde 或 prompt');
    if (!task.id) task.id = `task-${Date.now()}`;
    if (!task.scheduleType) task.scheduleType = 'INTERVAL';
    if (!task.intervalMinutes) task.intervalMinutes = 5;

    // 停止旧定时器
    schedulerStopTimer(task.id);

    // 更新持久化
    let tasks = schedulerLoadTasks();
    const existing = tasks.find(t => t.id === task.id);
    tasks = tasks.filter(t => t.id !== task.id);
    const executionCount = getCompletedRuns(existing || task, taskExecCounts);
    const maxRuns = getMaxRuns(task);
    const reachedMaxRuns = maxRuns > 0 && executionCount >= maxRuns;
    const savedTask = {
        id: task.id,
        targetIde: task.targetIde,
        targetPort: task.targetPort || 0,
        fixedSessionTitle: task.fixedSessionTitle || '',
        prompt: task.prompt,
        scheduleType: task.scheduleType,
        intervalMinutes: task.intervalMinutes,
        cronExpression: task.cronExpression || '',
        maxRuns,
        executionCount,
        pipeline: Array.isArray(task.pipeline) && task.pipeline.length > 0 ? task.pipeline : [],
        paused: reachedMaxRuns,
        createdAt: task.createdAt || existing?.createdAt || new Date().toISOString()
    };
    tasks.push(savedTask);
    schedulerSaveTasks(tasks);

    // 启动定时器；如果编辑后已达到最大轮次，则保留为暂停状态。
    if (!savedTask.paused) schedulerStartTimer(savedTask);
    taskExecCounts.set(task.id, executionCount);
    taskCurrentStage.set(task.id, -1);

    const pipelineLabel = savedTask.pipeline.length > 0 ? ` (流水线: ${savedTask.pipeline.length} 阶段)` : '';
    log(`📅 调度任务已创建: ${task.id} → ${task.targetIde}:${task.targetPort || '?'} (${task.scheduleType === 'CRON' ? 'cron: ' + task.cronExpression : '每 ' + task.intervalMinutes + ' 分钟'})${pipelineLabel}`);
    return { success: true, task: { ...savedTask, isRunning: !savedTask.paused, executionCount } };
}

/** 取消任务（彻底删除） */
function schedulerCancelTask(taskId) {
    schedulerStopTimer(taskId);
    taskExecCounts.delete(taskId);
    let tasks = schedulerLoadTasks();
    const before = tasks.length;
    tasks = tasks.filter(t => t.id !== taskId);
    schedulerSaveTasks(tasks);
    log(`📅 调度任务已取消: ${taskId}`);
    return tasks.length < before;
}

/** 暂停任务（停止定时器，保留任务记录） */
function schedulerPauseTask(taskId) {
    let tasks = schedulerLoadTasks();
    const task = tasks.find(t => t.id === taskId);
    if (!task) return false;
    schedulerStopTimer(taskId);
    task.paused = true;
    schedulerSaveTasks(tasks);
    log(`⏸️ 调度任务已暂停: ${taskId}`);
    return true;
}

/** 恢复任务（重新启动定时器） */
function schedulerResumeTask(taskId) {
    let tasks = schedulerLoadTasks();
    const task = tasks.find(t => t.id === taskId);
    if (!task) return false;
    if (shouldSkipScheduledRun(task, taskExecCounts)) {
        task.paused = true;
        schedulerSaveTasks(tasks);
        log(`⏹️ 调度任务 ${taskId} 已达到最大轮次 ${getMaxRuns(task)}，不能恢复`);
        return false;
    }
    if (!task.paused && activeTimers.has(taskId)) {
        log(`ℹ️ 调度任务已在运行，跳过重复恢复: ${taskId}`);
        return true;
    }
    schedulerStopTimer(taskId);
    task.paused = false;
    schedulerSaveTasks(tasks);
    schedulerStartTimer(task);
    log(`▶️ 调度任务已恢复: ${taskId}`);
    return true;
}

function schedulerPersistRunCompletion(task) {
    const result = markRunCompleted(task, taskExecCounts);
    let tasks = schedulerLoadTasks();
    const saved = tasks.find(t => t.id === task.id);
    if (saved) {
        saved.executionCount = result.completedRuns;
        if (result.reachedLimit) {
            saved.paused = true;
        }
        schedulerSaveTasks(tasks);
    }
    if (result.reachedLimit) {
        schedulerStopTimer(task.id);
        taskCurrentStage.set(task.id, -1);
        log(`⏹️ [${task.id}] 已完成最大轮次 ${result.completedRuns}/${result.maxRuns}，自动停止调度`);
    }
    return result;
}

function schedulerStopAtMaxRuns(task) {
    const completedRuns = getCompletedRuns(task, taskExecCounts);
    let tasks = schedulerLoadTasks();
    const saved = tasks.find(t => t.id === task.id);
    if (saved) {
        saved.executionCount = completedRuns;
        saved.paused = true;
        schedulerSaveTasks(tasks);
    }
    schedulerStopTimer(task.id);
    taskCurrentStage.set(task.id, -1);
    log(`⏹️ [${task.id}] 已达到最大轮次 ${completedRuns}/${getMaxRuns(task)}，自动停止调度`);
}

/** 启动单个任务的定时器 */
function schedulerStartTimer(task) {
    if (task.scheduleType === 'CRON') {
        schedulerStartCronTimer(task);
    } else {
        // 使用 setTimeout 链式调用代替 setInterval，确保上一轮执行完毕后再计时
        // （避免流水线长时间执行时与下一轮并发）
        const ms = (task.intervalMinutes || 5) * 60 * 1000;
        const entry = { timer: null, config: task, type: 'interval' };
        activeTimers.set(task.id, entry);
        function scheduleNext() {
            if (activeTimers.get(task.id) !== entry) return;
            const timer = setTimeout(async () => {
                if (activeTimers.get(task.id) !== entry) return;
                try {
                    await schedulerExecuteTask(task);
                } finally {
                    scheduleNext(); // 执行完成后才开始下一轮计时
                }
            }, ms);
            entry.timer = timer;
        }
        scheduleNext();
    }
}

/** 停止定时器 */
function schedulerStopTimer(taskId) {
    const entry = activeTimers.get(taskId);
    if (entry) {
        clearTimeout(entry.timer); // clearTimeout 兼容 clearInterval
        activeTimers.delete(taskId);
    }
}

/** Cron 定时器 — 算下次触发时间后 setTimeout */
function schedulerStartCronTimer(task) {
    const spec = parseCronExpr(task.cronExpression);
    if (!spec) {
        log(`⚠️ 无效 cron 表达式: ${task.cronExpression}`);
        return;
    }
    function scheduleNext() {
        // 如果任务已被取消则不再递归调度
        if (!activeTimers.has(task.id)) return;
        const now = Date.now();
        const nextMs = cronNextFireTime(spec, now);
        const delay = Math.max(nextMs - now, 1000);
        log(`📅 Cron ${task.id}: 下次触发在 ${Math.round(delay / 1000)}s 后`);
        const timer = setTimeout(async () => {
            // 执行前再次检查任务是否仍然活跃
            if (!activeTimers.has(task.id)) return;
            try {
                await schedulerExecuteTask(task);
            } finally {
                if (!activeTimers.has(task.id)) return;
                // 60s 后再计算下一次（避免同分钟重复触发），用追踪的 timer 替换
                const cooldownTimer = setTimeout(() => scheduleNext(), 60000);
                activeTimers.set(task.id, { timer: cooldownTimer, config: task, type: 'cron' });
            }
        }, delay);
        activeTimers.set(task.id, { timer, config: task, type: 'cron' });
    }
    scheduleNext();
}

/** 执行任务：通过 sendMessageToIde 发消息给目标 IDE */
async function schedulerExecuteTask(task) {
    if (shouldSkipScheduledRun(task, taskExecCounts)) {
        schedulerStopAtMaxRuns(task);
        log(`⏹️ [${task.id}] 已达到最大轮次 ${getCompletedRuns(task, taskExecCounts)}/${getMaxRuns(task)}，跳过执行`);
        return;
    }

    const count = getCompletedRuns(task, taskExecCounts) + 1;

    // 流水线模式
    if (Array.isArray(task.pipeline) && task.pipeline.length >= 2) {
        log(`📅 [${task.id}] 流水线执行第 ${count} 轮 (${task.pipeline.length} 阶段) → ${task.targetIde}:${task.targetPort || '?'}`);

        // 解析 uitty:pid
        let targetIdeName = task.targetIde;
        let targetPid = null;
        if (targetIdeName && targetIdeName.toLowerCase().startsWith('uitty:')) {
            targetPid = Number(targetIdeName.split(':')[1]);
            targetIdeName = 'uitty';
        }

        const cdpPort = schedulerFindCdpPort(task);
        if (!cdpPort) {
            taskCurrentStage.set(task.id, -1);
            return;
        }

        try {
            const result = await executePipelineStages(task, {
                cdpPort,
                targetPid,
                isActive: () => task.__manualTrigger
                    ? schedulerLoadTasks().some(t => t.id === task.id)
                    : activeTimers.has(task.id),
                onStageChange: (idx) => taskCurrentStage.set(task.id, idx),
                log,
                switchModel: async ({ model, stageIndex }) => {
                    await schedulerSwitchModel(cdpPort, task, model);
                    log(`✅ [${task.id}] 阶段 ${stageIndex + 1}: 模型就绪 → ${model}`);
                },
                sendMessage: async ({ message, stage, stageIndex }) => {
                    log(`📅 [${task.id}] 执行阶段 ${stageIndex + 1}/${task.pipeline.length}${stage.model ? ` (模型: ${stage.model})` : ''}: ${String(message || '').substring(0, 50)}...`);
                    await sendMessageToIde(cdpPort, message, task.fixedSessionTitle, targetPid);
                },
                waitForCompletion: async ({ stageIndex }) => {
                    await waitForSchedulerStageCompletion(cdpPort, task, stageIndex);
                    log(`✅ [${task.id}] 阶段 ${stageIndex + 1} 已完成`);
                }
            });

            if (result.cancelled) {
                log(`⚠️ [${task.id}] 流水线已取消，已完成 ${result.completedStages}/${task.pipeline.length} 阶段`);
                return;
            }
            const done = schedulerPersistRunCompletion(task);
            log(`✅ [${task.id}] 流水线第 ${done.completedRuns} 轮全部完成`);
        } catch (e) {
            taskCurrentStage.set(task.id, -1);
            log(`❌ [${task.id}] 流水线失败，已停止后续阶段: ${e.message}`);
        }
        return;
    }

    // 单任务模式（原逻辑）
    log(`📅 [${task.id}] 执行第 ${count} 次 → ${task.targetIde}:${task.targetPort || '?'}: ${task.prompt.substring(0, 50)}...`);

    try {
        const cdpPort = schedulerFindCdpPort(task);
        if (!cdpPort) return;

        let targetPid = null;
        let targetIdeName = task.targetIde;
        if (targetIdeName && targetIdeName.toLowerCase().startsWith('uitty:')) {
            targetPid = Number(targetIdeName.split(':')[1]);
            targetIdeName = 'uitty';
        }

        await sendMessageToIde(cdpPort, task.prompt, task.fixedSessionTitle, targetPid);
        const done = schedulerPersistRunCompletion(task);
        log(`✅ [${task.id}] 执行成功 (第 ${done.completedRuns} 次)`);
    } catch (e) {
        log(`❌ [${task.id}] 执行失败: ${e.message}`);
    }
}

/** 查找任务对应的 CDP 端口（抽取公共逻辑） */
function schedulerFindCdpPort(task) {
    let targetIdeName = task.targetIde;
    let targetPid = null;
    if (targetIdeName && targetIdeName.toLowerCase().startsWith('uitty:')) {
        targetPid = Number(targetIdeName.split(':')[1]);
        targetIdeName = 'uitty';
    }

    let cdpPort = null;
    if (task.targetPort && task.targetPort > 0) {
        if (activeCdpTargets.has(task.targetPort)) {
            const t = activeCdpTargets.get(task.targetPort);
            if (t.appName.toLowerCase() === targetIdeName.toLowerCase()) {
                cdpPort = task.targetPort;
            }
        }
        if (!cdpPort) {
            log(`⚠️ [${task.id}] IDE ${task.targetIde}:${task.targetPort} 不在线，跳过（不会降级到其他实例）`);
            return null;
        }
    } else {
        for (const [port, t] of activeCdpTargets) {
            if (t.appName.toLowerCase() === targetIdeName.toLowerCase()) {
                cdpPort = port;
                break;
            }
        }
        if (!cdpPort) {
            log(`⚠️ [${task.id}] IDE ${task.targetIde} 不在线，跳过`);
            return null;
        }
    }
    return cdpPort;
}

// ─── Cron 解析器 ───────────────────────────────────────────────

function parseCronExpr(expr) {
    const parts = (expr || '').trim().split(/\s+/);
    if (parts.length !== 5) return null;
    try {
        return {
            minutes: parseCronField(parts[0], 0, 59),
            hours: parseCronField(parts[1], 0, 23),
            daysOfMonth: parseCronField(parts[2], 1, 31),
            months: parseCronField(parts[3], 1, 12),
            daysOfWeek: parseCronField(parts[4], 0, 6)
        };
    } catch (e) { return null; }
}

function parseCronField(field, min, max) {
    if (field === '*') return new Set(Array.from({ length: max - min + 1 }, (_, i) => min + i));
    if (field.startsWith('*/')) {
        const step = parseInt(field.slice(2));
        if (!step || step <= 0) throw new Error('bad step');
        const s = new Set();
        for (let i = min; i <= max; i += step) s.add(i);
        return s;
    }
    const s = new Set();
    for (const part of field.split(',')) {
        if (part.includes('-')) {
            const [a, b] = part.split('-').map(Number);
            for (let i = a; i <= b; i++) if (i >= min && i <= max) s.add(i);
        } else {
            const n = parseInt(part);
            if (n >= min && n <= max) s.add(n);
        }
    }
    return s;
}

function cronNextFireTime(spec, fromMs) {
    const d = new Date(fromMs);
    d.setSeconds(0, 0);
    d.setMinutes(d.getMinutes() + 1);
    for (let i = 0; i < 525600; i++) {
        const mon = d.getMonth() + 1;
        const dom = d.getDate();
        const dow = d.getDay(); // 0=Sun
        const hour = d.getHours();
        const min = d.getMinutes();
        if (spec.months.has(mon) && spec.daysOfMonth.has(dom) && spec.daysOfWeek.has(dow)
            && spec.hours.has(hour) && spec.minutes.has(min)) {
            return d.getTime();
        }
        d.setMinutes(d.getMinutes() + 1);
    }
    return fromMs + 3600000;
}

/** 启动时恢复所有持久化任务 */
function schedulerRestoreAll() {
    const tasks = schedulerLoadTasks();
    if (tasks.length === 0) return;
    log(`📅 恢复 ${tasks.length} 个调度任务...`);
    for (const task of tasks) {
        taskExecCounts.set(task.id, getCompletedRuns(task, taskExecCounts));
        if (shouldSkipScheduledRun(task, taskExecCounts)) {
            task.paused = true;
            schedulerSaveTasks(tasks);
            log(`   ⏹️ ${task.targetIde}:${task.targetPort || '?'} — 已达到最大轮次 ${getCompletedRuns(task, taskExecCounts)}/${getMaxRuns(task)}`);
            continue;
        }
        if (task.paused) {
            log(`   ⏸️ ${task.targetIde}:${task.targetPort || '?'} — 已暂停 — "${task.prompt.substring(0, 40)}..."`);
            continue;
        }
        schedulerStartTimer(task);
        const label = task.scheduleType === 'CRON' ? `cron: ${task.cronExpression}` : `每 ${task.intervalMinutes} 分钟`;
        log(`   📅 ${task.targetIde}:${task.targetPort || '?'} — ${label} — "${task.prompt.substring(0, 40)}..."`);
    }
}

// 启动时恢复
setTimeout(schedulerRestoreAll, 3000); // 等扫描完再恢复
