'use strict';

function pageHas(p, field, needle) {
    return String(p?.[field] || '').includes(needle);
}

function anyPageHas(pages, field, needle) {
    return pages.some(p => pageHas(p, field, needle));
}

function anyPageTitleHas(pages, needle) {
    return pages.some(p => pageHas(p, 'title', needle));
}

function anyPageTitleHasIgnoreCase(pages, needle) {
    const n = needle.toLowerCase();
    return pages.some(p => String(p?.title || '').toLowerCase().includes(n));
}

const DEFAULT_PORT_APPS = new Map([
    [9333, { name: 'Antigravity', emoji: '🚀' }],
    [9334, { name: 'Antigravity', emoji: '🚀' }],
    [9335, { name: 'Antigravity', emoji: '🚀' }],
    [9336, { name: 'Antigravity', emoji: '🚀' }],
    [9444, { name: 'Windsurf', emoji: '🏄' }],
    [9555, { name: 'Cursor', emoji: '🖱️' }],
    [9666, { name: 'Codex', emoji: '📦' }],
]);

function detectAppType(rawPages, options = {}) {
    const pages = Array.isArray(rawPages) ? rawPages : [];
    const port = Number(options.port);

    // First classify by the host application. Project names and embedded webviews
    // can contain words like DSME/Cursor/Windsurf, but the .app URL identifies the shell.
    if (anyPageHas(pages, 'url', 'Antigravity.app')) return { name: 'Antigravity', emoji: '🚀' };
    if (anyPageHas(pages, 'url', 'Cursor.app')) return { name: 'Cursor', emoji: '🖱️' };
    if (anyPageHas(pages, 'url', 'Windsurf.app')) return { name: 'Windsurf', emoji: '🏄' };
    if (anyPageHas(pages, 'url', 'Codex.app') || pages.some(p => String(p?.url || '').startsWith('app://'))) {
        return { name: 'Codex', emoji: '📦' };
    }

    // Then allow explicit IDE titles. This keeps compatibility for targets whose URL
    // does not expose a native shell path.
    if (anyPageTitleHas(pages, 'Antigravity')) return { name: 'Antigravity', emoji: '🚀' };
    if (anyPageTitleHas(pages, 'Cursor')) return { name: 'Cursor', emoji: '🖱️' };
    if (anyPageTitleHas(pages, 'Windsurf')) return { name: 'Windsurf', emoji: '🏄' };
    if (anyPageTitleHas(pages, 'Codex')) return { name: 'Codex', emoji: '📦' };

    if (anyPageHas(pages, 'url', 'workbench.html')) return { name: 'VS Code', emoji: '💻' };
    if (anyPageTitleHas(pages, 'Simple Code GUI') || anyPageTitleHas(pages, 'simple-code-gui')) {
        return { name: 'Claude Code', emoji: '🤖' };
    }

    // Standalone web tools. These must stay after shell detection so opening a project
    // or webview named DSME inside Cursor/Antigravity does not rename the IDE.
    if (anyPageTitleHas(pages, 'DSME') || anyPageTitleHas(pages, 'DeepSeek')) return { name: 'DSME', emoji: '🐋' };
    if (anyPageTitleHasIgnoreCase(pages, 'uitty') || anyPageHas(pages, 'url', ':9488')) {
        return { name: 'uitty', emoji: '🐚' };
    }

    if (Number.isInteger(port) && DEFAULT_PORT_APPS.has(port)) {
        return DEFAULT_PORT_APPS.get(port);
    }

    return { name: 'Unknown', emoji: '❓' };
}

module.exports = { detectAppType };
