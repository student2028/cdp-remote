# 架构 V2：基于 Git 双轨制的多 Agent 协同流

> **核心思想：把多 Agent 协作降维成"事件驱动的状态机"，以 Git 的对象数据库作为消息队列和状态载体。代码事实走主分支，对话事件走旁路 ref，彼此独立又双向可追溯。**

---

## 一、演进逻辑

### V1 的痛点（基于文件 + 定时轮询）

在最初的 `.orchestra/` 架构中，IDE 之间通过 `plan.md`、`task.md`、`result.md` 传递信息，由 Voice7 (CDP Relay) 使用定时器（Cron / Interval）盲轮询触发：

1. **时间内耗**：定时器要么让 Agent 提早结束后干等，要么在未完成时被打断，调度延迟极高。
2. **文件污染**：一堆临时 `.md` 状态文件最后沦为无人清理的垃圾。
3. **缓存冲突**：多个 IDE 频繁读写同一个 `task.md`，文件状态经常不同步。

### V2 的破局思路

两个关键洞察：

1. **"代码变更 (Diff) + 提交信息 (Commit Message)" 就是最完美的 `result.md` 和 `plan.md`**。LLM 训练数据里，程序员的终极闭环动作就是 `git commit`，这个架构最大程度迎合了 AI 的"本能"。
2. **Git 的对象数据库远不止 `refs/heads/*`**。我们可以开辟一条旁路 ref 专门承载"Agent 之间的对话"，让真实代码 commit 保持干净。

---

## 二、双轨设计（核心）

```
┌──────────────────────────────────────────────────────────────┐
│                      同一个 Git 对象库                        │
│                                                               │
│   refs/heads/main              refs/orchestra/pipeline        │
│   ──────────────               ──────────────────────        │
│   feat: 登录 UI                DONE                           │
│   fix: 空指针                  TASK: 修复空指针               │
│                                TASK: 实现 LoginViewModel      │
│                                                               │
│   （真实代码事实）             （Agent 对话事件）             │
│                                                               │
│   通过 commit trailer 双向关联：                              │
│     Orchestra-Task: 9a8b7c6                                   │
└──────────────────────────────────────────────────────────────┘
```

### 两条 ref 的分工

| Ref | 承载内容 | 谁来写 | 出现在哪里 |
|-----|---------|--------|-----------|
| `refs/heads/main` | 真实代码 commit（`feat:` / `fix:` / `refactor:` …） | 工人 IDE 正常 `git commit` | `git log` / `git branch` / GitHub 侧栏 |
| `refs/orchestra/pipeline` | Agent 对话事件（`TASK:` / `REVIEW:` / `FAIL:` / `DONE` …） | `orchestra` 命令（见第六节） | **只有显式 `git log refs/orchestra/pipeline` 才可见** |

### 双向关联

工人每次提交真实代码时，commit message 尾部追加 **git trailer**（和 `Signed-off-by:`、`Co-authored-by:` 同一原生机制）：

```
feat: 完成 LoginViewModel 及验证码倒计时

Orchestra-Task: 9a8b7c6
Orchestra-Pipeline: pair_programming
```

- **正向**：`git show <code-commit>` → 看到这段代码是响应哪个任务写的。
- **反向**：`git log --grep "Orchestra-Task: 9a8b7c6"` → 找出所有响应同一任务的代码 commit。

### 为什么不用 `--allow-empty` 往主分支塞对话

被否决的方案：直接 `git commit --allow-empty -m "TASK: xxx"`。

| 污染项 | 后果 |
|--------|------|
| CHANGELOG 生成 | semantic-release 把未知 type 丢弃或报错 |
| `git bisect` | 空 commit 多跳几步 |
| Squash 合并 | 对话和代码被绑在一起 squash，语义全丢 |
| PR Files changed | Reviewer 看到空 diff 会困惑 |

**双轨制把这些问题一次性根除**：主分支任何时候都可以被 semantic-release、bisect、squash 当成"纯代码仓库"处理，流水线日志作为旁路 metadata 存在但不打扰。

---

## 三、Relay 的角色：确定性状态机

Voice7 / Relay 本身不做代码判断，但会承担确定性的编排、守护和降级推进：

