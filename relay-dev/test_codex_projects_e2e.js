#!/usr/bin/env node
/**
 * Codex 项目管理 - 端到端真实操作测试 v2
 * 用修复后的逻辑：
 * - getCurrentProject: 三层检测（顶部栏 → composer底部 → sidebar展开项）
 * - switchProject: 展开项目 + 点击第一个聊天
 * - 空字符串防护
 */
const WebSocket = require('ws');
const http = require('http');
const fs = require('fs');
const path = require('path');

const CDP_PORT = 9666;
const RESULTS_DIR = '/tmp/codex_test_results_v2';
let msgId = 1;
let ws;
let passed = 0;
let failed = 0;
let warnings = 0;

function getMainPageWsUrl() {
    return new Promise((resolve, reject) => {
        http.get(`http://127.0.0.1:${CDP_PORT}/json`, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                const pages = JSON.parse(data);
                const main = pages.find(p => p.type === 'page' && p.url.includes('index.html'));
                if (main) resolve(main.webSocketDebuggerUrl);
                else reject(new Error('未找到 Codex 主页面'));
            });
        }).on('error', reject);
    });
}

function cdpCall(method, params = {}, timeout = 8000) {
    return new Promise((resolve, reject) => {
        const id = msgId++;
        const timer = setTimeout(() => reject(new Error(`CDP ${method} 超时`)), timeout);
        const handler = (raw) => {
            const msg = JSON.parse(raw);
            if (msg.id === id) {
                clearTimeout(timer);
                ws.removeListener('message', handler);
                if (msg.error) reject(new Error(msg.error.message));
                else resolve(msg.result);
            }
        };
        ws.on('message', handler);
        ws.send(JSON.stringify({ id, method, params }));
    });
}

async function cdpEval(expression, timeout = 8000) {
    const result = await cdpCall('Runtime.evaluate', {
        expression, returnByValue: true, awaitPromise: true
    }, timeout);
    return result?.result?.value;
}

async function screenshot(name) {
    const result = await cdpCall('Page.captureScreenshot', { format: 'png', quality: 80 });
    const buf = Buffer.from(result.data, 'base64');
    const filePath = path.join(RESULTS_DIR, `${name}.png`);
    fs.writeFileSync(filePath, buf);
    console.log(`  📸 截图: ${name}.png (${(buf.length/1024).toFixed(1)}KB)`);
    return filePath;
}

async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

function assert(condition, msg) {
    if (condition) { console.log(`  ✅ PASS: ${msg}`); passed++; }
    else { console.log(`  ❌ FAIL: ${msg}`); failed++; }
}
function warn(msg) { console.log(`  ⚠️  WARN: ${msg}`); warnings++; }

// ─── 和 CodexCommands.kt 完全一致的 CDP 命令 ───

async function listProjects() {
    const raw = await cdpEval(`
        (function() {
            try {
                var projects = [];
                var btns = document.querySelectorAll('button[aria-label^="Start new chat in "]');
                for (var i = 0; i < btns.length; i++) {
                    var name = (btns[i].getAttribute('aria-label') || '').replace('Start new chat in ', '');
                    if (name) projects.push(name);
                }
                
                var topBtns = document.querySelectorAll('button[type="button"]');
                var current = '';
                for (var j = 0; j < topBtns.length; j++) {
                    var r = topBtns[j].getBoundingClientRect();
                    if (r.y > 0 && r.y < 30 && r.height < 40) {
                        var t = (topBtns[j].textContent || '').trim();
                        var a = topBtns[j].getAttribute('aria-label') || '';
                        if (t && !a && projects.indexOf(t) >= 0) { current = t; break; }
                    }
                }
                
                if (!current) {
                    var allBtns = document.querySelectorAll('button');
                    for (var k = 0; k < allBtns.length; k++) {
                        var r2 = allBtns[k].getBoundingClientRect();
                        if (r2.y > 800 && r2.y < 870) {
                            var t2 = (allBtns[k].textContent || '').trim();
                            if (projects.indexOf(t2) >= 0) { current = t2; break; }
                        }
                    }
                }
                
                var expanded = {};
                var roleBtns = document.querySelectorAll('[role="button"][aria-expanded]');
                for (var m = 0; m < roleBtns.length; m++) {
                    var a2 = (roleBtns[m].getAttribute('aria-label') || '');
                    if (a2) expanded[a2] = roleBtns[m].getAttribute('aria-expanded') === 'true';
                }
                
                return JSON.stringify({projects, current, expanded});
            } catch(e) { return JSON.stringify({projects:[], current:'', expanded:{}, error:e.message}); }
        })()
    `);
    return JSON.parse(raw);
}

