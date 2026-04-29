/** 从大脑回复文本中解析 VERDICT 和 ISSUES 摘要 */
function parseBrainVerdict(replyText) {
    if (!replyText || replyText.length < 20) return null;

    // 匹配 VERDICT: PASS 或 VERDICT: NEEDS_REWORK
    const verdictMatch = replyText.match(/VERDICT\s*[:：]\s*(PASS|NEEDS_REWORK)/i);
    if (!verdictMatch) return null;

    const verdict = verdictMatch[1].toUpperCase();

    // 提取 ISSUES 部分作为返工消息
    let issues = '';
    const issuesMatch = replyText.match(/ISSUES\s*[:：]\s*([\s\S]*?)(?:SUMMARY|---|\n\n|$)/i);
    if (issuesMatch) {
        issues = issuesMatch[1].trim();
    }

    // 提取 SUMMARY
    let summary = '';
    const summaryMatch = replyText.match(/SUMMARY\s*[:：]\s*(.+)/i);
    if (summaryMatch) {
        summary = summaryMatch[1].trim();
    }

    let nextAction = '';
    const actionMatch = replyText.match(/NEXT_ACTION\s*[:：]\s*(REVIEW|TASK|DONE)/i);
    if (actionMatch) {
        nextAction = actionMatch[1].toUpperCase();
    }

    let nextTask = '';
    const taskMatch = replyText.match(/[-—–]{2,}\s*NEXT[\s_]*TASK[\s_]*START\s*[-—–]{2,}\s*([\s\S]*?)\s*[-—–]{2,}\s*NEXT[\s_]*TASK[\s_]*END/i);
    if (taskMatch && taskMatch[1].trim().length > 5) {
        nextTask = taskMatch[1].trim();
    }

    return { verdict, issues, summary, nextAction, nextTask };
}


/**
 * 从大脑回复文本中解析 BRAIN_PLAN 阶段输出的任务内容。
 * 匹配 ---TASK_START---...---TASK_END--- 之间的内容。
 * @param {string} replyText
 * @returns {string|null} 任务内容，未找到返回 null
 */
function parseBrainTask(replyText, { fallbackFull = false } = {}) {
    if (!replyText || replyText.length < 20) return null;

    // 1. 精确匹配：---TASK_START---...---TASK_END---
    let m = replyText.match(/---TASK_START---\s*([\s\S]*?)\s*---TASK_END---/);
    if (m && m[1].trim().length > 5) return m[1].trim();

    // 2. 宽松匹配：带空格、少横线、全角横线、代码块包裹
    m = replyText.match(/[-—–]{2,}\s*TASK[\s_]*START\s*[-—–]{2,}\s*([\s\S]*?)\s*[-—–]{2,}\s*TASK[\s_]*END/i);
    if (m && m[1].trim().length > 5) return m[1].trim();

    // 3. 单边匹配：只有 TASK_START 没有 TASK_END（AI 可能省略了结尾标记）
    m = replyText.match(/[-—–]{2,}\s*TASK[\s_]*START\s*[-—–]{2,}\s*([\s\S]+)/i);
    if (m && m[1].trim().length > 30) return m[1].trim();

    // 4. fallbackFull 模式（超时降级时启用）：回复足够长且含任务关键词，整篇当 task
    if (fallbackFull && replyText.length > 150) {
        return replyText;
    }

    return null;
}

module.exports = { parseBrainVerdict, parseBrainTask };
