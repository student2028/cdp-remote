/**
 * 解析 targetIde 中的 uitty:pid 格式。
 * 统一所有调度器 / 工作流中的 uitty 实例标识解析。
 *
 * @param {string} targetIde - 如 "Cursor"、"uitty:12345"
 * @returns {{ ideName: string, pid: number|null }}
 */
function parseTargetIde(targetIde) {
    const raw = String(targetIde || '');
    if (raw.toLowerCase().startsWith('uitty:')) {
        const pid = Number(raw.split(':')[1]);
        return { ideName: 'uitty', pid: Number.isFinite(pid) && pid > 0 ? pid : null };
    }
    return { ideName: raw, pid: null };
}

module.exports = { parseTargetIde };
