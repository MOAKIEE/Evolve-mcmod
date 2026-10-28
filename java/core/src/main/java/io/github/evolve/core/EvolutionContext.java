package io.github.evolve.core;

import java.util.Set;

/**
 * Inputs supplied by a future world/prestige system, not client commands.
 * Perk ranks are already limited by their corresponding upstream achievements.
 * productionMultiplier represents the upstream global multiplier, whose prestige
 * calculation is outside this module. It also supplies primitive sundial
 * and worker production; civilization morale/prestige multipliers are not calculated here.
 */
public record EvolutionContext(String biome, String universe, boolean seeded,
                               boolean rapidMutation, int massExtinction,
                               int novice, int master, int grandmaster,
                               int unbound, int obsolete, boolean nightmare,
                               Set<String> extinctSpecies, double productionMultiplier) {
    private static final Set<String> BIOMES = Set.of("grassland", "forest", "desert", "oceanic",
            "tundra", "taiga", "swamp", "ashland", "volcanic", "hellscape", "eden");
    private static final Set<String> UNIVERSES = Set.of("standard", "evil", "antimatter", "heavy",
            "micro", "magic");

    public EvolutionContext {
        if (!BIOMES.contains(biome) || !UNIVERSES.contains(universe))
            throw new IllegalArgumentException("Unsupported biome or universe");
        checkRank(massExtinction, 5);
        checkRank(novice, 5);
        checkRank(master, 5);
        checkRank(grandmaster, 5);
        checkRank(unbound, 3);
        checkRank(obsolete, 5);
        extinctSpecies = Set.copyOf(extinctSpecies);
        if (!Double.isFinite(productionMultiplier) || productionMultiplier <= 0 || productionMultiplier > 1e12)
            throw new IllegalArgumentException("Invalid production multiplier");
    }

    public static EvolutionContext fresh() {
        return new EvolutionContext("grassland", "standard", false, false,
                0, 0, 0, 0, 0, 0, false, Set.of(), 1);
    }

    private static void checkRank(int rank, int max) {
        if (rank < 0 || rank > max) throw new IllegalArgumentException("Invalid perk rank");
    }
}
