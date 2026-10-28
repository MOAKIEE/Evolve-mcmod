package io.github.evolve.core;

import java.util.Map;

/** Versioned portable state, containing no renderer, scheduler, IO or Minecraft objects. */
public record GameSave(int schemaVersion, String ruleset, long elapsedTicks, long revision,
                       int tickRemainder, int loopCycle, boolean paused,
                       GameSnapshot.Phase phase, int randomSeed, String species, String genus,
                       int progress, EvolutionContext context,
                       Map<String, ResourceState> resources, Map<String, Integer> structures,
                       Map<String, Integer> technologies, Map<String, Double> traits,
                       CivilizationState civilization) {
    public static final int SCHEMA_VERSION = 9;
    public static final String RULESET = "evolve-1.4.10/cement-1";

    public GameSave {
        resources = GameSnapshot.ordered(resources);
        structures = GameSnapshot.ordered(structures);
        technologies = GameSnapshot.ordered(technologies);
        traits = GameSnapshot.ordered(traits);
    }

    public record ResourceState(double amount, double capacity, boolean visible, double changePerSecond) {}
}
