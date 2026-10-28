package io.github.evolve.core;

/** Configured wood fuel slots and iron production slots; effective operation is recomputed per fastLoop. */
public record SmelterState(int wood, int iron) {
    public static SmelterState empty() { return new SmelterState(0, 0); }
}
