#!/bin/bash
# ═══════════════════════════════════════════════════════════════════
# 端到端流水线测试脚本
#
# 测试流程：
#   IDLE → BRAIN_PLAN → WORKER_CODE → BRAIN_REVIEW → (循环) → DONE
#
# 用法：
#   ./scripts/e2e_workflow_test.sh [TEST_CWD] [BRAIN_PORT] [WORKER_PORT]
#
# 默认值：
#   TEST_CWD  = /Users/student2028/code/cc/workflow-e2e-test
#   BRAIN     = Antigravity:9333
#   WORKER    = Cursor:9555
# ═══════════════════════════════════════════════════════════════════
set -euo pipefail

RELAY="http://127.0.0.1:19336"
TEST_CWD="${1:-/Users/student2028/code/cc/workflow-e2e-test}"
BRAIN_PORT="${2:-9333}"
WORKER_PORT="${3:-9555}"
WORKER_APP="${WORKER_APP:-Cursor}"
WORKER_MODEL="${WORKER_MODEL:-Composer 2}"
MAX_WAIT_SECS=600   # 单阶段最长等待 10 分钟
POLL_INTERVAL=10    # 每 10 秒轮询一次
TOTAL_TIMEOUT=1800  # 全流程最长 30 分钟

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

log()  { echo -e "${BLUE}[$(date +%H:%M:%S)]${NC} $*"; }
pass() { echo -e "${GREEN}[✅ PASS]${NC} $*"; }
fail() { echo -e "${RED}[❌ FAIL]${NC} $*"; }
warn() { echo -e "${YELLOW}[⚠️  WARN]${NC} $*"; }
step() { echo -e "\n${PURPLE}═══ $* ═══${NC}"; }

# ─── 工具函数 ─────────────────────────────────────────────────

get_state() {
    curl -sf "${RELAY}/workflow/status" 2>/dev/null | python3 -c "
import json,sys
d = json.load(sys.stdin)
print(d.get('state','ERROR'))" 2>/dev/null || echo "ERROR"
}

get_full_status() {
    curl -sf "${RELAY}/workflow/status" 2>/dev/null | python3 -c "
import json,sys
d = json.load(sys.stdin)
state = d.get('state','?')
elapsed = d.get('elapsed_ms',0)
brain = d.get('brain',{}).get('ide','?')
worker = d.get('worker',{}).get('ide','?')
cwd = d.get('cwd','?')
rr = d.get('reviewRound',0)
lrv = d.get('lastReviewVerdict','?')
print(f'{state}|{elapsed}|{brain}|{worker}|{cwd}|{rr}|{lrv}')" 2>/dev/null || echo "ERROR|0|?|?|?|0|?"
}

check_ide() {
    local port=$1
    local name=$2
    local resp
    resp=$(curl -sf "http://127.0.0.1:${port}/json" 2>/dev/null | head -1)
    if [ -n "$resp" ]; then
        pass "IDE ${name} on CDP:${port} 可用"
        return 0
    else
        fail "IDE ${name} on CDP:${port} 不可用"
        return 1
    fi
}

wait_for_state() {
    local target_state="$1"
    local description="$2"
    local timeout="${3:-$MAX_WAIT_SECS}"
    local start_time=$SECONDS
    local last_state=""

    while true; do
        local elapsed=$((SECONDS - start_time))
        if [ $elapsed -ge $timeout ]; then
            fail "等待 ${target_state} 超时 (${elapsed}s > ${timeout}s)"
            return 1
        fi

        local state
        state=$(get_state)

        if [ "$state" != "$last_state" ]; then
            log "状态变化: ${CYAN}${last_state:-INIT}${NC} → ${CYAN}${state}${NC}  (${elapsed}s)"
            last_state="$state"
        fi

        # 支持多个目标状态（用 | 分隔）
        for ts in $(echo "$target_state" | tr '|' ' '); do
            if [ "$state" = "$ts" ]; then
                pass "${description} → ${state} (耗时 ${elapsed}s)"
                return 0
            fi
        done

        # 异常终态
        if [ "$state" = "ABORT" ] && [[ ! "$target_state" =~ "ABORT" ]]; then
            fail "流水线意外中断 (ABORT)，在等待 ${target_state} 时"
            return 1
        fi

        sleep $POLL_INTERVAL
    done
}

