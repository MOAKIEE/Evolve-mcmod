package io.github.evolve.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Package-private mutable state. Only the owning engine thread can reach it. */
final class EvolutionState {
    long elapsedTicks;
    long revision;
    int tickRemainder;
    int loopCycle;
    boolean paused;
    GameSnapshot.Phase phase = GameSnapshot.Phase.EVOLUTION;
    int randomSeed;
    String species = "protoplasm";
    String genus = "organism";
    int progress;
    final EvolutionContext context;
    final Map<String, Resource> resources = new LinkedHashMap<>();
    final Map<String, Integer> structures = new LinkedHashMap<>();
    final Map<String, Integer> technologies = new LinkedHashMap<>();
    final Map<String, Double> traits = new LinkedHashMap<>();

    EvolutionState(EvolutionContext context) { this.context = context; }

    int count(String id) { return structures.getOrDefault(id, 0); }
    int tech(String id) { return technologies.getOrDefault(id, 0); }
    double amount(String id) { return resources.get(id).amount; }
    boolean has(String id) { return structures.containsKey(id); }
    boolean selectingGenus() { return context.grandmaster() >= 5; }
    void grant(String id, int level) { technologies.put(id, level); }
    void addStructure(String id) { structures.put(id, count(id) + 1); }

    int nextInt(int bound) {
        // Multiplication intentionally uses long to match JS's exact Number arithmetic here.
        randomSeed = (int) (((long) randomSeed * 9301 + 49297) % 233280);
        return (int) ((long) randomSeed * bound / 233280);
    }

    void becomeSentient(String id) {
        species = id;
        genus = SpeciesCatalog.all().get(id).genus();
        traits.putAll(SpeciesCatalog.startingTraits(id));
        structures.clear();
        technologies.keySet().removeIf(key -> key.equals("evo") || key.startsWith("evo_"));
        resources.values().forEach(resource -> resource.visible = false);
        progress = 100;
        phase = GameSnapshot.Phase.CIVILIZATION_READY;
    }

    static final class Resource {
        double amount;
        double capacity;
        boolean visible;
        double rate;
        Resource(double amount, double capacity, boolean visible) {
            this.amount = amount;
            this.capacity = capacity;
            this.visible = visible;
        }
        void addCapped(double change) { amount = Math.max(0, Math.min(capacity, amount + change)); }
    }
}