- **主触发源**：Git Hook 的 HTTP 请求推动状态前进。
- **守护路径**：当 Hook 或 Agent 终端命令缺失时，watchdog 会读取 Brain 回复中的结构化 `TASK` / `VERDICT`，尽力自动补发 `orchestra` 事件。
- **动作**：从状态转移表中提取一段固定的死文本 (Prompt)，通过 CDP 发送给下一个绑定的 IDE 窗口。
- **归属识别**：Hook 同时上报 commit 所在的 **ref 名字**，Relay 据此**精确区分** 这次事件来自大脑（`refs/orchestra/pipeline`）还是工人（`refs/heads/main`），**不再依赖"前缀 + 当前状态" 的猜谜**。

```
┌─────────────────────────────────────────────┐
│              Relay 引擎（不变）              │
│                                             │
│  ┌──────────────┐   ┌──────────────┐        │
│  │ reference-   │──→│  状态转移表   │──→ 发送 │
│  │ transaction  │   │  (可插拔)    │   Prompt│
│  │ hook 事件    │   └──────────────┘   给 IDE│
│  └──────────────┘                           │
└─────────────────────────────────────────────┘
```

---

## 四、协议规范

### 4.1 对话消息前缀表

所有写入 `refs/orchestra/pipeline` 的 commit message 必须使用以下前缀之一：

| 前缀 | 方向 | 语义 | 触发动作 |
|------|------|------|----------|
| `TASK:` | 大脑 → 工人 | 派发新任务 | Relay 提取任务体发给工人，进入 `WORKER_CODE` |
| `REVIEW:` | 大脑 → 工人 | 返工要求（代码已存在但需修改） | 同上，但 prompt 会提示"基于上次 diff 修改" |
| `FAIL:` | 工人 → 大脑 | 无法完成（编译失败 / 需求不清） | Relay 把失败原因发给大脑，进入 `BRAIN_RECOVER` |
| `DONE` | 大脑 → 用户 | 整条流水线收尾 | 通知用户，进入 `DONE` |
| `ABORT` | 大脑或用户 | 人工中断 | 状态机清零，释放锁 |

**扩展原则**：新增前缀只需扩充状态转移表，Relay 引擎零改动。

### 4.2 代码 commit trailer 规范

工人提交真实代码时（走 `refs/heads/main`），commit message 尾部**必须**包含：

```
Orchestra-Task: <对应 pipeline commit 的短哈希>
Orchestra-Pipeline: <流水线类型名，如 pair_programming>
```

这两条 trailer 由 Relay 在下发 prompt 时**明确告知**工人 Agent，由 Agent 负责写入。Relay 不校验 trailer（保持零 AI / 零规则），但事后可用 `git interpret-trailers` 审计。

---

## 五、核心工作流：结对编程（Pair Programming）

### 5.1 拓扑结构

```
  你(需求)
    │
    ▼
  大脑(Plan) ◄────────────────────────┐
    │                                 │
    │ orchestra task "实现xxx"        │
    │ (写入 refs/orchestra/pipeline)  │
    ▼                                 │
  工人(编码)                           │
    │                                 │
    │ git commit -m "feat: ...        │
    │               Orchestra-Task: " │
    │ (写入 refs/heads/main)          │
    ▼                                 │
  大脑(Review)                        │
    │                                 │
    ├── 不满意 → orchestra review ────┘
    │           "修复xxx"
    │
    └── 全部完成 → orchestra done
```

### 5.2 状态转移表

```yaml
pipeline: pair_programming
roles:
  brain:  { ide: Antigravity, cdp_port: 9333 }
  worker: { ide: Cursor,      cdp_port: 9555, model: "Composer 2" }

timeouts:
  default_ms: 600000   # 10 分钟无事件就告警

states:
  IDLE:
    trigger: 用户在手机上点"启动"
    action:  向 brain 发送需求
    next:    BRAIN_PLAN

  BRAIN_PLAN:
    wait_for:
      ref: refs/orchestra/pipeline
      prefix: [TASK]
    action:  把 TASK 内容 + 关联哈希发给 worker
    next:    WORKER_CODE

  WORKER_CODE:
    wait_for:
      ref: refs/heads/main
    action:  通知 brain 审查 Diff（附上 commit hash）
    next:    BRAIN_REVIEW
    on_alt:
      # 工人也可能往 pipeline 写 FAIL
      ref: refs/orchestra/pipeline
      prefix: [FAIL]
      next:  BRAIN_RECOVER

  BRAIN_REVIEW:
    wait_for:
      ref: refs/orchestra/pipeline
      prefix: [REVIEW, DONE]
    branch:
      REVIEW → WORKER_CODE  # 返工循环
      DONE   → DONE

  BRAIN_RECOVER:
    wait_for:
      ref: refs/orchestra/pipeline
      prefix: [TASK, ABORT]
    branch:
      TASK  → WORKER_CODE
      ABORT → DONE

  DONE:
    action: 通知用户 + 记录日志 + 释放锁
```

