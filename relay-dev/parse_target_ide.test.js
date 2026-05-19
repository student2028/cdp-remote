const assert = require('assert');
const { parseTargetIde } = require('./parse_target_ide');

// ─── 普通 IDE 名称 ─────────────────────────────────────────────
assert.deepStrictEqual(parseTargetIde('Cursor'),       { ideName: 'Cursor', pid: null });
assert.deepStrictEqual(parseTargetIde('Antigravity'),  { ideName: 'Antigravity', pid: null });
assert.deepStrictEqual(parseTargetIde('Codex'),        { ideName: 'Codex', pid: null });
assert.deepStrictEqual(parseTargetIde('Windsurf'),     { ideName: 'Windsurf', pid: null });

// ─── uitty:pid 格式 ────────────────────────────────────────────
assert.deepStrictEqual(parseTargetIde('uitty:12345'),  { ideName: 'uitty', pid: 12345 });
assert.deepStrictEqual(parseTargetIde('Uitty:999'),    { ideName: 'uitty', pid: 999 });
assert.deepStrictEqual(parseTargetIde('UITTY:42'),     { ideName: 'uitty', pid: 42 });

// ─── 边界情况 ──────────────────────────────────────────────────
assert.deepStrictEqual(parseTargetIde('uitty:'),       { ideName: 'uitty', pid: null },   'empty pid → null');
assert.deepStrictEqual(parseTargetIde('uitty:0'),      { ideName: 'uitty', pid: null },   'pid=0 invalid');
assert.deepStrictEqual(parseTargetIde('uitty:-1'),     { ideName: 'uitty', pid: null },   'negative pid');
assert.deepStrictEqual(parseTargetIde('uitty:abc'),    { ideName: 'uitty', pid: null },   'non-numeric pid');
assert.deepStrictEqual(parseTargetIde(''),             { ideName: '', pid: null },         'empty string');
assert.deepStrictEqual(parseTargetIde(null),           { ideName: '', pid: null },         'null');
assert.deepStrictEqual(parseTargetIde(undefined),      { ideName: '', pid: null },         'undefined');

// ─── 不误匹配含 "uitty" 但非前缀的名称 ────────────────────────
assert.deepStrictEqual(parseTargetIde('my-uitty:123'), { ideName: 'my-uitty:123', pid: null }, 'uitty not prefix');

console.log('parse_target_ide tests passed');
