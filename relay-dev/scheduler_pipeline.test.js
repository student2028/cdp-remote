const assert = require('assert');
const { executePipelineStages } = require('./scheduler_pipeline');

(async () => {
    const events = [];
    const task = {
        id: 'task-test',
        targetIde: 'Codex',
        targetPort: 9666,
        fixedSessionTitle: '',
        pipeline: [
            { model: 'GPT-5.4', prompt: 'first prompt', delayMinutes: 0 },
            { model: 'GPT-5.5', prompt: 'second prompt', delayMinutes: 1 }
        ]
    };

    const result = await executePipelineStages(task, {
        cdpPort: 9666,
        targetPid: null,
        isActive: () => true,
        onStageChange: (idx) => events.push(`stage:${idx}`),
        sleep: async (ms) => events.push(`sleep:${ms}`),
        switchModel: async ({ model }) => events.push(`switch:${model}`),
        sendMessage: async ({ message }) => events.push(`send:${message}`),
        waitForCompletion: async ({ stageIndex }) => events.push(`wait:${stageIndex}`),
        log: () => {}
    });

    assert.deepStrictEqual(result, { ok: true, completedStages: 2 });
    assert.deepStrictEqual(events, [
        'stage:0',
        'switch:GPT-5.4',
        'send:first prompt',
        'wait:0',
        'stage:1',
        'sleep:60000',
        'switch:GPT-5.5',
        'send:second prompt',
        'wait:1',
        'stage:-1'
    ]);

    const cancelledEvents = [];
    const cancelled = await executePipelineStages(task, {
        cdpPort: 9666,
        targetPid: null,
        isActive: () => !cancelledEvents.includes('wait:0'),
        onStageChange: (idx) => cancelledEvents.push(`stage:${idx}`),
        sleep: async () => {},
        switchModel: async ({ model }) => cancelledEvents.push(`switch:${model}`),
        sendMessage: async ({ message }) => cancelledEvents.push(`send:${message}`),
        waitForCompletion: async ({ stageIndex }) => cancelledEvents.push(`wait:${stageIndex}`),
        log: () => {}
    });

    assert.deepStrictEqual(cancelled, { ok: false, cancelled: true, completedStages: 1 });
    assert.ok(!cancelledEvents.includes('switch:GPT-5.5'));

    console.log('scheduler_pipeline tests passed');
})().catch((err) => {
    console.error(err);
    process.exit(1);
});
