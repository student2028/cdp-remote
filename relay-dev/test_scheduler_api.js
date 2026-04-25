/**
 * Relay Scheduler API 测试 — 用 curl 模拟请求验证 API 行为。
 *
 * 运行: node relay-dev/test_scheduler_api.js
 * 前提: cdp_relay.js 正在运行
 */

const http = require('http');

const RELAY_HOST = process.env.RELAY_HOST || 'localhost';
const RELAY_PORT = process.env.RELAY_PORT || 19336;
const BASE = `http://${RELAY_HOST}:${RELAY_PORT}`;

let passed = 0;
let failed = 0;
let createdTaskId = null;

function assert(condition, msg) {
    if (condition) {
        console.log(`  ✅ ${msg}`);
        passed++;
    } else {
        console.log(`  ❌ ${msg}`);
        failed++;
    }
}

function httpRequest(method, path, body = null) {
    return new Promise((resolve, reject) => {
        const url = new URL(path, BASE);
        const opts = {
            hostname: url.hostname,
            port: url.port,
            path: url.pathname + url.search,
            method,
            headers: { 'Content-Type': 'application/json' },
            timeout: 5000
        };
        const req = http.request(opts, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                try {
                    resolve({ status: res.statusCode, body: JSON.parse(data) });
                } catch (e) {
                    resolve({ status: res.statusCode, body: data });
                }
            });
        });
        req.on('error', reject);
        req.on('timeout', () => { req.destroy(); reject(new Error('timeout')); });
        if (body) req.write(JSON.stringify(body));
        req.end();
    });
}

