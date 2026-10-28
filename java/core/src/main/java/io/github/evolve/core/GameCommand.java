package io.github.evolve.core;

/** Client intent only. Costs, unlocks, resource changes and time stay on the authority. */
public sealed interface GameCommand permits GameCommand.Action, GameCommand.Pause {
    record Action(String actionId, int repetitions) implements GameCommand {
        public Action(String actionId) { this(actionId, 1); }
    }

    record Pause(boolean paused) implements GameCommand {}
}
