/**
 * 流水线状态机的纯工具函数。
 *
 * 抽离为独立模块以便单元测试（cdp_relay.js 启动 HTTP 服务，不适合直接 require）。
 */

// DONE / ABORT 是 verb（终态动词），不是合法的 pipeline state；
// 进入终态后会保留一段时间，让前端（App）有机会轮询到，再自愈回 IDLE。
const TERMINAL_VERBS = new Set(['DONE', 'ABORT']);

// 终态保留时长：10 秒。期间 /workflow/status 会返回 DONE/ABORT，
// 超过后被访问才自愈为 IDLE，并把原终态记入 lastFinishedState。
const TERMINAL_RETENTION_MS = 10_000;

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

module.exports = { TERMINAL_VERBS, TERMINAL_RETENTION_MS, maybeSelfHealTerminalState };
