const fs = require('fs');
const path = require('path');

const { parseBrainVerdict } = require('./workflow_utils.js');

function assert(condition, msg) {
    if (!condition) throw new Error(msg);
}

// Test PASS
const testPass = `
...some text...
VERDICT: PASS
ISSUES: No major issues.
`;
const resPass = parseBrainVerdict(testPass);
assert(resPass.verdict === 'PASS', "Expected PASS");
assert(resPass.issues === 'No major issues.', "Expected correct issues");

// Test NEEDS_REWORK
const testRework = `
VERDICT: NEEDS_REWORK
ISSUES: 
- missing tests
- bad formatting
---
Summary below
`;
const resRework = parseBrainVerdict(testRework);
assert(resRework.verdict === 'NEEDS_REWORK', "Expected NEEDS_REWORK");
assert(resRework.issues.includes('missing tests'), "Expected issues text");

// Test missing verdict
assert(parseBrainVerdict("Some random text") === null, "Expected null");

console.log("parseBrainVerdict tests passed!");
