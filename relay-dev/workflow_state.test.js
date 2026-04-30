const assert = require('assert');
const { hydratePipelineState, statusInitialTask } = require('./workflow_state');

const restored = hydratePipelineState({
    state: 'BRAIN_REVIEW',
    initialTask: 'Ship the git workflow safely',
    brain: { ide: 'Antigravity', port: 9333 },
    worker: { ide: 'Cursor', port: 9555 },
});

assert.strictEqual(restored.initialTask, 'Ship the git workflow safely');
assert.strictEqual(restored.worker.ide, 'Cursor');

assert.strictEqual(hydratePipelineState({ state: 'DONE', initialTask: 'old task' }), null);

assert.strictEqual(statusInitialTask('IDLE', 'old completed task'), '');
assert.strictEqual(statusInitialTask('BRAIN_PLAN', 'running task'), 'running task');

console.log('workflow_state tests passed');
