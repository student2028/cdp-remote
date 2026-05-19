const assert = require('assert');
const {
    defaultSchedulerModelOptions,
    sanitizeModelOptions
} = require('./scheduler_model_options');

assert.deepStrictEqual(
    sanitizeModelOptions([' Gemini 3.1 Pro ', '', 'Gemini 3.1 Pro', null, 'Claude Sonnet 4.6', '✓ deepseek-v4-flash']),
    ['Gemini 3.1 Pro', 'Claude Sonnet 4.6', 'deepseek-v4-flash']
);

assert.ok(defaultSchedulerModelOptions('DSME').includes('Gemini 3.1 Pro'));
assert.ok(defaultSchedulerModelOptions('Codex').includes('GPT-5.5'));
assert.deepStrictEqual(defaultSchedulerModelOptions('uitty:26954'), []);

console.log('scheduler_model_options tests passed');