async function getCurrentProject() {
    return await cdpEval(`
        (function() {
            var projects = [];
            var btns = document.querySelectorAll('button[aria-label^="Start new chat in "]');
            for (var i = 0; i < btns.length; i++) {
                var name = (btns[i].getAttribute('aria-label') || '').replace('Start new chat in ', '');
                if (name) projects.push(name);
            }
            
            var topBtns = document.querySelectorAll('button[type="button"]');
            for (var j = 0; j < topBtns.length; j++) {
                var r = topBtns[j].getBoundingClientRect();
                if (r.y > 0 && r.y < 30 && r.height < 40) {
                    var t = (topBtns[j].textContent || '').trim();
                    var aria = topBtns[j].getAttribute('aria-label') || '';
                    if (t && !aria && projects.indexOf(t) >= 0) return t;
                }
            }
            
            var allBtns = document.querySelectorAll('button');
            for (var k = 0; k < allBtns.length; k++) {
                var r2 = allBtns[k].getBoundingClientRect();
                if (r2.y > 800 && r2.y < 870) {
                    var t2 = (allBtns[k].textContent || '').trim();
                    if (projects.indexOf(t2) >= 0) return t2;
                }
            }
            
            var roleBtns = document.querySelectorAll('[role="button"][aria-expanded="true"]');
            for (var m = 0; m < roleBtns.length; m++) {
                var a = (roleBtns[m].getAttribute('aria-label') || '');
                if (a && projects.indexOf(a) >= 0) return a;
            }
            return '';
        })()
    `);
}

async function switchProject(name) {
    if (!name) return 'invalid';
    const escaped = name.replace(/'/g, "\\'").replace(/"/g, '\\"');
    return await cdpEval(`
        (function() {
            var roleBtns = document.querySelectorAll('[role="button"][aria-expanded]');
            var projectEl = null;
            for (var i = 0; i < roleBtns.length; i++) {
                if ((roleBtns[i].getAttribute('aria-label') || '') === '${escaped}') {
                    projectEl = roleBtns[i]; break;
                }
            }
            if (!projectEl) return 'not-found';
            
            if (projectEl.getAttribute('aria-expanded') !== 'true') {
                projectEl.click();
            }
            
            var parent = projectEl.parentElement;
            if (!parent) return 'expanded-only';
            
            var chatEls = parent.querySelectorAll('[role="button"]');
            for (var j = 0; j < chatEls.length; j++) {
                var el = chatEls[j];
                if (el === projectEl) continue;
                if (el.querySelector('.truncate.select-none')) {
                    el.click();
                    return 'switched';
                }
            }
            
            var newChatBtn = document.querySelector('button[aria-label="Start new chat in ${escaped}"]');
            if (newChatBtn) { newChatBtn.click(); return 'new-chat'; }
            return 'expanded-only';
        })()
    `);
}

async function startNewChatInProject(name) {
    if (!name) return 'invalid';
    const escaped = name.replace(/'/g, "\\'").replace(/"/g, '\\"');
    return await cdpEval(`
        (function() {
            var btn = document.querySelector('button[aria-label="Start new chat in ${escaped}"]');
            if (btn) { btn.click(); return 'clicked'; }
            return 'not-found';
        })()
    `);
}