### 5.3 实际效果

一次完整的流水线跑完后，两条 ref 分别呈现：

**主分支（真实代码）**

```
$ git log --oneline
f4e5d6c fix: 修复登录页面空指针
3d2e1f0 feat: 完成登录界面 UI 及 ViewModel
```

**流水线日志（旁路）**

```
$ git log refs/orchestra/pipeline --oneline
a1b2c3d DONE
b2c3d4e REVIEW: 登录页面有空指针风险，请修复 LoginViewModel 第42行
c0b1a2d TASK: 实现 LoginViewModel，包含验证码倒计时逻辑
```

**跨轨追溯**

```
$ git show f4e5d6c | tail -3
    fix: 修复登录页面空指针

    Orchestra-Task: b2c3d4e
```

这就是一份完美的、自带上下文、但**零侵入**的敏捷看板。

---

## 六、技术实现要点

### 6.1 `orchestra` 小命令（写入旁路 ref）

放在 `scripts/orchestra.sh`，封装"往 `refs/orchestra/pipeline` 追加一条对话 commit"：

```bash
#!/bin/bash
# 用法：orchestra task "实现 LoginViewModel"
#       orchestra review "修复第42行空指针"
#       orchestra fail "依赖未安装，需要先 pod install"
#       orchestra done
set -euo pipefail

VERB=$(echo "$1" | tr '[:lower:]' '[:upper:]')
BODY="${2:-}"
REF="refs/orchestra/pipeline"

case "$VERB" in
  TASK|REVIEW|FAIL) MSG="${VERB}: ${BODY}" ;;
  DONE|ABORT)       MSG="${VERB}" ;;
  *) echo "Unknown verb: $1" >&2; exit 1 ;;
esac

# 借用当前 HEAD 的 tree，不创建新树对象（节省空间）
TREE=$(git rev-parse HEAD^{tree})
PARENT=$(git rev-parse --verify "$REF" 2>/dev/null || true)

if [ -z "$PARENT" ]; then
  NEW=$(git commit-tree "$TREE" -m "$MSG")
else
  NEW=$(git commit-tree "$TREE" -p "$PARENT" -m "$MSG")
fi

git update-ref "$REF" "$NEW"
echo "orchestra: $VERB → $REF @ ${NEW:0:7}"
```

关键点：
- 用 `git commit-tree` + `git update-ref` 手工塑造 commit 对象，**完全绕开工作区**。
- `git status` 输出不变，主分支 `HEAD` 不动。
- Agent 端调用方式只有一行：`orchestra task "..."`，比 `git commit --allow-empty` 更短。

### 6.2 Git Hook：统一监听所有 ref 变化

用 `reference-transaction` hook（Git 2.28+ 原生），比 `post-commit` 更底层，能捕获 `git update-ref` 等非 `git commit` 的操作。

模板文件路径：`scripts/git-hooks/reference-transaction`。Relay 启动流水线时会把它安装到目标仓库的 `.git/hooks/reference-transaction`。

```bash
#!/bin/bash
# 只在事务"committed"阶段触发，过滤无关 ref
[ "$1" = "committed" ] || exit 0

while read -r OLDREV NEWREV REFNAME; do
  case "$REFNAME" in
    refs/heads/main|refs/orchestra/pipeline) ;;
    *) continue ;;
  esac

  MSG=$(git log -1 --pretty=%B "$NEWREV" 2>/dev/null || echo "")

  curl -s --max-time 2 --fail \
       -X POST http://127.0.0.1:19336/workflow/next \
       -H "Content-Type: application/json" \
       -d "$(jq -n \
              --arg m "$MSG" \
              --arg r "$REFNAME" \
              --arg h "$NEWREV" \
              '{message:$m, ref:$r, hash:$h}')" \
       > /dev/null 2>> .git/orchestra.log &
done
```

