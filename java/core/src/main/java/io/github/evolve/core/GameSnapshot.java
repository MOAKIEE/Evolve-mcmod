package io.github.evolve.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deeply immutable UI data, safe to hand to a renderer or encode into an S2C packet. */
public record GameSnapshot(long revision, long elapsedTicks, Phase phase, boolean paused,
                           String species, String genus, int progress,
                           List<ResourceView> resources, List<ActionView> actions,
                           Map<String, Integer> technologies, Map<String, Double> traits) {
    public enum Phase { EVOLUTION, CIVILIZATION_READY }

    public GameSnapshot {
        resources = List.copyOf(resources);
        actions = List.copyOf(actions);
        technologies = ordered(technologies);
        traits = ordered(traits);
    }

    public record ResourceView(String id, TextRef name, double amount, double capacity,
                               double changePerSecond, boolean visible) {}

    public record ActionView(String id, String category, TextRef title, List<TextRef> tooltip,
                             int count, Map<String, Double> costs,
                             boolean affordable, boolean fitsStorage) {
        public ActionView {
            tooltip = List.copyOf(tooltip);
            costs = ordered(costs);
        }
        public boolean enabled() { return affordable && fitsStorage; }
    }

    static <K, V> Map<K, V> ordered(Map<K, V> input) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }
}
