function getMaxRuns(task) {
    const raw = Number(task?.maxRuns || 0);
    return Number.isFinite(raw) && raw > 0 ? Math.floor(raw) : 0;
}

function getCompletedRuns(task, counts) {
    if (task?.id && counts?.has(task.id)) {
        return Number(counts.get(task.id)) || 0;
    }
    const raw = Number(task?.executionCount || 0);
    return Number.isFinite(raw) && raw > 0 ? Math.floor(raw) : 0;
}

function shouldSkipScheduledRun(task, counts) {
    const maxRuns = getMaxRuns(task);
    if (maxRuns <= 0) return false;
    return getCompletedRuns(task, counts) >= maxRuns;
}

function markRunCompleted(task, counts) {
    const completedRuns = getCompletedRuns(task, counts) + 1;
    if (task?.id && counts) counts.set(task.id, completedRuns);
    const maxRuns = getMaxRuns(task);
    return {
        completedRuns,
        maxRuns,
        reachedLimit: maxRuns > 0 && completedRuns >= maxRuns
    };
}

module.exports = {
    getMaxRuns,
    getCompletedRuns,
    shouldSkipScheduledRun,
    markRunCompleted
};
