#!/usr/bin/env node
/**
 * 测试 Codex 项目管理 CDP 命令
 * 验证 listProjects / getCurrentProject / switchProject / startNewChatInProject / addNewProject
 */
const WebSocket = require('ws');
const http = require('http');

const CDP_PORT = 9666;

// ─── 获取 Codex 主页面 wsUrl ───
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

// ─── CDP 评估 ───
let msgId = 1;
function cdpEval(ws, expression, timeout = 5000) {
    return new Promise((resolve, reject) => {
        const id = msgId++;
        const timer = setTimeout(() => reject(new Error('CDP 评估超时')), timeout);
        const handler = (raw) => {
            const msg = JSON.parse(raw);
            if (msg.id === id) {
                clearTimeout(timer);
                ws.removeListener('message', handler);
                if (msg.error) reject(new Error(msg.error.message));
                else resolve(msg.result?.result?.value);
            }
        };
        ws.on('message', handler);
        ws.send(JSON.stringify({
            id,
            method: 'Runtime.evaluate',
            params: { expression, returnByValue: true }
        }));
    });
}

// ─── 测试 ───
async function runTests() {
    console.log('🔌 连接 Codex CDP...');
    const wsUrl = await getMainPageWsUrl();
    console.log(`  wsUrl: ${wsUrl}`);

    const ws = new WebSocket(wsUrl);
    await new Promise((resolve, reject) => {
        ws.on('open', resolve);
        ws.on('error', reject);
    });
    console.log('✅ 已连接\n');

    // ═══════ 测试 1: listProjects ═══════
    console.log('═══ 测试 1: listProjects ═══');
    const listResult = await cdpEval(ws, `
        (function() {
            try {
                var projects = [];
                var btns = document.querySelectorAll('button[aria-label^="Start new chat in "]');
                for (var i = 0; i < btns.length; i++) {
                    var aria = btns[i].getAttribute('aria-label') || '';
                    var name = aria.replace('Start new chat in ', '');
                    if (name) projects.push(name);
                }
                var topBtns = document.querySelectorAll('main button');
                var current = '';
                for (var j = 0; j < topBtns.length; j++) {
                    var t = (topBtns[j].textContent || '').trim();
                    if (projects.indexOf(t) >= 0 && topBtns[j].getBoundingClientRect().y < 40) {
                        current = t; break;
                    }
                }
                return JSON.stringify({projects: projects, current: current});
            } catch(e) { return JSON.stringify({projects: [], current: '', error: e.message}); }
        })()
    `);
    const listData = JSON.parse(listResult);
    console.log(`  找到 ${listData.projects.length} 个项目:`);
    listData.projects.forEach((p, i) => {
        const marker = p === listData.current ? ' ← 当前' : '';
        console.log(`    [${i}] ${p}${marker}`);
    });
    console.log(`  当前项目: "${listData.current}"\n`);

    // ═══════ 测试 2: getCurrentProject ═══════
    console.log('═══ 测试 2: getCurrentProject ═══');
    const currentResult = await cdpEval(ws, `
        (function() {
            var mainBtns = document.querySelectorAll('main button[type="button"]');
            for (var i = 0; i < mainBtns.length; i++) {
                var r = mainBtns[i].getBoundingClientRect();
                if (r.y < 40 && r.height < 30 && r.width > 40) {
                    var t = (mainBtns[i].textContent || '').trim();
                    if (t && t.length < 60) return t;
                }
            }
            return '';
        })()
    `);
    console.log(`  当前项目 (顶部栏): "${currentResult}"\n`);

    // ═══════ 测试 3: 验证 "Add new project" 按钮存在 ═══════
    console.log('═══ 测试 3: "Add new project" 按钮 ═══');
    const addBtnResult = await cdpEval(ws, `
        (function() {
            var btn = document.querySelector('button[aria-label="Add new project"]');
            if (btn) {
                var r = btn.getBoundingClientRect();
                return JSON.stringify({found: true, x: r.x, y: r.y, w: r.width, h: r.height, visible: !!btn.offsetParent});
            }
            return JSON.stringify({found: false});
        })()
    `);
    const addBtnData = JSON.parse(addBtnResult);
    if (addBtnData.found) {
        console.log(`  ✅ 找到按钮 pos=(${addBtnData.x},${addBtnData.y}) size=${addBtnData.w}x${addBtnData.h} visible=${addBtnData.visible}`);
    } else {
        console.log(`  ❌ 未找到 "Add new project" 按钮`);
    }
    console.log();

    // ═══════ 测试 4: 验证每个项目的 "Start new chat" 和 "Project actions" 按钮 ═══════
    console.log('═══ 测试 4: 各项目操作按钮 ═══');
    for (const projectName of listData.projects) {
        const btnResult = await cdpEval(ws, `
            (function() {
                var newChat = document.querySelector('button[aria-label="Start new chat in ${projectName.replace(/'/g, "\\'")}"]');
                var actions = document.querySelector('button[aria-label="Project actions for ${projectName.replace(/'/g, "\\'")}"]');
                return JSON.stringify({
                    name: '${projectName.replace(/'/g, "\\'")}',
                    newChatBtn: !!newChat,
                    actionsBtn: !!actions
                });
            })()
        `);
        const btnData = JSON.parse(btnResult);
        const newChatIcon = btnData.newChatBtn ? '✅' : '❌';
        const actionsIcon = btnData.actionsBtn ? '✅' : '❌';
        console.log(`  📁 ${btnData.name}: newChat=${newChatIcon} actions=${actionsIcon}`);
    }
    console.log();

    // ═══════ 测试 5: switchProject (验证点击逻辑，不实际切换) ═══════
    console.log('═══ 测试 5: switchProject 查找验证 ═══');
    if (listData.projects.length > 0) {
        const testProject = listData.projects[0];
        const switchResult = await cdpEval(ws, `
            (function() {
                var items = document.querySelectorAll('[role="button"]');
                var found = [];
                for (var i = 0; i < items.length; i++) {
                    var aria = (items[i].getAttribute('aria-label') || '');
                    if (aria === '${testProject.replace(/'/g, "\\'")}') {
                        var r = items[i].getBoundingClientRect();
                        found.push({aria: aria, x: r.x, y: r.y, w: r.width, h: r.height});
                    }
                }
                // 回退匹配
                for (var i = 0; i < items.length; i++) {
                    var t = (items[i].textContent || '').trim();
                    if (t.indexOf('${testProject.replace(/'/g, "\\'")}') === 0 && items[i].getBoundingClientRect().x < 300) {
                        var r = items[i].getBoundingClientRect();
                        found.push({text: t, x: r.x, y: r.y, w: r.width, h: r.height});
                    }
                }
                return JSON.stringify({project: '${testProject.replace(/'/g, "\\'")}', matches: found});
            })()
        `);
        const switchData = JSON.parse(switchResult);
        console.log(`  项目 "${switchData.project}" 在 DOM 中的匹配:`);
        switchData.matches.forEach((m, i) => {
            console.log(`    [${i}] ${m.aria ? 'aria="'+m.aria+'"' : 'text="'+m.text+'"'} pos=(${m.x},${m.y}) size=${m.w}x${m.h}`);
        });
        if (switchData.matches.length === 0) console.log('    ❌ 无匹配');
    }
    console.log();

    // ═══════ 测试 6: getProjectChats ═══════
    console.log('═══ 测试 6: getProjectChats ═══');
    if (listData.projects.length > 0) {
        const testProject = listData.projects[0];
        const chatsResult = await cdpEval(ws, `
            (function() {
                try {
                    var items = document.querySelectorAll('[role="button"]');
                    var projectEl = null;
                    for (var i = 0; i < items.length; i++) {
                        var aria = (items[i].getAttribute('aria-label') || '');
                        if (aria === '${testProject.replace(/'/g, "\\'")}') { projectEl = items[i]; break; }
                    }
                    if (!projectEl) return JSON.stringify({error: 'project not found'});
                    var parent = projectEl.parentElement;
                    if (!parent) return JSON.stringify({error: 'no parent'});
                    var chats = [];
                    var spans = parent.querySelectorAll('.truncate.select-none');
                    for (var j = 0; j < spans.length; j++) {
                        var t = (spans[j].textContent || '').trim();
                        if (t) chats.push(t);
                    }
                    return JSON.stringify({project: '${testProject.replace(/'/g, "\\'")}', chats: chats});
                } catch(e) { return JSON.stringify({error: e.message}); }
            })()
        `);
        const chatsData = JSON.parse(chatsResult);
        if (chatsData.error) {
            console.log(`  ❌ ${chatsData.error}`);
        } else {
            console.log(`  📁 ${chatsData.project} 的聊天列表 (${chatsData.chats.length} 个):`);
            chatsData.chats.forEach((c, i) => {
                console.log(`    [${i}] ${c}`);
            });
        }
    }
    console.log();

    // ═══════ 测试 7: 模型按钮验证 ═══════
    console.log('═══ 测试 7: 模型选择按钮 ═══');
    const modelResult = await cdpEval(ws, `
        (function(){
            var btns = document.querySelectorAll('button[aria-haspopup="menu"]');
            var found = [];
            for (var i = 0; i < btns.length; i++) {
                if (!btns[i].offsetParent) continue;
                var t = (btns[i].textContent || '').toLowerCase();
                if (t.indexOf('5.') >= 0 || t.indexOf('gpt') >= 0 || t.indexOf('high') >= 0 || t.indexOf('low') >= 0 || t.indexOf('medium') >= 0) {
                    var r = btns[i].getBoundingClientRect();
                    found.push({text: btns[i].textContent.replace(/\\s+/g, ' ').trim(), x: r.x, y: r.y});
                }
            }
            return JSON.stringify(found);
        })()
    `);
    const modelData = JSON.parse(modelResult);
    if (modelData.length > 0) {
        modelData.forEach(m => console.log(`  ✅ 模型按钮: "${m.text}" pos=(${m.x},${m.y})`));
    } else {
        console.log('  ❌ 未找到模型按钮');
    }

    console.log('\n═══════════════════════════════════');
    console.log('✅ 所有测试完成');
    console.log('═══════════════════════════════════');

    ws.close();
}

runTests().catch(e => {
    console.error('❌ 测试失败:', e.message);
    process.exit(1);
});
