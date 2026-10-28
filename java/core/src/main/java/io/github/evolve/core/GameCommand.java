package io.github.evolve.core;

/** Client intent only. Costs, unlocks, resource changes and time stay on the authority. */
public sealed interface GameCommand permits GameCommand.Action, GameCommand.Pause, GameCommand.AssignJob, GameCommand.AdjustSmelter, GameCommand.Trade {
    record Action(String actionId, int repetitions) implements GameCommand {
        public Action(String actionId) { this(actionId, 1); }
    }

    record Pause(boolean paused) implements GameCommand {}

    /** Quantity is requested units, bounded by the currently supported manual market limit of 100. */
    record Trade(String resourceId, int quantity, boolean buy) implements GameCommand {}

    /** Positive delta recruits from unemployed citizens; negative delta returns workers. */
    record AssignJob(String jobId, int delta) implements GameCommand {}

    /** Adjust configured fuel (Wood/Coal) or product (Iron) slots; resource amounts remain server-owned. */
    record AdjustSmelter(String allocationId, int delta) implements GameCommand {}
}
