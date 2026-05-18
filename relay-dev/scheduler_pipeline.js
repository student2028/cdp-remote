async function executePipelineStages(task, deps) {
    const stages = Array.isArray(task.pipeline) ? task.pipeline : [];
    let completedStages = 0;
    const isActive = deps.isActive || (() => true);
    const onStageChange = deps.onStageChange || (() => {});
    const sleep = deps.sleep || ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
    const log = deps.log || (() => {});

    for (let stageIndex = 0; stageIndex < stages.length; stageIndex++) {
        if (!isActive()) {
            onStageChange(-1);
            return { ok: false, cancelled: true, completedStages };
        }

        const stage = stages[stageIndex] || {};
        onStageChange(stageIndex);

        if (stageIndex > 0 && stage.delayMinutes && stage.delayMinutes > 0) {
            const delayMs = stage.delayMinutes * 60 * 1000;
            log(`⏱ [${task.id}] 阶段 ${stageIndex + 1}: 上一阶段完成后等待 ${stage.delayMinutes} 分钟...`);
            await sleep(delayMs);
            if (!isActive()) {
                onStageChange(-1);
                return { ok: false, cancelled: true, completedStages };
            }
        }

        const model = String(stage.model || '').trim();
        if (model) {
            await deps.switchModel({
                task,
                stage,
                stageIndex,
                cdpPort: deps.cdpPort,
                targetPid: deps.targetPid,
                model
            });
        }

        await deps.sendMessage({
            task,
            stage,
            stageIndex,
            cdpPort: deps.cdpPort,
            targetPid: deps.targetPid,
            message: stage.prompt
        });

        await deps.waitForCompletion({
            task,
            stage,
            stageIndex,
            cdpPort: deps.cdpPort,
            targetPid: deps.targetPid
        });

        completedStages += 1;
    }

    onStageChange(-1);
    return { ok: true, completedStages };
}

module.exports = {
    executePipelineStages
};
