package io.github.evolve.core;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Basic foundry recipes; manual crafting and shared craftsmen use authoritative costs. */
final class CraftingRules {
    record Recipe(String resource, String ingredient, int cost) {
        String job() { return "craft_" + resource; }
    }
    static final List<Recipe> RECIPES = List.of(new Recipe("Plywood", "Lumber", 100),
            new Recipe("Brick", "Cement", 40), new Recipe("Wrought_Iron", "Iron", 80));
    static final Set<String> RESOURCES = Set.of("Plywood", "Brick", "Wrought_Iron");

    private CraftingRules() {}

    static Recipe recipeForJob(String job) {
        return RECIPES.stream().filter(r -> r.job().equals(job)).findFirst().orElse(null);
    }

    static int workers(EvolutionState state) {
        return RECIPES.stream().mapToInt(r -> state.civilization.workers().get(r.job())).sum();
    }

    static int cost(EvolutionState state, Recipe recipe) {
        // Human wasteful rank 1 costs +10%; the non-primary humanoid rank 0.5 costs +12%.
        return (int) Math.round(recipe.cost() * (state.traits.get("wasteful") == 0.5 ? 1.12 : 1.10));
    }

    static double ratio(EvolutionState state) { return 1 + (state.tech("foundry") >= 2 ? state.count("foundry") * 0.03 : 0); }

    static void install(Map<String, CivilizationRules.Rule> rules) {
        for (var recipe : RECIPES) {
            String id = "craft-" + recipe.resource();
            rules.put(id, new CivilizationRules.Rule(id, "crafting", "ui_craft_" + recipe.resource(),
                    s -> s.phase == GameSnapshot.Phase.PRIMITIVE && s.count("foundry") > 0 && s.resources.get(recipe.resource()).visible,
                    s -> Map.of(recipe.ingredient(), (double) cost(s, recipe)),
                    s -> s.resources.get(recipe.resource()).addCapped(ratio(s)),
                    s -> List.of(TextRef.of("ui_craft_output", ratio(s))), null, recipe.resource()));
        }
    }

    static void fastLoop(EvolutionState state) {
        for (var recipe : RECIPES) {
            // The original checks one second of input before consuming a quarter-second amount.
            int count = Math.min(state.civilization.workers().get(recipe.job()),
                    (int) Math.min(1_000_000, Math.floor(state.amount(recipe.ingredient()) / (cost(state, recipe) / 140d))));
            double consume = count * cost(state, recipe) / 140d;
            var ingredient = state.resources.get(recipe.ingredient());
            ingredient.addCapped(-consume * 0.25);
            ingredient.rate -= consume;
            var output = state.resources.get(recipe.resource());
            output.rate = ratio(state) * count / 140d;
            output.addCapped(output.rate * 0.25);
        }
    }
}
