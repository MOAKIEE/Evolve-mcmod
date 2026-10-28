package io.github.evolve.core;

import java.util.Map;

/** Portable civilization metadata and work assignments; no client-owned mutable state. */
public record CivilizationState(String primaryGenus, int unemployed,
                                Map<String, Integer> workers, Map<String, Integer> assigned,
                                boolean capacitiesInitialized, SmelterState smelter) {
    public CivilizationState {
        workers = GameSnapshot.ordered(workers);
        assigned = GameSnapshot.ordered(assigned);
    }
    public CivilizationState(String primaryGenus, int unemployed, Map<String, Integer> workers, boolean capacitiesInitialized) {
        this(primaryGenus, unemployed, workers, workers, capacitiesInitialized);
    }
    public CivilizationState(String primaryGenus, int unemployed, Map<String, Integer> workers,
                             Map<String, Integer> assigned, boolean capacitiesInitialized) {
        this(primaryGenus, unemployed, workers, assigned, capacitiesInitialized, SmelterState.empty());
    }
    public static CivilizationState empty() { return handoff(""); }
    public static CivilizationState handoff(String primaryGenus) {
        return new CivilizationState(primaryGenus, 0, Map.of(), false);
    }
}
