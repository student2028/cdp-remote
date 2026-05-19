function schedulerAppKey(appName) {
    const lower = String(appName || '').toLowerCase();
    if (lower.startsWith('uitty:') || lower.includes('uitty')) return 'uitty';
    if (lower.includes('cursor')) return 'cursor';
    if (lower.includes('windsurf')) return 'windsurf';
    if (lower.includes('codex')) return 'codex';
    if (lower.includes('claude')) return 'claude';
    if (lower.includes('dsme') || lower.includes('deepseek')) return 'dsme';
    if (lower.includes('antigravity')) return 'antigravity';
    return lower || 'unknown';
}

function sanitizeModelOptions(models) {
    const seen = new Set();
    const out = [];
    for (const model of models || []) {
        const text = String(model || '')
            .replace(/\s+/g, ' ')
            .replace(/^[^\p{L}\p{N}]+/u, '')
            .trim();
        if (!text || seen.has(text)) continue;
        seen.add(text);
        out.push(text);
    }
    return out;
}

function defaultSchedulerModelOptions(appName) {
    const appKey = schedulerAppKey(appName);
    if (appKey === 'cursor') {
        return ['Auto', 'Premium', 'Composer 2', 'Composer 1.5', 'GPT', 'Codex', 'Sonnet', 'Opus'];
    }
    if (appKey === 'windsurf') {
        return [
            'Claude Opus 4.7',
            'Claude Opus 4.6',
            'Claude Sonnet 4.6',
            'GPT-5.3-Codex',
            'GPT-5.4',
            'Kimi K2.6',
            'SWE-1.6',
            'Gemini 3.1 Pro',
            'Adaptive'
        ];
    }
    if (appKey === 'codex') {
        return ['Extra High', 'High', 'Medium', 'Low', 'Fast', 'Standard', 'GPT-5.5', 'GPT-5.4'];
    }
    if (appKey === 'antigravity' || appKey === 'dsme') {
        return [
            'Gemini 3.1 Pro',
            'Gemini 3 Flash',
            'Claude Sonnet 4.6',
            'Claude Opus 4.7',
            'GPT-5.4',
            'Kimi K2.6',
            'MiniMax'
        ];
    }
    if (appKey === 'claude') {
        return ['sonnet', 'opus', 'haiku'];
    }
    return [];
}

module.exports = {
    defaultSchedulerModelOptions,
    sanitizeModelOptions,
    schedulerAppKey
};
