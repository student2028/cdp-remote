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

    // Test resolveStageCdp for heterogeneous pipeline
    const heteroEvents = [];
    const heteroTask = {
        id: 'task-hetero',
        isHeterogeneous: true,
        pipeline: [
            { targetIde: 'Cursor', targetPort: 9555, prompt: 'cursor prompt', delayMinutes: 0 },
            { targetIde: 'Devin', targetPort: 9444, prompt: 'devin prompt', delayMinutes: 0 }
        ]
    };

    const heteroResult = await executePipelineStages(heteroTask, {
        cdpPort: null,
        targetPid: null,
        resolveStageCdp: (stage) => {
            return { cdpPort: stage.targetPort, targetPid: stage.targetPort === 9444 ? 123 : null };
        },
        isActive: () => true,
        onStageChange: (idx) => heteroEvents.push(`stage:${idx}`),
        sleep: async () => {},
        switchModel: async () => {},
        sendMessage: async ({ message, cdpPort, targetPid }) => heteroEvents.push(`send:${message} to ${cdpPort} pid:${targetPid}`),
        waitForCompletion: async ({ stageIndex }) => heteroEvents.push(`wait:${stageIndex}`),
        log: () => {}
    });

    assert.deepStrictEqual(heteroResult, { ok: true, completedStages: 2 });
    assert.deepStrictEqual(heteroEvents, [
        'stage:0',
        'send:cursor prompt to 9555 pid:null',
        'wait:0',
        'stage:1',
        'send:devin prompt to 9444 pid:123',
        'wait:1',
        'stage:-1'
    ]);

    console.log('scheduler_pipeline tests passed');
})().catch((err) => {
    console.error(err);
    process.exit(1);
});
