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

    return { verdict, issues, summary };
}


/**
 * 从大脑回复文本中解析 BRAIN_PLAN 阶段输出的任务内容。
 * 匹配 ---TASK_START---...---TASK_END--- 之间的内容。
 * @param {string} replyText
 * @returns {string|null} 任务内容，未找到返回 null
 */
function parseBrainTask(replyText) {
    if (!replyText || replyText.length < 20) return null;
    const m = replyText.match(/---TASK_START---\s*([\s\S]*?)\s*---TASK_END---/);
    if (!m) return null;
    const task = m[1].trim();
    return task.length > 5 ? task : null;
}

module.exports = { parseBrainVerdict, parseBrainTask };