关键点：
- `--max-time 2 --fail` 防 Relay 挂掉时卡死 commit。
- 失败日志写到 `.git/orchestra.log`，不污染终端。
- 后台化 `&` 保证 commit 本身立即返回。

### 6.3 Relay `/workflow/next` 伪代码

```javascript
app.post('/workflow/next', (req, res) => {
  const { message, ref, hash } = req.body;
  const pl = currentPipeline;  // 当前激活的流水线 + 状态

  // 解析前缀（统一正则，未来扩展语法就一个点）
  const m = message.match(/^(\w+)(?::\s*(.*))?$/s);
  const verb = m ? m[1].toUpperCase() : null;
  const body = m ? (m[2] || '').trim() : '';

  // 按 ref 精确分流来源，不再猜
  if (ref === 'refs/orchestra/pipeline') {
    // 来源确定：对话事件
    switch (verb) {
      case 'TASK':
      case 'REVIEW':
        sendToIde(pl.worker.cdp_port,
          `${verb === 'REVIEW' ? '大脑提出返工' : '大脑派发新任务'}：${body}\n\n` +
          `提交代码时请在 message 末尾附上：\n` +
          `  Orchestra-Task: ${hash.slice(0,7)}\n` +
          `  Orchestra-Pipeline: ${pl.name}`);
        pl.state = 'WORKER_CODE';
        break;
      case 'FAIL':
        sendToIde(pl.brain.cdp_port,
          `工人无法完成任务，原因：${body}\n请决策：继续派 TASK 还是 ABORT。`);
        pl.state = 'BRAIN_RECOVER';
        break;
      case 'DONE':
        notifyUser('流水线已完成');
        pl.state = 'DONE';
        break;
      case 'ABORT':
        notifyUser('流水线已中断');
        pl.state = 'DONE';
        break;
    }
  } else if (ref === 'refs/heads/main') {
    // 来源确定：工人交卷代码
    sendToIde(pl.brain.cdp_port,
      `工人提交了代码：「${message.split('\n')[0]}」\n` +
      `请用 git show ${hash} 审查 Diff。\n` +
      `不满意 → orchestra review "..."\n` +
      `满意且还有任务 → orchestra task "..."\n` +
      `全部完成 → orchestra done`);
    pl.state = 'BRAIN_REVIEW';
  }

  res.json({ ok: true, state: pl.state });
});

// 每个状态都带 timeout watchdog
setInterval(() => {
  if (now() - pl.state_entered_at > pl.timeouts.default_ms) {
    notifyUser(`流水线在状态 ${pl.state} 超时，请人工介入`);
  }
}, 30000);
```

### 6.4 便利 Git alias

写进 repo 的 `.git/config`（或全局 `~/.gitconfig`），方便人类随时围观：

```ini
[alias]
    pipeline  = log refs/orchestra/pipeline --oneline --decorate
    pipefull  = log refs/orchestra/pipeline --format='%C(yellow)%h%Creset %s%n%n%w(0,4,4)%b'
    trace     = "!f() { git log --all --grep=\"Orchestra-Task: $1\"; }; f"
```

用法：

```bash
git pipeline            # 流水线概览
git pipefull            # 每条对话的完整 body
git trace b2c3d4e       # 根据任务哈希反查所有响应代码
```

### 6.5 前端遥控器 HTTP API

Relay 对前端（Android 手机遥控器）暴露三个端点，用来**启动 / 观测 / 中断**流水线。所有端点都只在本机监听（`POST /workflow/next` 强制 `127.0.0.1`/`::1`），跨网访问只允许读端点。

| 端点 | 方法 | 调用者 | 用途 |
|------|------|--------|------|
| `/workflow/start`  | POST | 手机 App | 配置大脑 / 工人 / cwd，派发首个 `TASK:` |
| `/workflow/status` | GET  | 手机 App（2s 轮询） | 读当前流水线状态 |
| `/workflow/abort`  | POST | 手机 App | 人工中断，状态回到 `IDLE` |
| `/workflow/next`   | POST | `reference-transaction` hook（本机） | Agent 对话事件内部推进，**不对外开放** |

#### 6.5.1 `POST /workflow/start`

