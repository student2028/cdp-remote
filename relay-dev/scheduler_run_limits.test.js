const assert = require('assert');
const {
    getMaxRuns,
    getCompletedRuns,
    shouldSkipScheduledRun,
    markRunCompleted
} = require('./scheduler_run_limits');

const counts = new Map();

assert.strictEqual(getMaxRuns({}), 0);
assert.strictEqual(getMaxRuns({ maxRuns: 3 }), 3);
assert.strictEqual(getMaxRuns({ maxRuns: '4' }), 4);
assert.strictEqual(getMaxRuns({ maxRuns: -1 }), 0);

assert.strictEqual(getCompletedRuns({ id: 'a', executionCount: 2 }, counts), 2);
counts.set('a', 5);
assert.strictEqual(getCompletedRuns({ id: 'a', executionCount: 2 }, counts), 5);

assert.strictEqual(shouldSkipScheduledRun({ id: 'b', maxRuns: 0 }, counts), false);
assert.strictEqual(shouldSkipScheduledRun({ id: 'b', maxRuns: 2, executionCount: 1 }, counts), false);
assert.strictEqual(shouldSkipScheduledRun({ id: 'b', maxRuns: 2, executionCount: 2 }, counts), true);

const task = { id: 'c', maxRuns: 2, executionCount: 0 };
let r = markRunCompleted(task, counts);
assert.deepStrictEqual(r, { completedRuns: 1, maxRuns: 2, reachedLimit: false });
r = markRunCompleted(task, counts);
assert.deepStrictEqual(r, { completedRuns: 2, maxRuns: 2, reachedLimit: true });

console.log('scheduler_run_limits tests passed');
