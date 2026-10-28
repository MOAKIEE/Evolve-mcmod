package io.github.evolve.core;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Static upstream data, extracted at development time without evaluating gameplay scripts. */
public final class SpeciesCatalog {
    public record Species(String id, String genus, Map<String, Double> traits) {
        public Species { traits = GameSnapshot.ordered(traits); }
    }

    private static final Properties GENERA = load("/evolve/genus.properties");
    private static final Properties SPECIES = load("/evolve/species.properties");
    private static final List<String> GENUS_ORDER = List.of(GENERA.getProperty("order").split(","));
    private static final Map<String, Species> ALL = createSpecies();

    private SpeciesCatalog() {}

    public static Map<String, Species> all() { return ALL; }
    public static List<String> genusOrder() { return GENUS_ORDER; }

    public static Map<String, Double> startingTraits(String id) {
        Species species = ALL.get(id);
        if (species == null) throw new IllegalArgumentException("Unknown species: " + id);
        var result = parseTraits(GENERA.getProperty(species.genus + ".traits", ""));
        result.putAll(species.traits);
        return GameSnapshot.ordered(result);
    }

    private static Map<String, Species> createSpecies() {
        var result = new LinkedHashMap<String, Species>();
        for (String id : SPECIES.getProperty("order").split(",")) {
            result.put(id, new Species(id, SPECIES.getProperty(id + ".genus"),
                    parseTraits(SPECIES.getProperty(id + ".traits"))));
        }
        return GameSnapshot.ordered(result);
    }

    private static LinkedHashMap<String, Double> parseTraits(String text) {
        var result = new LinkedHashMap<String, Double>();
        if (!text.isEmpty()) Arrays.stream(text.split(",")).forEach(pair -> {
            String[] fields = pair.split(":");
            result.put(fields[0], Double.parseDouble(fields[1]));
        });
        return result;
    }

    private static Properties load(String name) {
        try (var stream = SpeciesCatalog.class.getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("Missing packaged data: " + name);
            var properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return properties;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load packaged data: " + name, e);
        }
    }
}
