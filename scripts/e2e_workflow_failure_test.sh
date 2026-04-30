#!/bin/bash
# ═══════════════════════════════════════════════════════════════════
# 工作流失败路径 / 兜底路径测试脚本
#
# 覆盖不依赖 AI 输出质量的确定性路径：
#   1. /workflow/start 参数校验和未知 IDE 错误
#   2. /workflow/next 请求体校验
#   3. /workflow/next 未知 ref / 无 trailer / 非法状态转移忽略
#   4. 运行态人工 abort，并再次 abort 归位 IDLE
#
# 用法：
#   ./scripts/e2e_workflow_failure_test.sh [TEST_CWD]
# ═══════════════════════════════════════════════════════════════════
set -euo pipefail

RELAY="${RELAY:-http://127.0.0.1:19336}"
TEST_CWD="${1:-/Users/student2028/code/cc/workflow-e2e-test}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
NC='\033[0m'

log()  { echo -e "${BLUE}[$(date +%H:%M:%S)]${NC} $*"; }
pass() { echo -e "${GREEN}[PASS]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
step() { echo -e "\n${PURPLE}═══ $* ═══${NC}"; }

http_code() {
    local method="$1"
    local path="$2"
    local body="${3:-}"
    local out code
    out=$(mktemp)
    if [ -n "$body" ]; then
        code=$(curl -s -o "$out" -w '%{http_code}' -X "$method" "${RELAY}${path}" \
            -H 'Content-Type: application/json' -d "$body" || true)
    else
        code=$(curl -s -o "$out" -w '%{http_code}' -X "$method" "${RELAY}${path}" || true)
    fi
    cat "$out"
    rm -f "$out"
    echo
    echo "__HTTP_CODE__${code}"
}

json_field() {
    local field="$1"
    python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('${field}', ''))"
}

status_state() {
    curl -sf "${RELAY}/workflow/status" | json_field state
}

assert_code() {
    local expected="$1"
    local output="$2"
    local actual
    actual=$(echo "$output" | sed -n 's/^__HTTP_CODE__//p' | tail -1)
    if [ "$actual" != "$expected" ]; then
        echo "$output"
        fail "期望 HTTP ${expected}，实际 ${actual}"
    fi
}

assert_json_field() {
    local field="$1"
    local expected="$2"
    local output="$3"
    local actual
    actual=$(echo "$output" | sed '/^__HTTP_CODE__/d' | json_field "$field")
    if [ "$actual" != "$expected" ]; then
        echo "$output"
        fail "期望 ${field}=${expected}，实际 ${actual}"
    fi
}

reset_to_idle() {
    local state
    state=$(status_state 2>/dev/null || echo ERROR)
    if [ "$state" != "IDLE" ]; then
        warn "当前流水线状态为 ${state}，先 abort 归位"
        http_code POST /workflow/abort '{}' >/dev/null
        sleep 1
        state=$(status_state 2>/dev/null || echo ERROR)
        if [ "$state" != "IDLE" ]; then
            http_code POST /workflow/abort '{}' >/dev/null
            sleep 1
        fi
    fi
}

echo ""
echo -e "${PURPLE}╔═══════════════════════════════════════════════════╗${NC}"
echo -e "${PURPLE}║      工作流失败路径自动化测试                      ║${NC}"
echo -e "${PURPLE}╚═══════════════════════════════════════════════════╝${NC}"
echo ""
log "Relay:    ${RELAY}"
log "Test CWD: ${TEST_CWD}"

step "第 0 步：环境检查"
health=$(curl -sf "${RELAY}/health" 2>/dev/null | json_field status 2>/dev/null || echo "")
[ "$health" = "ok" ] || fail "Relay 服务不可用"
pass "Relay 服务健康"

[ -d "${TEST_CWD}/.git" ] || fail "测试仓库不存在或不是 Git 仓库: ${TEST_CWD}"
pass "测试仓库就绪"

reset_to_idle
[ "$(status_state)" = "IDLE" ] || fail "无法归位 IDLE"
pass "初始状态 IDLE"

step "第 1 步：/workflow/start 错误路径"
missing=$(http_code POST /workflow/start '{}')
assert_code 400 "$missing"
pass "缺少必填字段返回 HTTP 400"

unknown=$(http_code POST /workflow/start "{
  \"brain\": {\"ide\": \"DefinitelyMissingIde\"},
  \"worker\": {\"ide\": \"Cursor\", \"port\": 9555},
  \"cwd\": \"${TEST_CWD}\",
  \"initial_task\": \"failure path validation\"
}")
assert_code 503 "$unknown"
pass "未知 Brain IDE 返回 HTTP 503"

[ "$(status_state)" = "IDLE" ] || fail "失败 start 不应改变 IDLE 状态"
pass "失败 start 后状态保持 IDLE"

step "第 2 步：/workflow/next 校验和忽略路径"
missing_next=$(http_code POST /workflow/next '{}')
assert_code 400 "$missing_next"
pass "缺少 message/ref/hash 返回 HTTP 400"

unknown_ref=$(http_code POST /workflow/next '{
  "message": "TASK: should be ignored",
  "ref": "refs/heads/feature",
  "hash": "1111111111111111111111111111111111111111"
}')
assert_code 200 "$unknown_ref"
assert_json_field ignored unknown-ref "$unknown_ref"
pass "未知 ref 被安全忽略"

no_trailer=$(http_code POST /workflow/next '{
  "message": "feat: commit without trailer",
  "ref": "refs/heads/master",
  "hash": "2222222222222222222222222222222222222222"
}')
assert_code 200 "$no_trailer"
assert_json_field ignored no-orchestra-trailer "$no_trailer"
pass "无 Orchestra-Task trailer 的主分支提交被忽略"

illegal_done=$(http_code POST /workflow/next '{
  "message": "DONE",
  "ref": "refs/orchestra/pipeline",
  "hash": "3333333333333333333333333333333333333333"
}')
assert_code 200 "$illegal_done"
assert_json_field ignored illegal-transition "$illegal_done"
pass "IDLE 状态下 DONE 非法转移被忽略"

[ "$(status_state)" = "IDLE" ] || fail "忽略路径不应改变 IDLE 状态"
pass "忽略路径后状态保持 IDLE"

step "第 3 步：运行态人工 abort"
plan=$(http_code POST /workflow/next '{
  "message": "PLAN: failure path abort validation",
  "ref": "refs/orchestra/pipeline",
  "hash": "4444444444444444444444444444444444444444"
}')
assert_code 200 "$plan"
assert_json_field state BRAIN_PLAN "$plan"
pass "手动注入 PLAN 后进入 BRAIN_PLAN"

abort_running=$(http_code POST /workflow/abort '{}')
assert_code 200 "$abort_running"
assert_json_field state ABORT "$abort_running"
pass "运行态 abort 进入 ABORT"

abort_terminal=$(http_code POST /workflow/abort '{}')
assert_code 200 "$abort_terminal"
assert_json_field state IDLE "$abort_terminal"
pass "终态再次 abort 归位 IDLE"

[ "$(status_state)" = "IDLE" ] || fail "最终状态不是 IDLE"
pass "最终状态 IDLE"

echo ""
pass "失败路径测试完成"
