/**
 * 流水线状态机的纯工具函数。
 *
 * 抽离为独立模块以便单元测试（cdp_relay.js 启动 HTTP 服务，不适合直接 require）。
 */

// DONE / ABORT 是 verb（终态动词），不是合法的 pipeline state；
// 进入终态后会保留一段时间，让前端（App）有机会轮询到，再自愈回 IDLE。
const TERMINAL_VERBS = new Set(['DONE', 'ABORT']);

// 终态保留时长：120 秒。期间 /workflow/status 会返回 DONE/ABORT，
// 超过后被访问才自愈为 IDLE，并把原终态记入 lastFinishedState。
// 必须 >= 120s：Brain AI 可能在 DONE 后 40~60 秒才追发 TASK（先 done 后 task 竞态），
// 保留窗口内 TASK 走正式的 DONE→WORKER_CODE 转移路径，不丢 pipeline 上下文。
const TERMINAL_RETENTION_MS = 120_000;

/**
 * 终态延迟自愈：若 pipeline 处于 DONE/ABORT 且已停留 ≥ TERMINAL_RETENTION_MS，
 * 把它归位到 IDLE，并记录 lastFinishedState/lastFinishedAt。
 *
 * 就地修改 pl。返回是否发生了自愈（true = 已归位 IDLE）。
 *
 * 不在终态 / 停留不足时返回 false 且不修改 pl。
 */
function maybeSelfHealTerminalState(pl, now = Date.now()) {
    if (!TERMINAL_VERBS.has(pl.state)) return false;
    const age = now - pl.state_entered_at;
    if (age < TERMINAL_RETENTION_MS) return false;
    pl.lastFinishedState = pl.state;
    pl.lastFinishedAt = pl.state_entered_at;
    pl.state = 'IDLE';
    pl.state_entered_at = now;
    pl.warned = false;
    return true;
}

function hydratePipelineState(saved, { maxEventLog = 30 } = {}) {
    if (!saved || !saved.state || ['IDLE', 'DONE', 'ABORT'].includes(saved.state)) {
        return null;
    }
    return {
        name: saved.name || 'pair_programming',
        state: saved.state,
        state_entered_at: saved.state_entered_at || Date.now(),
        brain: saved.brain || { ide: 'Antigravity', port: null },
        worker: saved.worker || { ide: 'Cursor', port: null },
        cwd: saved.cwd || null,
        initialTask: saved.initialTask || '',
        timeouts: saved.timeouts || { default_ms: 180_000 },
        warned: false,
        lastError: saved.lastError || null,
        lastErrorAt: saved.lastErrorAt || null,
        lastFinishedState: saved.lastFinishedState || null,
        lastFinishedAt: saved.lastFinishedAt || null,
        eventLog: (saved.eventLog || []).slice(-maxEventLog),
        reviewRound: saved.reviewRound || 0,
        minReviewRounds: saved.minReviewRounds || 3,
        lastReviewVerdict: saved.lastReviewVerdict || null,
        lastSeenPipelineHash: saved.lastSeenPipelineHash || null,
        lastSeenMasterHash: saved.lastSeenMasterHash || null,
        currentTaskHash: saved.currentTaskHash || null,
        workerBaselineStatus: saved.workerBaselineStatus || '',
    };
}

function statusInitialTask(state, initialTask) {
    return state === 'IDLE' ? '' : (initialTask || '');
}

module.exports = {
    TERMINAL_VERBS,
    TERMINAL_RETENTION_MS,
    maybeSelfHealTerminalState,
    hydratePipelineState,
    statusInitialTask,
};
