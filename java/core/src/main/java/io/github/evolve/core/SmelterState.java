package io.github.evolve.core;

/** Configured fuel and iron slots; effective operation is recomputed per fastLoop. */
public record SmelterState(int wood, int coal, int iron) {
    public SmelterState(int wood, int iron) { this(wood, 0, iron); }
    public static SmelterState empty() { return new SmelterState(0, 0, 0); }
}