# ─── 主流程 ─────────────────────────────────────────────────

GLOBAL_START=$SECONDS

echo ""
echo -e "${PURPLE}╔═══════════════════════════════════════════════════╗${NC}"
echo -e "${PURPLE}║      🔬 端到端流水线自动化测试                      ║${NC}"
echo -e "${PURPLE}╚═══════════════════════════════════════════════════╝${NC}"
echo ""
log "Relay:     ${RELAY}"
log "Brain:     Antigravity:${BRAIN_PORT}"
log "Worker:    ${WORKER_APP}:${WORKER_PORT}"
log "Test CWD:  ${TEST_CWD}"
log "超时设置:  单阶段 ${MAX_WAIT_SECS}s | 全流程 ${TOTAL_TIMEOUT}s"
echo ""

# ─── 第 0 步：前置检查 ──────────────────────────────────────

step "第 0 步：环境检查"

# Relay 健康检查
relay_ok=$(curl -sf "${RELAY}/health" 2>/dev/null | python3 -c "import json,sys; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")
if [ "$relay_ok" = "ok" ]; then
    pass "Relay 服务健康"
else
    fail "Relay 服务不可用"
    exit 1
fi

# 测试仓库检查
if [ -d "${TEST_CWD}/.git" ]; then
    pass "测试仓库就绪: ${TEST_CWD}"
else
    fail "测试仓库不存在或不是 Git 仓库"
    exit 1
fi

log "确保两个 IDE 已打开 ${TEST_CWD}..."
curl -sf "${RELAY}/launch?port=${BRAIN_PORT}&app=Antigravity&cwd=${TEST_CWD}" > /dev/null 2>&1 || true
curl -sf "${RELAY}/launch?port=${WORKER_PORT}&app=${WORKER_APP}&cwd=${TEST_CWD}" > /dev/null 2>&1 || true
sleep 3

# IDE 检查
check_ide "$BRAIN_PORT" "Antigravity (Brain)" || exit 1
check_ide "$WORKER_PORT" "${WORKER_APP} (Worker)"  || exit 1

# 当前流水线状态
current=$(get_state)
if [ "$current" != "IDLE" ] && [ "$current" != "DONE" ] && [ "$current" != "ABORT" ]; then
    warn "当前流水线状态为 ${current}，先 abort"
    curl -sf -X POST "${RELAY}/workflow/abort" -H 'Content-Type: application/json' -d '{}' > /dev/null
    sleep 2
fi

# ─── 第 1 步：启动流水线 ──────────────────────────────────────

step "第 1 步：启动流水线 (PLAN)"

TASK_MSG="请在项目中创建一个 utils/greeting.py 文件，实现一个 greet(name) 函数，返回 Hello, {name}! 字符串，并添加 docstring 和类型注解。这是一个非常简单的任务，请直接实现即可。"

log "发送启动请求..."
log "Relay /workflow/start 也会在发现 IDE 不在线时自动启动并打开 ${TEST_CWD}"

START_RESP=$(curl -sf -X POST "${RELAY}/workflow/start" \
    -H 'Content-Type: application/json' \
    -d "{
        \"brain\": {\"ide\": \"Antigravity\", \"port\": ${BRAIN_PORT}},
        \"worker\": {\"ide\": \"${WORKER_APP}\", \"port\": ${WORKER_PORT}},
        \"cwd\": \"${TEST_CWD}\",
        \"initial_task\": \"${TASK_MSG}\",
        \"min_review_rounds\": 1
    }" 2>/dev/null)

START_OK=$(echo "$START_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('ok',False))" 2>/dev/null || echo "False")

if [ "$START_OK" = "True" ]; then
    pass "流水线启动成功"
else
    fail "流水线启动失败: ${START_RESP}"
    exit 1
fi

# ─── 第 2 步：等待 BRAIN_PLAN → WORKER_CODE ──────────────────

step "第 2 步：等待 Brain 规划 → Worker 接任务 (BRAIN_PLAN → WORKER_CODE)"
log "Brain (Antigravity) 正在分析需求并输出任务..."
log "（超时降级将在 3 分钟后自动触发）"

