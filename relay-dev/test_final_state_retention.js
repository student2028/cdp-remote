/**
 * 终态保留（P0#2）单元测试
 *
 * 验证 maybeSelfHealTerminalState 的行为：
 *   1. IDLE / 非终态 → 不改动
 *   2. DONE/ABORT 停留 < 10 秒 → 保留（App 能轮询到终态）
 *   3. DONE/ABORT 停留 ≥ 10 秒 → 自愈为 IDLE，记录 lastFinishedState/lastFinishedAt
 *
 * 运行: node relay-dev/test_final_state_retention.js
 */

const {
    TERMINAL_VERBS,
    TERMINAL_RETENTION_MS,
    maybeSelfHealTerminalState,
} = require('./workflow_state.js');

let passed = 0;
let failed = 0;

function assert(cond, msg) {
    if (cond) {
        console.log(`  ✅ ${msg}`);
        passed++;
    } else {
        console.log(`  ❌ ${msg}`);
        failed++;
    }
}

function makePipeline(overrides = {}) {
    return {
        name: 'pair_programming',
        state: 'IDLE',
        state_entered_at: Date.now(),
        warned: false,
        lastFinishedState: null,
        lastFinishedAt: null,
        ...overrides,
    };
}

console.log('\n🧪 终态保留单元测试 (P0#2)\n');

// ── 1. 常量健壮性 ──
console.log('1. 常量');
assert(TERMINAL_VERBS.has('DONE'), 'DONE 是终态');
assert(TERMINAL_VERBS.has('ABORT'), 'ABORT 是终态');
assert(!TERMINAL_VERBS.has('IDLE'), 'IDLE 不是终态');
assert(TERMINAL_RETENTION_MS === 10_000, '保留时长为 10 秒');

// ── 2. 非终态：无论停留多久都不应自愈 ──
console.log('\n2. 非终态状态不自愈');
{
    const now = 1_000_000;
    const pl = makePipeline({ state: 'IDLE', state_entered_at: now - 60_000 });
    const healed = maybeSelfHealTerminalState(pl, now);
    assert(healed === false, 'IDLE + 停留 60s → 不自愈');
    assert(pl.state === 'IDLE', '状态仍是 IDLE');
    assert(pl.lastFinishedState === null, 'lastFinishedState 未被污染');
}
{
    const now = 1_000_000;
    const pl = makePipeline({ state: 'WORKER_CODE', state_entered_at: now - 60_000 });
    const healed = maybeSelfHealTerminalState(pl, now);
    assert(healed === false, 'WORKER_CODE + 停留 60s → 不自愈');
    assert(pl.state === 'WORKER_CODE', '状态保持不变');
}

// ── 3. 终态但停留不足 10 秒 → 保留 ──
console.log('\n3. 终态保留窗口内（< 10s）');
for (const verb of ['DONE', 'ABORT']) {
    const enteredAt = 1_000_000;
    const pl = makePipeline({ state: verb, state_entered_at: enteredAt, warned: true });
    const healed = maybeSelfHealTerminalState(pl, enteredAt + 5_000);
    assert(healed === false, `${verb} 停留 5s → 不自愈`);
    assert(pl.state === verb, `${verb} 状态保留`);
    assert(pl.state_entered_at === enteredAt, `${verb} state_entered_at 未变`);
    assert(pl.lastFinishedState === null, `${verb} 未写入 lastFinishedState`);
    assert(pl.warned === true, `${verb} warned 字段不被重置`);
}

// ── 4. 终态停留 ≥ 10 秒 → 自愈 ──
console.log('\n4. 终态自愈窗口外（≥ 10s）');
for (const verb of ['DONE', 'ABORT']) {
    const enteredAt = 1_000_000;
    const now = enteredAt + 10_000;
    const pl = makePipeline({ state: verb, state_entered_at: enteredAt, warned: true });
    const healed = maybeSelfHealTerminalState(pl, now);
    assert(healed === true, `${verb} 停留 10s → 自愈`);
    assert(pl.state === 'IDLE', `${verb} → IDLE`);
    assert(pl.state_entered_at === now, `state_entered_at 重置为 now`);
    assert(pl.lastFinishedState === verb, `lastFinishedState=${verb}`);
    assert(pl.lastFinishedAt === enteredAt, `lastFinishedAt 记录原始进入时间`);
    assert(pl.warned === false, `warned 被清理`);
}

// ── 5. 边界：正好 9999ms 保留，10000ms 自愈 ──
console.log('\n5. 10s 边界');
{
    const enteredAt = 1_000_000;
    const pl = makePipeline({ state: 'DONE', state_entered_at: enteredAt });
    const healed = maybeSelfHealTerminalState(pl, enteredAt + 9_999);
    assert(healed === false, '9999ms → 仍保留');
    assert(pl.state === 'DONE', '状态仍为 DONE');
}
{
    const enteredAt = 1_000_000;
    const pl = makePipeline({ state: 'DONE', state_entered_at: enteredAt });
    const healed = maybeSelfHealTerminalState(pl, enteredAt + 10_000);
    assert(healed === true, '10000ms → 自愈');
    assert(pl.state === 'IDLE', '状态归位 IDLE');
}

// ── 6. 二次调用：已自愈后再调用不应再次触发 ──
console.log('\n6. 幂等性');
{
    const enteredAt = 1_000_000;
    const pl = makePipeline({ state: 'DONE', state_entered_at: enteredAt });
    maybeSelfHealTerminalState(pl, enteredAt + 11_000);
    const snapshot = { ...pl };
    const healedAgain = maybeSelfHealTerminalState(pl, enteredAt + 20_000);
    assert(healedAgain === false, '已是 IDLE → 不再自愈');
    assert(pl.lastFinishedState === snapshot.lastFinishedState, 'lastFinishedState 稳定');
    assert(pl.lastFinishedAt === snapshot.lastFinishedAt, 'lastFinishedAt 稳定');
}

// ── 汇总 ──
console.log(`\n${'═'.repeat(40)}`);
console.log(`✅ 通过: ${passed}  ❌ 失败: ${failed}  总计: ${passed + failed}`);
console.log(`${'═'.repeat(40)}\n`);

process.exit(failed > 0 ? 1 : 0);
