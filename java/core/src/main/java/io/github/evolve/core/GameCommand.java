package io.github.evolve.core;

/** Client intent only. Costs, unlocks, resource changes and time stay on the authority. */
public sealed interface GameCommand permits GameCommand.Action, GameCommand.Pause, GameCommand.AssignJob, GameCommand.AdjustSmelter {
    record Action(String actionId, int repetitions) implements GameCommand {
        public Action(String actionId) { this(actionId, 1); }
    }

    record Pause(boolean paused) implements GameCommand {}

    /** Positive delta recruits from unemployed citizens; negative delta returns workers. */
    record AssignJob(String jobId, int delta) implements GameCommand {}

    /** Adjust configured fuel (Wood/Coal) or product (Iron) slots; resource amounts remain server-owned. */
    record AdjustSmelter(String allocationId, int delta) implements GameCommand {}
}
