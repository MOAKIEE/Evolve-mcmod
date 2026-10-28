package io.github.evolve.core;

/** A batch commits one action at a time, rechecking cost and visibility after each success. */
public record CommandResult(Status status, int applied, long revision) {
    public enum Status {
        OK, INVALID_COMMAND, UNKNOWN_ACTION, LOCKED, UNAFFORDABLE, STORAGE_TOO_SMALL,
        RESOURCE_FULL, PAUSED, PHASE_COMPLETE, NO_AVAILABLE_WORKERS
    }
}
