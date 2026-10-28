package io.github.evolve.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Native, bounded human primitive rules from actions.js, tech.js and main.js. */
final class CivilizationRules {
    record Rule(String id, String category, String title, Predicate<EvolutionState> visible,
                Function<EvolutionState, Map<String, Double>> costs, Consumer<EvolutionState> effect,
                Function<EvolutionState, List<TextRef>> tooltip, String structure, String gathers) {}

    static final Set<String> RESOURCES = Set.of("RNA", "DNA", "Food", "Lumber", "Stone", "Knowledge", "Money", "human");
    static final Set<String> STRUCTURES = Set.of("basic_housing");
    static final Map<String, Rule> ALL = build();

    private CivilizationRules() {}

    static boolean supported(String species, EvolutionContext context) {
        return species.equals("human") && context.universe().equals("standard") &&
                context.biome().equals("grassland");
    }

    static Map<String, Double> traits(CivilizationState civilization) {
        var result = new LinkedHashMap<>(SpeciesCatalog.startingTraits("human"));
        if (!civilization.primaryGenus().equals("humanoid")) {
            // sentience(): non-primary genus traits are lowered once by setTraitRank.
            result.put("adaptable", 0.5);
            result.put("wasteful", 0.5);
        }
        return GameSnapshot.ordered(result);
    }

    private static void initialize(EvolutionState state) {
        state.resources.values().forEach(resource -> { resource.visible = false; resource.rate = 0; });
        state.resources.put("Food", new EvolutionState.Resource(0, 250, false));
        state.resources.put("Lumber", new EvolutionState.Resource(0, 200, true));
        state.resources.put("Stone", new EvolutionState.Resource(0, 200, false));
        state.resources.put("Knowledge", new EvolutionState.Resource(0, 100, false));
        state.resources.put("Money", new EvolutionState.Resource(0, 1000, false));
        state.resources.put("human", new EvolutionState.Resource(0, 0, false));
        state.traits.clear();
        state.traits.putAll(traits(state.civilization));
        state.phase = GameSnapshot.Phase.PRIMITIVE;
    }

    static void fastLoop(EvolutionState state) {
        var population = state.resources.get("human");
        var food = state.resources.get("Food");
        var knowledge = state.resources.get("Knowledge");
        double consume = population.amount * 0.5; // All current citizens have the default unemployed job.
        boolean fed = food.amount >= consume * 0.25;
        food.rate = consume == 0 ? 0 : -consume;
        food.addCapped(-consume * 0.25);
        if (!fed && population.amount > 0 && state.nextInt(10) == 0) population.amount--;
        if (fed && food.amount > 0 && population.amount < population.capacity &&
                state.nextRoll(population.amount * (3 - Math.pow(2, 0.25))) <= 0) population.amount++;
        // Keep the default job in sync at the atomic tick boundary (no other jobs yet).
        state.civilization = new CivilizationState(state.civilization.primaryGenus(), (int) population.amount);
        knowledge.rate = state.tech("primitive") >= 3 ? state.context.productionMultiplier() : 0;
        knowledge.addCapped(knowledge.rate * 0.25);
    }

    private static Map<String, Rule> build() {
        var rules = new LinkedHashMap<String, Rule>();
        rules.put("civilization-start", new Rule("civilization-start", "civilization", "ui_start_civilization",
                s -> s.phase == GameSnapshot.Phase.CIVILIZATION_READY && supported(s.species, s.context),
                s -> Map.of(), CivilizationRules::initialize,
                s -> List.of(TextRef.of("ui_start_civilization_desc")), null, null));
        gather(rules, "lumber", "Lumber", 0, "city_lumber", s -> List.of(TextRef.of("city_lumber_desc", 1)));
        gather(rules, "food", "Food", 1, "city_food", s -> List.of(TextRef.of("city_food_desc", 1)));
        gather(rules, "stone", "Stone", 2, "ui_gather_stone", s -> List.of(TextRef.of("ui_gather_stone_desc", 1)));
        research(rules, "club", 0, "primitive", 1, Map.of("Lumber", 5d), s -> s.resources.get("Food").visible = true);
        research(rules, "bone_tools", 1, "primitive", 2, Map.of("Food", 10d), s -> s.resources.get("Stone").visible = true);
        research(rules, "sundial", 2, "primitive", 3, Map.of("Lumber", 8d, "Stone", 10d), s -> s.resources.get("Knowledge").visible = true);
        research(rules, "housing", 3, "housing", 1, Map.of("Knowledge", 10d), s -> s.structures.put("basic_housing", 0));
        rules.put("city-basic_housing", new Rule("city-basic_housing", "residential", "city_basic_housing_title",
                s -> s.phase == GameSnapshot.Phase.PRIMITIVE && s.tech("housing") == 1,
                s -> {
                    int count = s.count("basic_housing");
                    var costs = new LinkedHashMap<String, Double>();
                    if (count >= 5) costs.put("Money", (double) Math.round(20 * Math.pow(1.17, count)));
                    costs.put("Lumber", (double) Math.round(10 * Math.pow(1.23, count)));
                    return costs;
                }, s -> {
                    s.addStructure("basic_housing");
                    s.resources.get("human").capacity++;
                    s.resources.get("human").visible = true;
                }, s -> List.of(TextRef.of("city_basic_housing_desc")), "basic_housing", null));
        return GameSnapshot.ordered(rules);
    }

    private static void gather(Map<String, Rule> rules, String id, String resource, int primitive, String title,
                               Function<EvolutionState, List<TextRef>> tooltip) {
        String actionId = "city-" + id;
        rules.put(actionId, new Rule(actionId, "outskirts", title,
                s -> s.phase == GameSnapshot.Phase.PRIMITIVE && s.tech("primitive") >= primitive,
                s -> Map.of(), s -> s.resources.get(resource).addCapped(1), tooltip, null, resource));
    }

    private static void research(Map<String, Rule> rules, String id, int primitive, String grant, int rank,
                                 Map<String, Double> costs, Consumer<EvolutionState> extra) {
        String actionId = "tech-" + id;
        rules.put(actionId, new Rule(actionId, "research", "tech_" + id,
                s -> s.phase == GameSnapshot.Phase.PRIMITIVE && s.tech("primitive") >= primitive && s.tech(grant) < rank,
                s -> costs, s -> { extra.accept(s); s.grant(grant, rank); },
                s -> List.of(TextRef.of("tech_" + id + "_desc")), null, null));
    }
}
