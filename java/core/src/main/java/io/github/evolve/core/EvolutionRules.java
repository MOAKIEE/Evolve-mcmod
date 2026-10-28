package io.github.evolve.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Native adaptation of src/actions.js evolution actions. No DOM or script evaluation. */
final class EvolutionRules {
    record Rule(String id, String titleKey, String descriptionKey, Predicate<EvolutionState> visible,
                Function<EvolutionState, Map<String, Double>> cost, Consumer<EvolutionState> effect,
                Function<EvolutionState, List<TextRef>> tooltip) {}

    static final List<String> STRUCTURES = List.of("membrane", "organelles", "nucleus", "eukaryotic_cell", "mitochondria");
    static final Set<String> TECHS = Set.of("evo", "evo_animal", "evo_plant", "evo_fungi", "evo_synthetic",
            "evo_insectoid", "evo_mammals", "evo_eggshell", "evo_eldritch", "evo_aquatic", "evo_fey",
            "evo_sand", "evo_heat", "evo_polar", "evo_humanoid", "evo_giant", "evo_small",
            "evo_animalism", "evo_demonic", "evo_angelic", "evo_carnivore", "evo_herbivore",
            "evo_omnivore", "evo_avian", "evo_reptilian");
    static final Map<String, Rule> ALL = build();

    private EvolutionRules() {}

