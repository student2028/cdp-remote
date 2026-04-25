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
const { execSync, exec, spawn, execFile } = require('child_process');
const { promisify } = require('util');
const execFilePromise = promisify(execFile);
const os = require('os');
const ENABLE_WATCHDOG = process.env.ENABLE_WATCHDOG !== 'false';
const path = require('path');
const fs = require('fs');
const { parseBrainVerdict, parseBrainTask } = require('./workflow_utils');
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

// ═══════════════════════════════════════════════════════════════
// V2 Workflow 模块：双轨制多 Agent 协同流水线
// 详见 docs/git_driven_agent_workflow.md
// ═══════════════════════════════════════════════════════════════

// 流水线运行时状态（模块级，驻留进程生命周期）
// brain/worker 不再硬编码，由 /workflow/start 时由前端传入；默认值仅作占位。
const PIPELINE_STATE_FILE = path.join(CWD_HISTORY_DIR, 'pipeline_state.json');

/** 从文件恢复上次的流水线状态（Relay 重启后不丢失进行中的流水线） */
function loadPipelineState() {
    try {
        if (fs.existsSync(PIPELINE_STATE_FILE)) {
            const saved = JSON.parse(fs.readFileSync(PIPELINE_STATE_FILE, 'utf8'));
            // 只恢复核心字段，运行时字段用默认值
            if (saved && saved.state && saved.state !== 'IDLE') {
                log(`📂 恢复流水线状态: ${saved.state} (${saved.name || 'pair_programming'})`);
                return {
                    name: saved.name || 'pair_programming',
                    state: saved.state,
                    state_entered_at: saved.state_entered_at || Date.now(),
                    brain:  saved.brain  || { ide: 'Antigravity', port: null },
                    worker: saved.worker || { ide: 'Windsurf',    port: null },
                    cwd: saved.cwd || null,
                    timeouts: saved.timeouts || { default_ms: 180_000 },
                    warned: false,
                    lastError: saved.lastError || null,
                    lastErrorAt: saved.lastErrorAt || null,
                    lastFinishedState: saved.lastFinishedState || null,
                    lastFinishedAt: saved.lastFinishedAt || null,
                    eventLog: (saved.eventLog || []).slice(-MAX_EVENT_LOG),
                    reviewRound: saved.reviewRound || 0,
                    minReviewRounds: saved.minReviewRounds || 3,
                    lastReviewVerdict: saved.lastReviewVerdict || null,
                    lastSeenPipelineHash: saved.lastSeenPipelineHash || null,
                    lastSeenMasterHash: saved.lastSeenMasterHash || null,
                };
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
    worker: { ide: 'Windsurf',    port: null },
    cwd: null,
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
};

function isTransitionAllowed(state, isMainCommit, verb) {
    const rule = WORKFLOW_TRANSITIONS[state];
    if (!rule) return false;
    return isMainCommit ? rule.main : rule.pipeline.includes(verb);
}

// DONE / ABORT 是 verb（终态动词），不是合法的 pipeline state；
// 终态延迟自愈的逻辑抽到 relay-dev/workflow_state.js 便于单元测试。
const { TERMINAL_VERBS, maybeSelfHealTerminalState } = require('./workflow_state.js');

function nextWorkflowState(state, isMainCommit, verb) {
    // 终态自愈交给 /workflow/status 的延迟逻辑处理，这里不干预
    if (TERMINAL_VERBS.has(state)) return state;
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
//   3. 大脑回复自动解析：BRAIN_REVIEW 状态下通过 CDP 读取大脑 IDE 最后回复，
//      解析 VERDICT: PASS|NEEDS_REWORK，自动执行 scripts/orchestra.sh
//
const WATCHDOG_INTERVAL_MS = 15_000;
// 大脑回复解析至少等 30 秒再开始（给 AI 生成时间）
const BRAIN_REPLY_MIN_WAIT_MS = 30_000;
// 超时降级阈值：超过此时间未解析到标记，启用 fallback 模式
const BRAIN_PLAN_FALLBACK_MS = 3 * 60 * 1000;   // BRAIN_PLAN 3 分钟后降级
const BRAIN_REVIEW_FALLBACK_MS = 5 * 60 * 1000;  // BRAIN_REVIEW 5 分钟后降级
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
                    const wbs = pages.filter(p => p.type === 'page' && p.url && p.url.includes('workbench') && !p.url.includes('workbench-jetski'));
                    if (wbs.length === 0) { resolve(''); return; }

                    // 按 cwd basename 匹配 page title（精确定位打开了目标项目的窗口）
                    let wb = wbs[0]; // fallback: 第一个
                    if (cwdHint) {
                        const dirName = cwdHint.split('/').filter(Boolean).pop() || '';
                        const matched = wbs.find(p => p.title && p.title.toLowerCase().includes(dirName.toLowerCase()));
                        if (matched) wb = matched;
                    }

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

    // replaced in workflow_utils.js

/** 执行 scripts/orchestra.sh 命令 */
async function execOrchestra(verb, message, cwd) {
    const scriptPath = path.join(cwd, 'scripts', 'orchestra.sh');
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
                    if (round >= minRounds) {
                        // 通过且轮次足够：完成
                        success = await execOrchestra('done', null, cwd);
                    } else {
                        // 通过但轮次不足：继续审查
                        const reviewMsg = `第${round}轮PASS但轮次不足(${round}/${minRounds})，请更深入审视：性能/并发/回归/安全`;
                        success = await execOrchestra('review', reviewMsg, cwd);
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
            const launchArgs = [`--remote-debugging-port=${port}`];

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
            targets.push({ cdpPort: port, appName: t.appName, appEmoji: t.appEmoji, workspace: getWorkspaceForPort(port), pages });
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
            schedulerExecuteTask(task);
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
        const reqCwd = (parsed.query.cwd || '').replace(/[^a-zA-Z0-9/._\-~ ]/g, ''); // 白名单过滤：仅允许合法路径字符
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

                    const openArgs = [];
                    if (extractedUserDataDir) openArgs.push(`--user-data-dir=${extractedUserDataDir}`);
                    const pf = getElectronProxyFlag();
                    if (pf) openArgs.push(pf);
                    openArgs.push(reqCwd);

                    log(`📋 打开目录命令: open -n -a "${appInfo.appPath}" --args ${openArgs.join(' ')}`);
                    const userEnv = getUserEnv();
                    // 同 autoLaunchWithCdp：走 /usr/bin/open 让 IDE 独立于 relay 的进程组。
                    spawn('/usr/bin/open',
                        ['-n', '-a', appInfo.appPath, '--args', ...openArgs],
                        { detached: true, stdio: 'ignore', env: { ...process.env, ...userEnv } }).unref();
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

    // ── 原生 /cdp/{port}/send 接口，用于跨 IDE 自动发送消息 (Orchestra 引擎) ──
    if (cdpPath === '/send' && req.method === 'POST') {
        let body = '';
        req.on('data', chunk => body += chunk);
        req.on('end', async () => {
            try {
                const { message } = JSON.parse(body);
                if (!message) throw new Error("缺少 'message' 字段");
                if (!activeCdpTargets.has(cdpPort)) throw new Error(`CDP 端口 ${cdpPort} 不可用`);
                await sendMessageToIde(cdpPort, message);
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
        // P0#2: 终态延迟自愈（10秒内保留 DONE/ABORT 供 App 轮询，之后归位 IDLE）
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
    // body: { cwd: "/path/to/repo", filename: "screenshot.png", base64: "..." }
    // 限制：单文件 ≤ 10MB（base64 字符串约 14MB）
    if (req.url === '/workflow/upload_attachment' && req.method === 'POST') {
        const MAX_BODY_LEN = 14 * 1024 * 1024 + 4096; // ~10MB decoded + JSON wrapper
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
                log('⚠️ /workflow/upload_attachment 请求体超过 10MB 上限');
                res.writeHead(413, { 'Content-Type': 'application/json', ...cors });
                res.end(JSON.stringify({ error: '附件超过 10MB 上限' }));
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
                if (decoded.length > 10 * 1024 * 1024) {
                    res.writeHead(413, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({ error: `附件 ${filename} 超过 10MB 上限 (${(decoded.length / 1024 / 1024).toFixed(1)}MB)` }));
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
    //   worker: { ide: "Windsurf" },             // 必填
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
                const brainIde  = parsed.brain?.ide?.trim();
                const workerIde = parsed.worker?.ide?.trim();
                const initialTask = parsed.initial_task?.trim();
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
                if (brainIde.toLowerCase() === workerIde.toLowerCase()) {
                    res.writeHead(400, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({ error: 'brain 和 worker 必须是不同的 IDE' }));
                    return;
                }

                // cwd 必须是 Git 仓库
                if (!fs.existsSync(path.join(cwd, '.git'))) {
                    res.writeHead(400, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({ error: `cwd 不是 Git 仓库: ${cwd}` }));
                    return;
                }

                // 两个 IDE 都必须在线（优先用前端指定的端口，否则按名字查找）
                const brainPort  = (parsed.brain?.port && activeCdpTargets.has(parsed.brain.port))
                    ? parsed.brain.port : getIdePortByName(brainIde);
                const workerPort = (parsed.worker?.port && activeCdpTargets.has(parsed.worker.port))
                    ? parsed.worker.port : getIdePortByName(workerIde);
                if (!brainPort) {
                    res.writeHead(503, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({
                        error: `${brainIde} 未在线`,
                        hint: '请先在主机列表页启动该 IDE',
                        available: [...activeCdpTargets.values()].map(t => t.appName),
                    }));
                    return;
                }
                if (!workerPort) {
                    res.writeHead(503, { 'Content-Type': 'application/json', ...cors });
                    res.end(JSON.stringify({
                        error: `${workerIde} 未在线`,
                        hint: '请先在主机列表页启动该 IDE',
                        available: [...activeCdpTargets.values()].map(t => t.appName),
                    }));
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

                // 先执行 orchestra.sh（确保成功后才更新 currentPipeline，避免竞态）
                // 使用 execAsync 避免阻塞事件循环（withWorkflowLock 保证串行安全）
                const scriptPath = path.join(REPO_ROOT, 'scripts', 'orchestra.sh');

                // 自动安装 reference-transaction Hook（流水线的核心通信机制）
                const hookSrc = path.join(REPO_ROOT, '.git', 'hooks', 'reference-transaction');
                const targetHooksDir = path.join(cwd, '.git', 'hooks');
                const targetHook = path.join(targetHooksDir, 'reference-transaction');
                try {
                    if (fs.existsSync(hookSrc) && fs.existsSync(path.join(cwd, '.git'))) {
                        fs.mkdirSync(targetHooksDir, { recursive: true });
                        if (!fs.existsSync(targetHook)) {
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
                currentPipeline.brain  = { ide: brainIde, port: brainPort };
                currentPipeline.worker = { ide: workerIde, port: workerPort };
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
                currentPipeline.lastSeenPipelineHash = gitRevParse('refs/orchestra/pipeline', cwd);
                currentPipeline.lastSeenMasterHash = gitRevParse('refs/heads/master', cwd) || gitRevParse('refs/heads/main', cwd);
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
                    brain:  { ide: brainIde,  port: brainPort  },
                    worker: { ide: workerIde, port: workerPort },
                    cwd,
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
                    const scriptPath = path.join(REPO_ROOT, 'scripts', 'orchestra.sh');
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
                                await sendMessageToIde(port, prompt);
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
                            `ISSUES:`,
                            `- [blocker|major|minor] <问题> / <为什么> / <建议改法>`,
                            `（没有就写 NONE）`,
                            `SUMMARY: <一句话结论>`,
                            `---`,
                            ``,
                            `判定规则：`,
                            `- 任一 blocker 或 major → VERDICT 必须是 NEEDS_REWORK`,
                            `- 全部 minor 或 NONE → 可以 PASS`,
                            ``,
                            `⚠️ 核心规则（非常重要）：`,
                            `审查完成后，你必须在终端执行命令推进流水线，根据以下逻辑判断：`,
                            ``,
                            `1. 如果 VERDICT 是 NEEDS_REWORK：`,
                            `   执行 scripts/orchestra.sh review "你的 ISSUES 内容"`,
                            ``,
                            `2. 如果 VERDICT 是 PASS，请对照上面的「原始完整需求」检查：`,
                            `   - 所有需求都已实现？→ 执行 scripts/orchestra.sh done`,
                            `   - 还有未实现的需求？→ 执行 scripts/orchestra.sh task "下一个子任务的完整描述"`,
                            `     （把尚未完成的部分作为新任务派发给工人）`,
                            ``,
                            `⚠️ 不要遗漏原始需求的任何一项。DONE 意味着整个需求 100% 完成，不是当前子任务完成。`,
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
                            `3. 分析完成后，在终端执行以下命令派发任务给工人：`,
                            ``,
                            `scripts/orchestra.sh task "<第一步的完整任务描述>"`,
                            ``,
                            `⚠️ 重要规则：`,
                            `- 你必须在终端执行 scripts/orchestra.sh task "任务描述" 来派发任务`,
                            `- 不要自己写代码，你的职责是统筹和拆解`,
                            `- Relay 会通过 Git Hook 自动收到你的指令并推进流水线`,
                            `- 任务描述要完整，包含：目标、技术要求、完成标准`,
                        ].join('\n');
                        await sendWithRetry(port, prompt, 'brain');
                    } else if (verb === 'TASK' || verb === 'REVIEW') {
                        const port = requireIde(pl.worker);
                        if (!port) return;
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
                                    await sendMessageToIde(port, rejectPrompt);
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
});

// 注意：上面的 createServer handler 中所有路由最终都走进了
// CDP proxy 分支（以 /cdp/ 开头），所以不需要额外的 fallback。
// 不以 /cdp/ 开头且未匹配任何已知路由的请求会到达这里之前被 return 拦截。

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
    if (e.code === 'EADDRINUSE') {
        log(`⚠️ 端口 ${RELAY_PORT} 已被占用，可能有另一个relay实例正在运行`);
        // 检查是否有其他relay进程在运行
        const existingPid = findExistingRelayProcess();
        if (existingPid) {
            log(`ℹ️ 发现现有relay进程正在运行: PID ${existingPid}`);
            // 不要退出进程，继续运行并尝试处理
        } else {
            log(`❌ 端口被未知进程占用，无法继续`);
            process.exit(1);
        }
    } else {
        log(`❌ 服务器错误: ${e.message}`);
        // 对于其他严重错误，仍然退出
        process.exit(1);
    }
});

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

// ═══════════════════════════════════════════════════════════════
// ── 公共 CDP 消息发送工具 ──────────────────────────────────────
// ═══════════════════════════════════════════════════════════════

const CDP_TIMEOUT_MS = 8000;

/** 查找输入框并聚焦的 JS 表达式（各 IDE 通用） */
const FOCUS_INPUT_EXPR = `(function(){var ed=null;var ch=document.getElementById('chat');if(ch)ed=ch.querySelector('[contenteditable="true"]');if(!ed){var p=document.getElementById('windsurf.cascadePanel');if(p)ed=p.querySelector('[contenteditable="true"]');}if(!ed){var ces=document.querySelectorAll('div[contenteditable="true"]');for(var i=0;i<ces.length;i++){var e=ces[i];if(!e.offsetParent)continue;var c=e.className||'';var ro=e.getAttribute('role')||'';if(ro==='textbox'||c.indexOf('min-h-')>=0||c.indexOf('outline-none')>=0||c.indexOf('ProseMirror')>=0){ed=e;break;}}}if(!ed)return'no-input';ed.focus();ed.textContent='';return'ok';})()`;

/**
 * 通过 CDP WebSocket 向指定端口的 IDE 发送消息。
 * 被 /cdp/{port}/send 路由和调度引擎共用。
 *
 * @param {number} cdpPort - 目标 IDE 的 CDP 调试端口
 * @param {string} message - 要发送的消息文本
 * @throws {Error} 当目标不可用、无 workbench、或 CDP 操作失败时抛出
 */
async function sendMessageToIde(cdpPort, message, fixedSessionTitle = null) {
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

    const wb = pages.find(p => p.type === 'page' && p.url && p.url.includes('workbench') && !p.url.includes('workbench-jetski'));
    if (!wb) throw new Error('未找到 workbench 页面');

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
    return saved.map(t => ({
        ...t,
        isRunning: activeTimers.has(t.id),
        paused: t.paused || false,
        executionCount: taskExecCounts.get(t.id) || 0
    }));
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
    tasks = tasks.filter(t => t.id !== task.id);
    const savedTask = {
        id: task.id,
        targetIde: task.targetIde,
        targetPort: task.targetPort || 0,
        fixedSessionTitle: task.fixedSessionTitle || '',
        prompt: task.prompt,
        scheduleType: task.scheduleType,
        intervalMinutes: task.intervalMinutes,
        cronExpression: task.cronExpression || '',
        paused: false,
        createdAt: task.createdAt || new Date().toISOString()
    };
    tasks.push(savedTask);
    schedulerSaveTasks(tasks);

    // 启动定时器
    schedulerStartTimer(savedTask);
    taskExecCounts.set(task.id, 0);

    log(`📅 调度任务已创建: ${task.id} → ${task.targetIde} (${task.scheduleType === 'CRON' ? 'cron: ' + task.cronExpression : '每 ' + task.intervalMinutes + ' 分钟'})`);
    return { success: true, task: { ...savedTask, isRunning: true, executionCount: 0 } };
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

/** 启动单个任务的定时器 */
function schedulerStartTimer(task) {
    if (task.scheduleType === 'CRON') {
        schedulerStartCronTimer(task);
    } else {
        const ms = (task.intervalMinutes || 5) * 60 * 1000;
        const timer = setInterval(() => schedulerExecuteTask(task), ms);
        activeTimers.set(task.id, { timer, config: task, type: 'interval' });
    }
}

/** 停止定时器 */
function schedulerStopTimer(taskId) {
    const entry = activeTimers.get(taskId);
    if (entry) {
        if (entry.type === 'interval') clearInterval(entry.timer);
        else clearTimeout(entry.timer);
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
        const timer = setTimeout(() => {
            // 执行前再次检查任务是否仍然活跃
            if (!activeTimers.has(task.id)) return;
            schedulerExecuteTask(task);
            // 60s 后再计算下一次（避免同分钟重复触发），用追踪的 timer 替换
            const cooldownTimer = setTimeout(() => scheduleNext(), 60000);
            activeTimers.set(task.id, { timer: cooldownTimer, config: task, type: 'cron' });
        }, delay);
        activeTimers.set(task.id, { timer, config: task, type: 'cron' });
    }
    scheduleNext();
}

/** 执行任务：通过 sendMessageToIde 发消息给目标 IDE */
async function schedulerExecuteTask(task) {
    const count = (taskExecCounts.get(task.id) || 0) + 1;
    taskExecCounts.set(task.id, count);
    log(`📅 [${task.id}] 执行第 ${count} 次 → ${task.targetIde}: ${task.prompt.substring(0, 50)}...`);

    try {
        // 找目标 IDE 的 CDP 端口
        let cdpPort = null;
        for (const [port, t] of activeCdpTargets) {
            if (t.appName.toLowerCase() === task.targetIde.toLowerCase()) {
                cdpPort = port;
                break;
            }
        }
        if (!cdpPort) {
            log(`⚠️ [${task.id}] IDE ${task.targetIde} 不在线，跳过`);
            return;
        }

        await sendMessageToIde(cdpPort, task.prompt, task.fixedSessionTitle);
        log(`✅ [${task.id}] 执行成功 (第 ${count} 次)`);
    } catch (e) {
        log(`❌ [${task.id}] 执行失败: ${e.message}`);
    }
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
        taskExecCounts.set(task.id, 0);
        if (task.paused) {
            log(`   ⏸️ ${task.targetIde} — 已暂停 — "${task.prompt.substring(0, 40)}..."`);
            continue;
        }
        schedulerStartTimer(task);
        const label = task.scheduleType === 'CRON' ? `cron: ${task.cronExpression}` : `每 ${task.intervalMinutes} 分钟`;
        log(`   📅 ${task.targetIde} — ${label} — "${task.prompt.substring(0, 40)}..."`);
    }
}

// 启动时恢复
setTimeout(schedulerRestoreAll, 3000); // 等扫描完再恢复
