package io.github.evolve.core;

/** The same interface is used by a desktop preview and a future server-owned Minecraft session. */
public interface GameEngine {
    GameSnapshot snapshot();
    CommandResult execute(GameCommand command);

    /** 20 logical ticks per second. Never pass wall-clock deltas or client-supplied time. */
    void advanceTicks(int ticks);

    GameSave exportSave();
}
