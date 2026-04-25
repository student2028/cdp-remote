#!/bin/bash
# Git 双轨制：往 refs/orchestra/pipeline 写入 Agent 对话事件
#
# 用法：
#   ./scripts/orchestra.sh task   "实现 LoginViewModel"
#   ./scripts/orchestra.sh review "修复第42行空指针"
#   ./scripts/orchestra.sh fail   "依赖未安装，需要先 pod install"
#   ./scripts/orchestra.sh done
#   ./scripts/orchestra.sh abort
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "用法: orchestra <task|review|fail|done|abort> [message]" >&2
  exit 1
fi

VERB=$(echo "$1" | tr '[:lower:]' '[:upper:]')
BODY="${2:-}"
REF="refs/orchestra/pipeline"

case "$VERB" in
  TASK|REVIEW|FAIL|PLAN)
    if [ -z "$BODY" ]; then
      echo "错误: $VERB 需要附带消息内容" >&2
      echo "用法: orchestra $1 \"消息内容\"" >&2
      exit 1
    fi
    MSG="${VERB}: ${BODY}"
    ;;
  DONE|ABORT)
    MSG="${VERB}"
    ;;
  *)
    echo "未知指令: $1" >&2
    echo "可用指令: plan, task, review, fail, done, abort" >&2
    exit 1
    ;;
esac

# 借用当前 HEAD 的 tree（节省空间）；若是全新仓库还没有 HEAD，则退化到空树对象。
if git rev-parse --verify HEAD >/dev/null 2>&1; then
  TREE=$(git rev-parse HEAD^{tree})
else
  TREE=$(git mktree </dev/null)
fi
PARENT=$(git rev-parse --verify "$REF" 2>/dev/null || true)

if [ -z "$PARENT" ]; then
  NEW=$(git commit-tree "$TREE" -m "$MSG")
else
  NEW=$(git commit-tree "$TREE" -p "$PARENT" -m "$MSG")
fi

git update-ref "$REF" "$NEW"
echo "✅ orchestra: $VERB → $REF @ ${NEW:0:7}"