async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function runTests() {
    console.log(`\n🧪 Scheduler API 测试 (${BASE})\n`);

    // ── 1. 列出任务 (可能为空) ──
    console.log('1. GET /scheduler (列出任务)');
    try {
        const r = await httpRequest('GET', '/scheduler');
        assert(r.status === 200, `状态码 200, 实际: ${r.status}`);
        assert(Array.isArray(r.body.tasks), 'tasks 是数组');
        console.log(`   当前任务数: ${r.body.tasks.length}`);
    } catch (e) {
        assert(false, `请求失败: ${e.message} — 确认 relay 已启动`);
        console.log('\n❌ Relay 未运行，跳过后续测试\n');
        process.exit(1);
    }

    // ── 2. 创建任务 ──
    console.log('\n2. POST /scheduler (创建任务)');
    {
        const r = await httpRequest('POST', '/scheduler', {
            targetIde: 'Antigravity',
            targetPort: 9333,
            prompt: '这是一个测试任务，请忽略',
            scheduleType: 'INTERVAL',
            intervalMinutes: 999  // 大间隔，避免实际执行
        });
        assert(r.status === 200, `状态码 200, 实际: ${r.status}`);
        assert(r.body.success === true, `success=true`);
        assert(r.body.task && r.body.task.id, `返回了任务 ID: ${r.body.task?.id}`);
        createdTaskId = r.body.task?.id;
    }

    // ── 3. 验证任务出现在列表中 ──
    console.log('\n3. GET /scheduler (验证新任务)');
    {
        const r = await httpRequest('GET', '/scheduler');
        const task = r.body.tasks.find(t => t.id === createdTaskId);
        assert(task != null, `找到新建任务 ${createdTaskId}`);
        assert(task?.targetIde === 'Antigravity', `targetIde=Antigravity`);
        assert(task?.isRunning === true, `isRunning=true`);
        assert(task?.paused === false, `paused=false`);
    }

    // ── 4. 暂停任务 ──
    console.log('\n4. POST /scheduler/pause (暂停任务)');
    {
        const r = await httpRequest('POST', `/scheduler/pause?id=${createdTaskId}`);
        assert(r.status === 200, `状态码 200`);
        assert(r.body.success === true, `success=true`);
        assert(r.body.paused === true, `paused=true`);
    }

    // ── 5. 验证暂停状态 ──
    console.log('\n5. GET /scheduler (验证暂停)');
    {
        const r = await httpRequest('GET', '/scheduler');
        const task = r.body.tasks.find(t => t.id === createdTaskId);
        assert(task?.paused === true, `paused=true (已暂停)`);
        assert(task?.isRunning === false, `isRunning=false (定时器已停)`);
    }

    // ── 6. 恢复任务 ──
    console.log('\n6. POST /scheduler/resume (恢复任务)');
    {
        const r = await httpRequest('POST', `/scheduler/resume?id=${createdTaskId}`);
        assert(r.status === 200, `状态码 200`);
        assert(r.body.success === true, `success=true`);
        assert(r.body.paused === false, `paused=false`);
    }

    // ── 7. 验证恢复状态 ──
    console.log('\n7. GET /scheduler (验证恢复)');
    {
        const r = await httpRequest('GET', '/scheduler');
        const task = r.body.tasks.find(t => t.id === createdTaskId);
        assert(task?.paused === false, `paused=false (已恢复)`);
        assert(task?.isRunning === true, `isRunning=true (定时器重启)`);
    }

    // ── 8. 手动触发 ──
    console.log('\n8. POST /scheduler/trigger (手动触发)');
    {
        const r = await httpRequest('POST', `/scheduler/trigger?id=${createdTaskId}`);
        assert(r.status === 200, `状态码 200`);
        assert(r.body.success === true, `success=true`);
        assert(r.body.triggered === true, `triggered=true`);
    }

    // ── 9. 验证执行计数增加 ──
    console.log('\n9. GET /scheduler (验证执行计数)');
    await sleep(500); // 等异步执行完
    {
        const r = await httpRequest('GET', '/scheduler');
        const task = r.body.tasks.find(t => t.id === createdTaskId);
        assert(task?.executionCount >= 1, `executionCount >= 1, 实际: ${task?.executionCount}`);
    }

    // ── 10. 暂停后触发仍然可用 ──
    console.log('\n10. 暂停后手动触发');
    {
        await httpRequest('POST', `/scheduler/pause?id=${createdTaskId}`);
        const r = await httpRequest('POST', `/scheduler/trigger?id=${createdTaskId}`);
        assert(r.status === 200, `暂停状态下仍可手动触发`);
        assert(r.body.triggered === true, `triggered=true`);
    }

    // ── 11. 错误处理: 无 id 参数 ──
    console.log('\n11. 错误处理');
    {
        const r1 = await httpRequest('POST', '/scheduler/pause');
        assert(r1.status === 400, `pause 无 id → 400, 实际: ${r1.status}`);

        const r2 = await httpRequest('POST', '/scheduler/resume');
        assert(r2.status === 400, `resume 无 id → 400, 实际: ${r2.status}`);

        const r3 = await httpRequest('POST', '/scheduler/trigger');
        assert(r3.status === 400, `trigger 无 id → 400, 实际: ${r3.status}`);

        const r4 = await httpRequest('POST', '/scheduler/trigger?id=nonexistent');
        assert(r4.status === 404, `trigger 不存在的 id → 404, 实际: ${r4.status}`);
    }

    // ── 12. 删除任务 (清理) ──
    console.log('\n12. DELETE /scheduler (删除测试任务)');
    {
        const r = await httpRequest('DELETE', `/scheduler?id=${createdTaskId}`);
        assert(r.status === 200, `状态码 200`);
        assert(r.body.success === true, `success=true`);
    }

    // ── 13. 验证已删除 ──
    console.log('\n13. GET /scheduler (验证已删除)');
    {
        const r = await httpRequest('GET', '/scheduler');
        const task = r.body.tasks.find(t => t.id === createdTaskId);
        assert(task == null, `任务已从列表消失`);
    }

    // ── 汇总 ──
    console.log(`\n${'═'.repeat(40)}`);
    console.log(`✅ 通过: ${passed}  ❌ 失败: ${failed}  总计: ${passed + failed}`);
    console.log(`${'═'.repeat(40)}\n`);

    process.exit(failed > 0 ? 1 : 0);
}

runTests().catch(e => {
    console.error('测试运行失败:', e);
    process.exit(1);
});
