package io.github.evolve.core;

import java.util.Map;

/** Portable civilization metadata and work assignments; no client-owned mutable state. */
public record CivilizationState(String primaryGenus, int unemployed,
                                Map<String, Integer> workers, boolean capacitiesInitialized) {
    public CivilizationState { workers = GameSnapshot.ordered(workers); }
    public static CivilizationState empty() { return handoff(""); }
    public static CivilizationState handoff(String primaryGenus) {
        return new CivilizationState(primaryGenus, 0, Map.of(), false);
    }
}
