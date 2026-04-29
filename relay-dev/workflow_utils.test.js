const assert = require('assert');
const { parseBrainVerdict, parseBrainTask } = require('./workflow_utils');

const plan = parseBrainTask(`
analysis...
---TASK_START---
Implement the first workflow slice with tests.
---TASK_END---
`);
assert.strictEqual(plan, 'Implement the first workflow slice with tests.');

const rework = parseBrainVerdict(`
---
REVIEW_ROUND: 1
VERDICT: NEEDS_REWORK
NEXT_ACTION: REVIEW
ISSUES:
- [major] Missing failure handling / crashes on timeout / add fallback
SUMMARY: Needs one fix
---
`);
assert.strictEqual(rework.verdict, 'NEEDS_REWORK');
assert.strictEqual(rework.nextAction, 'REVIEW');
assert.match(rework.issues, /Missing failure handling/);

const nextTask = parseBrainVerdict(`
---
REVIEW_ROUND: 2
VERDICT: PASS
NEXT_ACTION: TASK
ISSUES:
NONE
SUMMARY: Current slice passes
---NEXT_TASK_START---
Build the next slice and keep the same commit trailers.
---NEXT_TASK_END---
---
`);
assert.strictEqual(nextTask.verdict, 'PASS');
assert.strictEqual(nextTask.nextAction, 'TASK');
assert.strictEqual(nextTask.nextTask, 'Build the next slice and keep the same commit trailers.');

const done = parseBrainVerdict(`
VERDICT: PASS
NEXT_ACTION: DONE
ISSUES:
NONE
SUMMARY: All requirements are complete
`);
assert.strictEqual(done.nextAction, 'DONE');

console.log('workflow_utils tests passed');
