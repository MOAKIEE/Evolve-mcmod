package io.github.evolve.core;

/** Portable civilization handoff metadata and the currently supported default job. */
public record CivilizationState(String primaryGenus, int unemployed) {
    public static CivilizationState empty() { return new CivilizationState("", 0); }
}