    private static Map<String, Rule> build() {
        var result = new LinkedHashMap<String, Rule>();
        add(result, "rna", "resource_RNA_name", "evo_rna",
                s -> s.resources.get("RNA").visible, s -> Map.of(),
                s -> s.resources.get("RNA").addCapped(s.context.rapidMutation() ? 2 : 1),
                s -> List.of(TextRef.of("evo_rna", s.context.rapidMutation() ? 2 : 1)));
        add(result, "dna", "evo_dna_title", "evo_dna_desc",
                s -> s.resources.get("DNA").visible && s.amount("DNA") < s.resources.get("DNA").capacity,
                s -> Map.of("RNA", 2d), s -> s.resources.get("DNA").addCapped(1),
                s -> List.of(TextRef.of("evo_dna_desc"), TextRef.of("evo_dna_effect")));

        structure(result, "membrane", "membrane", 2, 2, 0, 0,
                s -> s.resources.get("RNA").capacity += 5 * (s.count("mitochondria") + 1),
                s -> TextRef.of("evo_membrane_effect", 5 * (s.count("mitochondria") + 1)));
        structure(result, "organelles", "organelles", 12, 8, 4, 4, s -> {},
                s -> TextRef.of("evo_organelles_effect", (s.context.rapidMutation() ? 2 : 1) + (s.tech("evo") >= 2 ? 1 : 0)));
        add(result, "nucleus", "evo_nucleus_title", "evo_nucleus_desc", s -> s.has("nucleus"),
                s -> costs(38 + s.count("nucleus") * (s.tech("evo") >= 4 ? 16 : 32),
                        18 + s.count("nucleus") * (s.tech("evo") >= 4 ? 12 : 16)),
                s -> s.addStructure("nucleus"),
                s -> List.of(TextRef.of("evo_nucleus_desc"), TextRef.of("evo_nucleus_effect", s.tech("evo") >= 5 ? 2 : 1)));
        structure(result, "eukaryotic_cell", "eukaryotic", 20, 20, 40, 12,
                s -> s.resources.get("DNA").capacity += 10 * (s.count("mitochondria") + 1),
                s -> TextRef.of("evo_eukaryotic_effect", 10 * (s.count("mitochondria") + 1)));
        structure(result, "mitochondria", "mitochondria", 75, 50, 65, 35, s -> {},
                s -> TextRef.of("evo_mitochondria_effect"));

        progress(result, "sexual_reproduction", 1, Map.of(), 150, 2, 20, s -> true, s -> {});
        progress(result, "phagocytosis", 2, Map.of(), 175, 3, 40, s -> true, s -> s.grant("evo_animal", 1));
        for (String id : List.of("chloroplasts", "chitin")) {
            String type = id.equals("chitin") ? "fungi" : "plant";
            progress(result, id, 2, Map.of(), 175, 3, 40, s -> true, s -> {
                s.grant("evo_" + type, s.selectingGenus() ? 2 : 1);
                if (s.selectingGenus()) { s.grant("evo", 7); s.progress = 100; }
            });
        }
        progress(result, "exterminate", 2, Map.of(), 200, 7, 100,
                s -> s.context.obsolete() >= 5, s -> s.grant("evo_synthetic", 2));
        progress(result, "multicellular", 3, Map.of(), 200, 4, 60, s -> true, s -> {});
        progress(result, "spores", 4, Map.of("evo_fungi", 1), 230, 5, 80, s -> true, s -> {});
        progress(result, "poikilohydric", 4, Map.of("evo_plant", 1), 230, 5, 80, s -> true, s -> {});
        progress(result, "bilateral_symmetry", 4, Map.of("evo_animal", 1), 230, 5, 80, s -> true, s -> {
            for (String type : List.of("insectoid", "mammals", "eggshell", "eldritch", "aquatic", "fey", "sand", "heat", "polar"))
                s.grant("evo_" + type, 1);
        });
        progress(result, "bryophyte", 5, Map.of(), 260, 7, 100,
                s -> s.tech("evo_plant") > 0 || s.tech("evo_fungi") > 0, s -> {
                    if (s.tech("evo_plant") > 0) s.grant("evo_plant", 2);
                    if (s.tech("evo_fungi") > 0) s.grant("evo_fungi", 2);
                });
        genus(result, "athropods", "insectoid", 5, s -> true);
        progress(result, "mammals", 5, Map.of("evo_mammals", 1), 245, 6, 90, s -> true, s -> {
            for (String type : List.of("humanoid", "giant", "small", "animalism", "demonic", "angelic")) s.grant("evo_" + type, 1);
        });
        genus(result, "humanoid", "humanoid", 6, s -> true);
        genus(result, "gigantism", "giant", 6, s -> true);
        genus(result, "dwarfism", "small", 6, s -> true);
        progress(result, "animalism", 6, Map.of("evo_animalism", 1), 250, 7, 95,
                s -> s.tech("evo_animalism") == 1, s -> s.grant("evo_animalism", 2));
        for (String type : List.of("carnivore", "herbivore")) {
            progress(result, type, 7, Map.of("evo_animalism", 2), 255, 7, 100,
                    s -> s.tech("evo_animalism") == 2, s -> {
                        s.grant("evo_" + type, 2);
                        s.grant("evo_animalism", 3);
                    });
        }
        // Upstream omnivore requires the deliberately unobtainable "locked" technology.
        add(result, "omnivore", "evo_omnivore_title", "evo_omnivore_desc", s -> false,
                s -> costs(0, 255), s -> {}, s -> List.of(TextRef.of("evo_omnivore_desc")));
        genus(result, "celestial", "angelic", 6, s -> s.context.biome().equals("eden") || s.context.unbound() >= 3);
        genus(result, "demonic", "demonic", 6, s -> s.context.biome().equals("hellscape") || s.context.unbound() >= 3);
        genus(result, "eldritch", "eldritch", 5, s -> s.context.nightmare());
        genus(result, "aquatic", "aquatic", 5, s -> biome(s, "oceanic", "swamp"));
        genus(result, "fey", "fey", 5, s -> biome(s, "forest", "swamp", "taiga"));
        genus(result, "heat", "heat", 5, s -> biome(s, "volcanic", "ashland"));
        genus(result, "polar", "polar", 5, s -> biome(s, "tundra", "taiga"));
        genus(result, "sand", "sand", 5, s -> biome(s, "desert", "ashland"));
        progress(result, "eggshell", 5, Map.of("evo_eggshell", 1), 245, 6, 90,
                s -> !s.selectingGenus(), s -> s.grant("evo_eggshell", 2));
        progress(result, "endothermic", 6, Map.of("evo_eggshell", 2), 260, 7, 100,
                s -> true, s -> s.grant("evo_avian", 2));
        progress(result, "ectothermic", 6, Map.of("evo_eggshell", 2), 260, 7, 100,
                s -> true, s -> s.grant("evo_reptilian", 2));
        add(result, "sentience", "evo_sentience_title", "evo_sentience_desc",
                s -> s.tech("evo") == 7 && s.progress == 100,
                s -> costs(300, 300), EvolutionRules::randomSentience,
                s -> List.of(TextRef.of("evo_sentience_desc"), TextRef.of("evo_sentience_effect")));

        for (var species : SpeciesCatalog.all().values()) {
            // Custom/hybrid species and synthetic imitation have additional upstream menus.
            if (species.genus().equals("hybrid") || species.id().equals("synth")) continue;
            add(result, species.id(), "race_" + species.id(), "race_" + species.id() + "_desc",
                    s -> s.tech("evo") >= 7 && s.tech("evo_" + species.genus()) >= 2 && s.progress == 100 &&
                            (s.context.seeded() || s.context.massExtinction() >= 1 || s.context.extinctSpecies().contains(species.id())),
                    s -> costs(320, 320), s -> s.becomeSentient(species.id()),
                    s -> List.of(TextRef.of("race_" + species.id() + "_desc")));
        }
        return GameSnapshot.ordered(result);
    }

