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
const { execSync, exec } = require('child_process');
const os = require('os');
const path = require('path');
const fs = require('fs');
const otaMeta = require(path.join(__dirname, '..', 'ota_meta.js'));
const REPO_ROOT = path.join(__dirname, '..');

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
const CDP_PORT_MIN = parseInt(process.env.CDP_PORT_MIN || '9222');  // 扫描起始
const CDP_PORT_MAX = parseInt(process.env.CDP_PORT_MAX || '9700');  // 扫描结束（含 Codex 9666）
const RELAY_PORT = parseInt(process.env.RELAY_PORT || '19336');
const BIND_ADDR = process.env.BIND_ADDR || '0.0.0.0';
const CHECK_INTERVAL = 10000;
const SCAN_TIMEOUT = 2000;

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

// ─── 多实例目标管理 ───
// Map<port, { appName, appEmoji, pages[], lastSeen }>
const activeCdpTargets = new Map();
let connectionCount = 0;

function detectAppType(pages) {
    for (const p of pages) {
        if (p.title?.includes('Antigravity') || p.url?.includes('Antigravity.app'))
            return { name: 'Antigravity', emoji: '🚀' };
        if (p.title?.includes('Cursor') || p.url?.includes('Cursor.app'))
            return { name: 'Cursor', emoji: '🖱️' };
        if (p.title?.includes('Windsurf') || p.url?.includes('Windsurf.app'))
            return { name: 'Windsurf', emoji: '🏄' };
        if (p.title?.includes('Codex') || p.url?.includes('Codex.app') || p.url?.startsWith('app://'))
            return { name: 'Codex', emoji: '📦' };
        if (p.url?.includes('workbench.html'))
            return { name: 'VS Code', emoji: '💻' };
    }
    return { name: 'Unknown', emoji: '❓' };
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
 */
async function autoLaunchWithCdp({ force = false, port = CDP_PORT, appName = '', cwd = '' } = {}) {
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
            let launchCmd;

            // Codex 默认端口: 使用默认配置目录（首实例）
            if (!running && port === 9666 && app.name === 'Codex') {
                launchCmd = `"${app.binPath}" --remote-debugging-port=${port}`;
                log(`📋 Codex 首实例 (端口 9666)，使用默认配置`);
            } else if (!running && port === 9333) {
                // 9333 首实例：使用默认配置目录（主登录源）
                launchCmd = `"${app.binPath}" --remote-debugging-port=${port}`;
                log(`📋 首实例 (端口 9333)，使用默认配置`);
            } else {
                // 多实例或预制端口：使用独立 user-data-dir
                const defaultDataDir = path.join(os.homedir(), 'Library/Application Support', app.name);
                const userDataDir = path.join(os.homedir(), '.cdp-instances', `${app.name}-${port}`);
                // Antigravity 用 User/ 子目录，Codex 用 Cookies 文件判断
                const hasUserData = fs.existsSync(path.join(userDataDir, 'User')) || fs.existsSync(path.join(userDataDir, 'Cookies'));
                const hasDefaultData = fs.existsSync(path.join(defaultDataDir, 'User')) || fs.existsSync(path.join(defaultDataDir, 'Cookies'));
                if (isPreprovisioned && hasUserData) {
                    log(`📋 使用预制数据目录: ${userDataDir} (端口 ${port})`);
                } else if (!hasUserData && hasDefaultData) {
                    log(`📋 复制用户配置: ${defaultDataDir} → ${userDataDir}`);
                    fs.mkdirSync(userDataDir, { recursive: true });
                    // 复制关键认证文件
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
                launchCmd = `"${app.binPath}" --remote-debugging-port=${port} --user-data-dir="${userDataDir}"`;
            }
            // 添加 Electron 代理启动参数
            const proxyFlag = getElectronProxyFlag();
            if (proxyFlag) {
                launchCmd += ` ${proxyFlag}`;
            }
            if (cwd) {
                launchCmd += ` "${cwd}"`;
            }
            _cachedUserEnv = null; // 每次启动前强制刷新环境变量
            const userEnv = getUserEnv();
            log(`📋 执行: ${launchCmd}`);
            const child = exec(launchCmd, { detached: true, stdio: 'ignore', env: { ...process.env, ...userEnv } });
            if (child.unref) child.unref();  // 脱离父进程

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

// ─── HTTP 代理 ───

const server = http.createServer(async (req, res) => {
    const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS', 'Access-Control-Allow-Headers': '*' };

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
            targets.push({ cdpPort: port, appName: t.appName, appEmoji: t.appEmoji, pages });
        }
        res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
        res.end(JSON.stringify({ targets }));
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

    // ── /kill?port=XXXX (终止指定 CDP 端口的进程) ──
    if (req.url.startsWith('/kill')) {
        const parsed = urlModule.parse(req.url, true);
        const reqPort = parsed.query.port ? parseInt(parsed.query.port) : 0;
        res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
        if (!reqPort) {
            res.end(JSON.stringify({ success: false, error: '缺少 port 参数' }));
            return;
        }
        try {
            const pgrepOut = execSync(`pgrep -f -- "--remote-debugging-port=${reqPort}" 2>/dev/null || true`).toString().trim();
            if (!pgrepOut) {
                res.end(JSON.stringify({ success: false, error: `端口 ${reqPort} 无进程` }));
                return;
            }
            const pids = pgrepOut.split('\n').filter(Boolean);
            log(`🔪 /kill: 终止端口 :${reqPort} 的进程 (PIDs: ${pids.join(', ')})`);
            for (const pid of pids) {
                try { execSync(`kill ${pid}`); } catch (e) { }
            }
            // 从活跃目标中移除
            activeCdpTargets.delete(reqPort);
            res.end(JSON.stringify({ success: true, port: reqPort, killedPids: pids }));
        } catch (e) {
            res.end(JSON.stringify({ success: false, error: e.message }));
        }
        return;
    }

    // ── /launch 支持 ?port=9444&app=Antigravity&cwd=/path/to/project ──
    if (req.url.startsWith('/launch')) {
        const parsed = urlModule.parse(req.url, true);
        const reqPort = parsed.query.port ? parseInt(parsed.query.port) : CDP_PORT;
        const reqApp = parsed.query.app || '';
        const reqCwd = (parsed.query.cwd || '').replace(/[;&|`$(){}!#]/g, ''); // 过滤危险字符
        res.writeHead(200, { 'Content-Type': 'application/json', ...cors });

        // 如果指定了端口且该端口已有实例
        if (activeCdpTargets.has(reqPort)) {
            const t = activeCdpTargets.get(reqPort);
            if (reqCwd) {
                log(`✅ /launch: CDP :${reqPort} 已运行，尝试打开新窗口目录: ${reqCwd}`);
                addCwdHistory(reqCwd, reqApp || t.appName);
                const appInfo = ELECTRON_APPS.find(a => a.name === t.appName);
                if (appInfo) {
                    let extractedUserDataDir = null;
                    try {
                        const pgrepOut = execSync(`pgrep -f -- "--remote-debugging-port=${reqPort}" 2>/dev/null || true`).toString().trim();
                        const pids = pgrepOut.split('\n').filter(Boolean);
                        if (pids.length > 0) {
                            const psOut = execSync(`ps -ww -o command= -p ${pids[0]}`).toString();
                            const match = psOut.match(/--user-data-dir=(["']?)([^"'\s]+)\1/);
                            if (match) extractedUserDataDir = match[2];
                        }
                    } catch (e) {
                        log(`⚠️ 获取 PID 信息失败: ${e.message}`);
                    }

                    let cmd = `"${appInfo.binPath}"`;
                    if (extractedUserDataDir) {
                        cmd += ` --user-data-dir="${extractedUserDataDir}"`;
                    }
                    const pf = getElectronProxyFlag();
                    if (pf) cmd += ` ${pf}`;
                    cmd += ` "${reqCwd}"`;

                    log(`📋 打开目录命令: ${cmd}`);
                    const userEnv = getUserEnv();
                    exec(cmd, { detached: true, stdio: 'ignore', env: { ...process.env, ...userEnv } }).unref();
                }
            } else {
                log(`✅ /launch: CDP :${reqPort} 已运行 (${t.appName})`);
            }
            res.end(JSON.stringify({ launched: true, already_running: true, port: reqPort, appName: t.appName }));
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
        const ok = await autoLaunchWithCdp({ force: true, port: reqPort, appName: reqApp, cwd: reqCwd });
        if (ok) await scanAllCdpPorts();
        res.end(JSON.stringify({ launched: ok, port: reqPort }));
        return;
    }

    // ── CDP HTTP 代理（支持 /cdp/{port}/... 和原始路径） ──
    const { cdpPort, path: cdpPath } = parseCdpPath(req.url);

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
});

// ─── WebSocket 代理（支持路径路由） ───

const wss = new WebSocketServer({ server });

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

server.listen(RELAY_PORT, BIND_ADDR, async () => {
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
    if (e.code === 'EADDRINUSE') log(`❌ 端口 ${RELAY_PORT} 已被占用`);
    else log(`❌ 服务器错误: ${e.message}`);
    process.exit(1);
});

process.on('SIGINT', () => { log('🛑 关闭中...'); wss.close(); server.close(); process.exit(0); });
process.on('SIGTERM', () => { log('🛑 关闭中...'); wss.close(); server.close(); process.exit(0); });