```jsonc
// 请求
{
  "pipeline": "pair_programming",          // 可选，默认 pair_programming
  "brain":  { "ide": "Antigravity" },      // 必填，CDP 扫描到的在线 IDE 名
  "worker": { "ide": "Cursor" },           // 必填，必须 ≠ brain
  "initial_task": "实现 LoginViewModel",   // 必填
  "cwd": "/Users/me/code/my-repo"          // 必填，Git 仓库工作目录
}

// 成功响应 (200)
{
  "ok": true,
  "pipeline": "pair_programming",
  "brain":  { "ide": "Antigravity", "port": 9333 },
  "worker": { "ide": "Cursor",      "port": 9555 },
  "cwd":    "/Users/me/code/my-repo",
  "script_output": "✅ orchestra: TASK → refs/orchestra/pipeline @ 67518ce",
  "note": "状态将在 hook 到达后异步推进到 WORKER_CODE，可轮询 /workflow/status 查看"
}
```

**错误码**

| 状态码 | 触发条件 |
|-------|---------|
| 400 | 缺字段 / `brain == worker` / `cwd` 不是 Git 仓库 |
| 409 | 当前流水线**非 IDLE**（还在跑），需要先 abort |
| 503 | `brain` 或 `worker` IDE 自动启动失败（response 附 `available` 列表） |
| 500 | `scripts/orchestra.sh` 执行失败 |

**内部行为**：校验通过后，Relay 会先确保 Brain / Worker IDE 就绪：若指定端口未在线，则自动启动对应 IDE，并让两个 IDE 打开同一个 `cwd`。当 Worker 是 Cursor 时，Relay 会尝试把 Cursor Composer 模型切到 `Composer 2`。随后 Relay 以 `cwd` 为工作目录执行本项目内置的 `scripts/orchestra.sh plan <initial_task>`，由 `reference-transaction` hook 异步触发 `/workflow/next`，状态机先走 `IDLE → BRAIN_PLAN`，再由 Brain 派发首个 `TASK` 进入 `WORKER_CODE`。

#### 6.5.2 `GET /workflow/status`

```jsonc
{
  "pipeline":   "pair_programming",
  "state":      "WORKER_CODE",     // IDLE | BRAIN_PLAN | WORKER_CODE | BRAIN_REVIEW | BRAIN_RECOVER | DONE | ABORT
  "elapsed_ms": 1185,              // 进入当前状态至今的毫秒数
  "warned":     false,             // 是否超过 timeouts.default_ms（默认 10min）
  "brain":  { "ide": "Antigravity", "port": 9333 },
  "worker": { "ide": "Cursor",      "port": 9555 },  // port 为 null 表示离线
  "cwd":    "/Users/me/code/my-repo",
  "timeouts": { "default_ms": 600000 }
}
```

前端用 `elapsed_ms` + `warned` 做"停留过久"提示，用 `port == null` 识别 IDE 掉线。

#### 6.5.3 `POST /workflow/abort`

```jsonc
// 请求（cwd 可省略，缺省复用 start 时记录的值）
{ "cwd": "/Users/me/code/my-repo" }

// 响应
{ "ok": true, "prev": "WORKER_CODE", "state": "IDLE" }
// 或（已经是 IDLE）
{ "ok": true, "state": "IDLE", "note": "已在 IDLE，无需中断" }
```

**内部行为**：Relay 以 `cwd` 为工作目录执行本项目内置的 `scripts/orchestra.sh abort`，pipeline ref 追加一条 `ABORT`，状态机进入 `ABORT`，随后延迟自愈回 `IDLE`。

#### 6.5.4 前端集成概要（Android）

Jetpack Compose 页面路径：`presentation/screen/workflow/`

| 文件 | 职责 |
|------|------|
| `WorkflowUiState.kt`    | 状态 data class + `WorkflowState` 枚举（含中文显示名和 emoji） |
| `WorkflowViewModel.kt`  | OkHttp 封装 + 2 秒轮询 `/workflow/status` + `parseStatusJson` 单测入口 |
| `WorkflowScreen.kt`     | 状态卡片、大脑/工人 chip 选择器、Git 仓库路径输入、初始任务多行文本、启动/中断互斥按钮 |

入口：主机列表页（`HostListScreen`）TopBar 的 `AccountTree` 图标 → 跳转到 `Routes.WORKFLOW`。