async function getProjectChats(name) {
    if (!name) return [];
    const escaped = name.replace(/'/g, "\\'").replace(/"/g, '\\"');
    const raw = await cdpEval(`
        (function() {
            try {
                var roleBtns = document.querySelectorAll('[role="button"][aria-expanded]');
                var projectEl = null;
                for (var i = 0; i < roleBtns.length; i++) {
                    if ((roleBtns[i].getAttribute('aria-label') || '') === '${escaped}') {
                        projectEl = roleBtns[i]; break;
                    }
                }
                if (!projectEl) return JSON.stringify([]);
                var parent = projectEl.parentElement;
                if (!parent) return JSON.stringify([]);
                var chats = [];
                var spans = parent.querySelectorAll('.truncate.select-none');
                for (var j = 0; j < spans.length; j++) {
                    var t = (spans[j].textContent || '').trim();
                    if (t) chats.push(t);
                }
                return JSON.stringify(chats);
            } catch(e) { return JSON.stringify([]); }
        })()
    `);
    return JSON.parse(raw);
}

// ═══════════════════════════════════════════════
async function runTests() {
    fs.mkdirSync(RESULTS_DIR, { recursive: true });
    console.log('🔌 连接 Codex CDP...');
    const wsUrl = await getMainPageWsUrl();
    ws = new WebSocket(wsUrl);
    await new Promise((resolve, reject) => { ws.on('open', resolve); ws.on('error', reject); });
    console.log('✅ WebSocket 已连接\n');

    // ═══ Phase 1: 初始状态 ═══
    console.log('════════ Phase 1: 初始状态 ════════');
    await screenshot('01_initial');
    
    const data = await listProjects();
    console.log(`  项目: [${data.projects.join(', ')}]`);
    console.log(`  当前: "${data.current}"`);
    console.log(`  展开状态:`, JSON.stringify(data.expanded));
    
    assert(data.projects.length > 0, `找到 ${data.projects.length} 个项目`);
    
    const current = await getCurrentProject();
    console.log(`  getCurrentProject: "${current}"`);
    assert(current && current.length > 0, `getCurrentProject 有结果: "${current}"`);
    assert(data.current === current, `listProjects.current="${data.current}" === getCurrentProject="${current}"`);
    
    // 记录初始聊天数
    const initialChats = await getProjectChats(current);
    console.log(`  ${current} 有 ${initialChats.length} 个聊天: [${initialChats.join(', ')}]`);
    const initialProject = current;
    console.log();

    // ═══ Phase 2: 切换到另一个项目 ═══
    console.log('════════ Phase 2: 切换项目 ════════');
    const otherProject = data.projects.find(p => p !== initialProject);
    if (!otherProject) {
        warn('只有一个项目，跳过切换测试');
    } else {
        console.log(`  从 "${initialProject}" → "${otherProject}"`);
        const sw = await switchProject(otherProject);
        console.log(`  switchProject 返回: "${sw}"`);
        assert(sw === 'switched' || sw === 'new-chat', `switchProject 返回 switched/new-chat (实际: ${sw})`);
        
        await sleep(1000);
        await screenshot('02_after_switch');
        
        const afterSwitch = await getCurrentProject();
        console.log(`  切换后 getCurrentProject: "${afterSwitch}"`);
        assert(afterSwitch === otherProject, `当前项目 = "${otherProject}" (实际: "${afterSwitch}")`);
        
        const afterData = await listProjects();
        console.log(`  切换后 listProjects.current: "${afterData.current}"`);
        assert(afterData.current === otherProject, `listProjects.current = "${otherProject}" (实际: "${afterData.current}")`);
    }
    console.log();

    // ═══ Phase 3: 新建聊天 ═══
    console.log('════════ Phase 3: 新建聊天 ════════');
    const targetProject = otherProject || initialProject;
    const beforeChats = await getProjectChats(targetProject);
    console.log(`  ${targetProject} 当前 ${beforeChats.length} 个聊天`);
    
    const newChatResult = await startNewChatInProject(targetProject);
    assert(newChatResult === 'clicked', `startNewChatInProject 返回 clicked (实际: ${newChatResult})`);
    
    await sleep(1000);
    await screenshot('03_new_chat');
    
    const afterNewChat = await getCurrentProject();
    console.log(`  新建聊天后 getCurrentProject: "${afterNewChat}"`);
    assert(afterNewChat === targetProject, `新聊天在 "${targetProject}" (实际: "${afterNewChat}")`);
    console.log();

    // ═══ Phase 4: 切回原项目 ═══
    console.log('════════ Phase 4: 切回原项目 ════════');
    if (otherProject) {
        const swBack = await switchProject(initialProject);
        console.log(`  switchProject("${initialProject}") 返回: "${swBack}"`);
        assert(swBack === 'switched' || swBack === 'new-chat', `切回返回 switched/new-chat (实际: ${swBack})`);
        
        await sleep(1000);
        await screenshot('04_switch_back');
        
        const backProject = await getCurrentProject();
        console.log(`  切回后 getCurrentProject: "${backProject}"`);
        assert(backProject === initialProject, `已切回 "${initialProject}" (实际: "${backProject}")`);
        
        // 原项目聊天数不变
        const backChats = await getProjectChats(initialProject);
        assert(backChats.length === initialChats.length, 
            `聊天数不变: ${backChats.length} (期望: ${initialChats.length})`);
    }
    console.log();

    // ═══ Phase 5: 边界测试 ═══
    console.log('════════ Phase 5: 边界测试 ════════');
    
    // 空字符串
    const emptyResult = await switchProject('');
    assert(emptyResult === 'invalid', `空字符串返回 invalid (实际: ${emptyResult})`);
    
    // 不存在的项目
    const badResult = await switchProject('不存在的项目_xyzzy_999');
    assert(badResult === 'not-found', `不存在的项目返回 not-found (实际: ${badResult})`);
    
    // 不存在项目的新建聊天
    const badChat = await startNewChatInProject('不存在的项目_xyzzy_999');
    assert(badChat === 'not-found', `不存在的项目新建聊天返回 not-found (实际: ${badChat})`);
    
    // 空字符串新建聊天
    const emptyChat = await startNewChatInProject('');
    assert(emptyChat === 'invalid', `空字符串新建聊天返回 invalid (实际: ${emptyChat})`);
    
    // 特殊字符
    const specialResult = await switchProject("test'project\"<>&");
    assert(specialResult === 'not-found', `特殊字符项目名返回 not-found (实际: ${specialResult})`);
    
    // 边界操作后状态不变
    const afterEdge = await getCurrentProject();
    assert(afterEdge.length > 0, `边界操作后状态完好: "${afterEdge}"`);
    
    // 空项目的聊天列表
    const emptyChats = await getProjectChats('不存在的项目');
    assert(Array.isArray(emptyChats) && emptyChats.length === 0, '不存在的项目返回空聊天列表');
    
    await screenshot('05_after_edge_cases');
    console.log();

    // ═══ Phase 6: 恢复并验证最终状态 ═══
    console.log('════════ Phase 6: 最终状态 ════════');
    if (initialProject) {
        await switchProject(initialProject);
        await sleep(500);
    }
    const finalProject = await getCurrentProject();
    const finalData = await listProjects();
    console.log(`  最终项目: "${finalProject}"`);
    console.log(`  最终列表: [${finalData.projects.join(', ')}]`);
    await screenshot('06_final');
    console.log();

    // ═══ 总结 ═══
    console.log('═══════════════════════════════════════');
    console.log(`📊 测试结果: ${passed} PASS / ${failed} FAIL / ${warnings} WARN`);
    console.log(`📁 截图: ${RESULTS_DIR}/`);
    console.log('═══════════════════════════════════════');
    
    if (failed > 0) {
        console.log('\n❌ 有测试失败！');
    } else {
        console.log('\n🎉 全部通过！伟大的作品！');
    }

    ws.close();
    process.exit(failed > 0 ? 1 : 0);
}

runTests().catch(e => {
    console.error('❌ 崩溃:', e.message, e.stack);
    process.exit(1);
});