wait_for_state "WORKER_CODE" "Brain 规划完成，Worker 已接收任务" || {
    fail "BRAIN_PLAN → WORKER_CODE 失败"
    log "最终状态："
    get_full_status
    exit 1
}

# ─── 第 3 步：等待 WORKER_CODE → BRAIN_REVIEW ────────────────

step "第 3 步：等待 Worker 编码 → Brain 审查 (WORKER_CODE → BRAIN_REVIEW)"
if [ "${WORKER_APP}" = "Cursor" ]; then
    log "Worker (Cursor) 将使用 ${WORKER_MODEL} 编码并提交..."
else
    log "Worker (${WORKER_APP}) 正在编码并提交..."
fi

wait_for_state "BRAIN_REVIEW" "Worker 编码完成，进入 Brain 审查" || {
    fail "WORKER_CODE → BRAIN_REVIEW 失败"
    exit 1
}

# ─── 第 4 步：等待审查循环 → DONE ────────────────────────────

step "第 4 步：等待审查循环 → 完成 (BRAIN_REVIEW ⇄ WORKER_CODE → DONE)"
log "可能经历多轮审查-返工循环..."

REVIEW_START=$SECONDS
while true; do
    local_elapsed=$((SECONDS - REVIEW_START))
    global_elapsed=$((SECONDS - GLOBAL_START))

    if [ $global_elapsed -ge $TOTAL_TIMEOUT ]; then
        fail "全流程超时 (${global_elapsed}s > ${TOTAL_TIMEOUT}s)"
        exit 1
    fi

    IFS='|' read -r state elapsed brain worker cwd rr lrv <<< "$(get_full_status)"
    elapsed_sec=$((elapsed / 1000))

    case "$state" in
        BRAIN_REVIEW)
            log "第 ${rr} 轮审查中... (全局 ${global_elapsed}s)"
            ;;
        WORKER_CODE)
            log "Worker 返工中（上轮 verdict: ${lrv}）... (全局 ${global_elapsed}s)"
            ;;
        DONE)
            pass "🎉 流水线完成！(reviewRound=${rr}, 全局耗时 ${global_elapsed}s)"
            break
            ;;
        ABORT)
            fail "流水线被中断 (reviewRound=${rr})"
            exit 1
            ;;
        IDLE)
            # DONE 自愈到 IDLE
            pass "🎉 流水线完成并自愈到 IDLE (全局耗时 ${global_elapsed}s)"
            break
            ;;
        *)
            warn "意外状态: ${state}"
            ;;
    esac
    sleep $POLL_INTERVAL
done

# ─── 第 5 步：验证结果 ───────────────────────────────────────

step "第 5 步：验证结果"

cd "$TEST_CWD"

# 检查是否有新文件被创建/修改
NEW_FILES=$(git diff HEAD --name-only 2>/dev/null)
COMMITTED=$(git log --oneline -5)
if [ -n "$NEW_FILES" ] || [ "$(echo "$COMMITTED" | wc -l)" -gt 1 ]; then
    pass "检测到代码变更"
    log "变更文件: ${NEW_FILES:-无未提交变更}"
    log "最近提交:"
    echo "$COMMITTED" | head -5
else
    warn "未检测到代码变更"
fi

# 检查 git log
COMMIT_COUNT=$(git log --oneline | wc -l | tr -d ' ')
log "Git 提交数: ${COMMIT_COUNT}"

ORCHESTRA_COUNT=$(git log --all --oneline refs/orchestra/pipeline 2>/dev/null | wc -l | tr -d ' ')
log "Orchestra 事件数: ${ORCHESTRA_COUNT}"

# ─── 总结 ─────────────────────────────────────────────────

TOTAL_ELAPSED=$((SECONDS - GLOBAL_START))
echo ""
echo -e "${PURPLE}╔═══════════════════════════════════════════════════╗${NC}"
echo -e "${PURPLE}║      📊 测试总结                                    ║${NC}"
echo -e "${PURPLE}╚═══════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  总耗时:        ${GREEN}${TOTAL_ELAPSED}s${NC}"
echo -e "  最终状态:      ${GREEN}$(get_state)${NC}"
echo -e "  Git 提交数:    ${COMMIT_COUNT}"
echo -e "  Orchestra 事件: ${ORCHESTRA_COUNT}"
echo ""
pass "🏁 端到端测试完成"