**前提要求**：用户需要选择两个不同的 IDE 实例（默认 Antigravity:9333 + Cursor:9555）。若 IDE 没有打开，`/workflow/start` 会按端口自动启动并打开同一个 Git 工作目录；自动启动失败时才返回 503。

---

## 七、可插拔流水线架构

流水线的本质是一张**有向图**：节点是 IDE 角色，边是 Git 事件触发的状态转移。不同流水线只是不同的图拓扑，Relay 引擎本身不变，切换流水线只需要加载不同的**状态转移表**（第 5.2 节的 YAML）。

### 7.1 未来可扩展的流水线类型

| 流水线 | 拓扑 | 节点数 | 适用场景 |
|--------|------|--------|----------|
| **结对编程** | 大脑 ⇄ 工人（双节点循环） | 2 | 日常功能开发（起点） |
| **三角审查** | 大脑 → 工人 → 审查员 → 大脑 | 3 | 需要独立 Code Review 的严肃场景 |
| **并行施工** | 大脑 → 工人 A + 工人 B → 大脑汇总 | 3+ | 前后端同步开发 |
| **TDD 驱动** | 测试员 (写测试) → 工人 (写实现) → 测试员 (验证) | 2 | 测试驱动开发 |
| **全栈流水线** | 编码 → 测试 → 文档 → 部署 | 4 | 完整 DevOps 模拟 |

**关键原则：所有流水线共享同一个 Relay 引擎，区别仅在于加载的状态转移表不同。引擎代码一行都不用改。**

### 7.2 与定时调度器的关系

流水线和定时调度器是**两套独立并行的系统**，共存于同一个 Relay 进程，各走各的 URL 路径：

| 系统 | 触发方式 | 用途 | URL 前缀 |
|------|---------|------|----------|
| **定时调度器 (Scheduler)** | Cron / Interval | 周期性任务（定时挖 Alpha、健康检查） | `/scheduler/*` |
| **流水线 (Pipeline)** | Git `reference-transaction` Hook | 多 Agent 协作开发 | `/workflow/*` |

---

## 八、已知取舍与局限

诚实列出当前设计**放弃了什么**，避免后续踩坑：

1. **全局状态驻留 Relay 内存**：Relay 重启则当前运行中的流水线状态丢失。对于单机单项目场景可接受；如需抗重启，后续可把 `pl.state` 持久化到 `.git/orchestra-state.json`。

2. **不提供"活文档式进度表"**：V1 的 `plan.md` 能随时 `cat` 看全局待办，V2 需要 `git pipeline` 查看。对人类中途介入者略有学习成本，但换来了零文件污染。

3. **依赖 Agent 自觉写 trailer**：`Orchestra-Task:` 由工人 Agent 自己追加到 commit message。如果 Agent 忘写，不会阻塞流水线（Relay 只认 ref + 前缀），但事后反向追溯会断链。可以在 `pre-commit` hook 里做宽松校验（仅告警不阻断）。

4. **单 repo 单流水线**：当前设计假设同一时刻只跑一条流水线。如果同时开多个项目，每个项目 Relay 实例独立监听不同端口即可，但**跨项目编排**不在本架构范围内。

5. **`reference-transaction` hook 需要 Git 2.28+**：macOS 自带的 Git 通常足够新，但极老环境可能需要 `brew install git`。

---

## 九、为什么这是"降维打击"式的优化

1. **绝对的可观测性**：`git pipeline` 和 `git log` 分别呈现对话流和代码流，两条干净、互不干扰，又能通过 trailer 一键打通。
2. **零延时 (Zero Latency)**：不再傻等定时器，Agent 一敲回车，下一个 Agent 毫秒级接棒。
3. **精准的来源识别**：靠 ref 名字而非消息前缀判断谁在说话，彻底消除 V1 的"并发串话"隐患。
4. **消除大模型幻觉**：Agent 直接看 Diff 审查，远比让 AI 在 `result.md` 里自然语言汇报"我改了什么"客观一百倍。
5. **符合 LLM 本能**：所有 LLM 训练数据里，程序员的终极闭环动作就是 `git commit`。架构最大程度迎合了 AI 的"本能"。
6. **主仓库永远可发布**：`refs/heads/main` 任何时候都是纯代码事实，CI/CD、语义化发版、`git bisect` 全部零改动。