    private static boolean biome(EvolutionState state, String... biomes) {
        return state.context.unbound() > 0 || List.of(biomes).contains(state.context.biome());
    }

    private static void randomSentience(EvolutionState state) {
        String genus = SpeciesCatalog.genusOrder().stream().filter(type -> state.tech("evo_" + type) >= 2)
                .findFirst().orElse("humanoid");
        var allowed = SpeciesCatalog.all().values().stream().filter(species -> species.genus().equals(genus)).toList();
        String id = allowed.get(state.nextInt(allowed.size())).id();
        if (state.context.extinctSpecies().contains(id)) id = allowed.get(state.nextInt(allowed.size())).id();
        state.becomeSentient(id);
    }

    private static void genus(Map<String, Rule> out, String id, String genus, int stage, Predicate<EvolutionState> condition) {
        progress(out, id, stage, Map.of("evo_" + genus, 1), 260, 7, 100, condition,
                s -> s.grant("evo_" + genus, 2));
    }

    private static void progress(Map<String, Rule> out, String id, int stage, Map<String, Integer> requirements,
                                 double dna, int next, int percent, Predicate<EvolutionState> extra,
                                 Consumer<EvolutionState> effect) {
        boolean exactStage = List.of("sexual_reproduction", "phagocytosis", "multicellular", "spores",
                "poikilohydric", "bilateral_symmetry", "mammals", "eggshell").contains(id);
        add(out, id, "evo_" + id + "_title", "evo_" + id + "_desc",
                s -> s.tech("evo") >= (id.equals("carnivore") || id.equals("herbivore") ? 0 : stage) &&
                        requirements.entrySet().stream().allMatch(e -> s.tech(e.getKey()) >= e.getValue()) &&
                        (exactStage ? s.tech("evo") == stage : (s.tech("evo") == stage || s.selectingGenus()) && s.progress < 100) && extra.test(s),
                s -> costs(0, dna), s -> {
                    s.grant("evo", next);
                    s.progress = percent;
                    effect.accept(s);
                }, s -> {
                    var lines = new ArrayList<TextRef>();
                    lines.add(TextRef.of("evo_" + id + "_desc"));
                    String effectKey = switch (id) {
                        case "spores", "poikilohydric", "bilateral_symmetry" -> "evo_nucleus_boost";
                        default -> "evo_" + id + "_effect";
                    };
                    lines.add(TextRef.of(effectKey));
                    return lines;
                });
    }

    private static void structure(Map<String, Rule> out, String id, String textName, double rna, double rnaScale,
                                  double dna, double dnaScale, Consumer<EvolutionState> extra,
                                  Function<EvolutionState, TextRef> effectText) {
        add(out, id, "evo_" + textName + "_title", "evo_" + textName + "_desc", s -> s.has(id),
                s -> costs(rna + rnaScale * s.count(id), dna + dnaScale * s.count(id)), s -> {
                    s.addStructure(id);
                    extra.accept(s);
                }, s -> List.of(TextRef.of("evo_" + textName + "_desc"), effectText.apply(s)));
    }

    private static void add(Map<String, Rule> out, String id, String title, String description,
                            Predicate<EvolutionState> visible, Function<EvolutionState, Map<String, Double>> cost,
                            Consumer<EvolutionState> effect, Function<EvolutionState, List<TextRef>> tooltip) {
        var rule = new Rule("evolution-" + id, title, description, visible, cost, effect, tooltip);
        if (out.put(rule.id, rule) != null) throw new IllegalStateException("Duplicate action: " + id);
    }

    static Map<String, Double> costs(double rna, double dna) {
        var result = new LinkedHashMap<String, Double>();
        if (rna > 0) result.put("RNA", rna);
        if (dna > 0) result.put("DNA", dna);
        return result;
    }
}
