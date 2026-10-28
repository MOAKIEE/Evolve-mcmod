package io.github.evolve.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    static final Set<String> RESOURCES = Set.of("RNA", "DNA", "Food", "Lumber", "Stone", "Knowledge", "Money", "Copper", "Iron", "human");
    static final Set<String> STRUCTURES = Set.of("basic_housing", "farm", "lumber_yard", "rock_quarry", "shed", "university", "bank", "mine", "smelter");
    static final List<String> JOBS = List.of("farmer", "lumberjack", "quarry_worker", "miner", "professor", "banker");
    static final Map<String, Integer> TECH_MAX = Map.ofEntries(Map.entry("primitive", 3), Map.entry("housing", 1),
            Map.entry("agriculture", 2), Map.entry("currency", 1), Map.entry("axe", 3), Map.entry("mining", 3),
            Map.entry("storage", 1), Map.entry("science", 1), Map.entry("banking", 2), Map.entry("pickaxe", 2), Map.entry("smelting", 1));
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
        state.resources.put("Copper", new EvolutionState.Resource(0, 100, false));
        state.resources.put("Iron", new EvolutionState.Resource(0, 100, false));
        state.resources.put("human", new EvolutionState.Resource(0, 0, false));
        state.traits.clear();
        state.traits.putAll(traits(state.civilization));
        var workers = new LinkedHashMap<String, Integer>();
        JOBS.forEach(job -> workers.put(job, 0));
        state.civilization = new CivilizationState(state.civilization.primaryGenus(), 0, workers, false);
        state.phase = GameSnapshot.Phase.PRIMITIVE;
    }

    static boolean jobVisible(EvolutionState state, String job) {
        return switch (job) {
            case "farmer" -> state.count("farm") > 0;
            case "lumberjack" -> state.tech("axe") >= 1;
            case "quarry_worker" -> state.count("rock_quarry") > 0;
            case "professor" -> state.count("university") > 0;
            case "banker" -> state.tech("banking") >= 2;
            case "miner" -> state.count("mine") > 0;
            default -> false;
        };
    }

    static int jobCapacity(EvolutionState state, String job) {
        return switch (job) {
            case "professor" -> state.count("university");
            case "banker" -> state.count("bank");
            case "miner" -> state.count("mine");
            default -> -1;
        };
    }

    static void updateCapacities(EvolutionState state) {
        state.resources.get("Food").capacity = 1000;
        state.resources.get("Lumber").capacity = 200 + 100d * state.count("lumber_yard") + 300d * state.count("shed");
        state.resources.get("Stone").capacity = 200 + 100d * state.count("rock_quarry") + (state.tech("primitive") >= 2 ? 300d * state.count("shed") : 0);
        state.resources.get("Knowledge").capacity = 100 + 500d * state.count("university");
        state.resources.get("Money").capacity = 1000 + 1800d * state.count("bank");
        state.resources.get("Copper").capacity = 100 + (state.resources.get("Copper").visible ? 90d * state.count("shed") : 0);
        state.resources.get("Iron").capacity = 100 + (state.resources.get("Iron").visible ? 125d * state.count("shed") : 0);
        var civ = state.civilization;
        state.civilization = new CivilizationState(civ.primaryGenus(), civ.unemployed(), civ.workers(), civ.assigned(), true, civ.smelter());
    }

    static void fastLoop(EvolutionState state) {
        refillJobs(state);
        var population = state.resources.get("human");
        var food = state.resources.get("Food");
        var knowledge = state.resources.get("Knowledge");
        var workers = state.civilization.workers();
        int unemployed = state.civilization.unemployed();
        int farmers = Math.min(workers.get("farmer"), state.count("farm"));
        int farmhands = workers.get("farmer") - farmers;
        double multiplier = state.context.productionMultiplier();
        // Neutral weather until the calendar/weather subsystem is migrated.
        double generated = (farmers * (0.82 + (state.tech("agriculture") >= 2 ? 1.15 : 0.65)) + farmhands * 0.82) * 1.2 * multiplier;
        double consume = population.amount - unemployed * 0.5;
        double delta = generated - consume;
        boolean fed = food.amount + delta * 0.25 >= 0;
        food.rate = delta == 0 ? 0 : delta;
        food.addCapped(delta * 0.25);
        if (!fed && population.amount > 0 && state.nextInt(10) == 0 && generated < consume / 1.25) population.amount--;
        if (fed && food.amount > 0 && population.amount < population.capacity &&
                state.nextRoll(population.amount * (3 - Math.pow(2, 0.25))) <= 0) { population.amount++; unemployed++; }
        double hunger = fed ? 1 : 0.5;
        knowledge.rate = (workers.get("professor") * 0.5 * hunger + (state.tech("primitive") >= 3 ? 1 : 0)) * multiplier;
        knowledge.addCapped(knowledge.rate * 0.25);
        var smelting = smelt(state);
        produce(state, "Lumber", workers.get("lumberjack") * (1 + (state.tech("axe") > 1 ? (state.tech("axe") - 1) * 0.35 : 0)) *
                (1 + state.count("lumber_yard") * 0.02) * hunger * multiplier);
        state.resources.get("Lumber").rate -= smelting.woodPerSecond();
        produce(state, "Stone", workers.get("quarry_worker") * (1 + state.count("rock_quarry") * 0.02) * hunger * multiplier);
        produce(state, "Copper", workers.get("miner") * (1 + state.tech("pickaxe") * 0.15) * (1d / 7) * hunger * multiplier);
        produce(state, "Iron", state.resources.get("Iron").visible ? workers.get("miner") * (1 + state.tech("pickaxe") * 0.15) * 0.25 *
                (1 + smelting.iron() * 0.1) * hunger * multiplier : 0);
        // The original income block runs after population changes and rounds each fastLoop deposit.
        double income = state.tech("currency") > 0 ? (population.amount - unemployed) * 0.4 : 0;
        if (fed && state.tech("banking") >= 2) income *= 1 + workers.get("banker") * 0.1;
        income *= hunger * multiplier;
        var money = state.resources.get("Money");
        // Match Number.toFixed(2) on the exact binary double, including negative deposits.
        double deposit = new BigDecimal(income * 0.25).setScale(2, RoundingMode.HALF_UP).doubleValue();
        money.rate = deposit == 0 ? 0 : deposit * 4;
        money.addCapped(deposit);
        reconcileWorkers(state, unemployed);
    }

    private static void refillJobs(EvolutionState state) {
        var civ = state.civilization;
        var workers = new LinkedHashMap<>(civ.workers());
        int unemployed = civ.unemployed();
        // Original fastLoop replenishes each finite job by at most one worker, before production.
        for (String job : JOBS) {
            int capacity = jobCapacity(state, job);
            if (unemployed > 0 && workers.get(job) < capacity && workers.get(job) < civ.assigned().get(job)) {
                workers.put(job, workers.get(job) + 1);
                unemployed--;
            }
        }
        state.civilization = new CivilizationState(civ.primaryGenus(), unemployed, workers, civ.assigned(), civ.capacitiesInitialized(), civ.smelter());
    }

    private record Smelting(int iron, double woodPerSecond) {}

    static void setSmelter(EvolutionState state, SmelterState smelter) {
        var civ = state.civilization;
        state.civilization = new CivilizationState(civ.primaryGenus(), civ.unemployed(), civ.workers(), civ.assigned(), civ.capacitiesInitialized(), smelter);
    }

    private static Smelting smelt(EvolutionState state) {
        if (state.count("smelter") == 0) return new Smelting(0, 0);
        var current = state.civilization.smelter();
        int iron = current.iron() < current.wood() ? current.iron() + 1 : current.iron();
        setSmelter(state, new SmelterState(current.wood(), iron));
        int operable = Math.min(current.wood(), (int) Math.floor(state.amount("Lumber") / 0.75));
        double consume = operable * 3d;
        state.resources.get("Lumber").addCapped(-consume * 0.25);
        return new Smelting(Math.max(0, iron - (current.wood() - operable)), consume);
    }

    private static void produce(EvolutionState state, String id, double rate) {
        state.resources.get(id).rate = rate;
        state.resources.get(id).addCapped(rate * 0.25);
    }

    private static void reconcileWorkers(EvolutionState state, int unemployed) {
        // Resolve deaths at the atomic tick boundary: release unemployed first, then the last job.
        var workers = new LinkedHashMap<>(state.civilization.workers());
        int excess = unemployed + workers.values().stream().mapToInt(Integer::intValue).sum() - (int) state.amount("human");
        int released = Math.min(unemployed, Math.max(0, excess));
        unemployed -= released;
        excess -= released;
        for (String job : JOBS.reversed()) {
            int loss = Math.min(workers.get(job), Math.max(0, excess));
            workers.put(job, workers.get(job) - loss);
            excess -= loss;
        }
        var civ = state.civilization;
        state.civilization = new CivilizationState(civ.primaryGenus(), unemployed, workers, civ.assigned(), civ.capacitiesInitialized(), civ.smelter());
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
        research(rules, "agriculture", 3, "agriculture", 1, Map.of("Knowledge", 10d), s -> s.structures.put("farm", 0));
        research(rules, "irrigation", Map.of("agriculture", 1), "agriculture", 2, Map.of("Knowledge", 55d), s -> {});
        research(rules, "currency", Map.of("housing", 1), "currency", 1, Map.of("Knowledge", 22d, "Lumber", 10d), s -> s.resources.get("Money").visible = true);
        research(rules, "stone_axe", 3, "axe", 1, Map.of("Knowledge", 45d, "Lumber", 20d, "Stone", 20d), s -> s.structures.put("lumber_yard", 0));
        research(rules, "mining", 3, "mining", 1, Map.of("Knowledge", 45d), s -> s.structures.put("rock_quarry", 0));
        research(rules, "storage", Map.of("primitive", 3, "currency", 1), "storage", 1, Map.of("Knowledge", 20d), s -> s.structures.put("shed", 0));
        research(rules, "science", Map.of("housing", 1), "science", 1, Map.of("Knowledge", 65d), s -> s.structures.put("university", 0));
        research(rules, "banking", Map.of("currency", 1), "banking", 1, Map.of("Knowledge", 90d), s -> s.structures.put("bank", 0));
        research(rules, "investing", Map.of("banking", 1), "banking", 2, Map.of("Money", 2500d, "Knowledge", 900d), s -> {});
        research(rules, "metal_working", Map.of("mining", 1), "mining", 2, Map.of("Knowledge", 350d), s -> s.structures.put("mine", 0));
        research(rules, "copper_axes", Map.of("axe", 1, "mining", 2), "axe", 2, Map.of("Knowledge", 540d, "Copper", 25d), s -> {});
        research(rules, "copper_pickaxe", Map.of("mining", 2), "pickaxe", 1, Map.of("Knowledge", 675d, "Copper", 25d), s -> {});
        research(rules, "iron_mining", Map.of("mining", 2), "mining", 3, Map.of("Knowledge", 2500d), s -> s.resources.get("Iron").visible = true);
        research(rules, "iron_axes", Map.of("axe", 2, "mining", 3), "axe", 3, Map.of("Knowledge", 2700d, "Iron", 250d), s -> {});
        research(rules, "iron_pickaxe", Map.of("pickaxe", 1, "mining", 3), "pickaxe", 2, Map.of("Knowledge", 3200d, "Iron", 250d), s -> {});
        research(rules, "smelting", Map.of("mining", 3), "smelting", 1, Map.of("Knowledge", 4050d), s -> s.structures.put("smelter", 0));
        building(rules, "farm", "agriculture", "city_farm", 3, 50, 1.32, 20, 1.36, 10, 1.36, s -> {});
        building(rules, "lumber_yard", "axe", "city_lumber_yard", 5, 5, 1.85, 6, 1.9, 2, 1.95,
                s -> s.resources.get("Lumber").capacity += 100);
        building(rules, "rock_quarry", "mining", "city_rock_quarry", 2, 20, 1.45, 50, 1.36, 10, 1.36,
                s -> s.resources.get("Stone").capacity += 100);
        rules.put("city-shed", new Rule("city-shed", "trade", "city_shed_title1",
                s -> s.phase == GameSnapshot.Phase.PRIMITIVE && s.tech("storage") > 0,
                s -> Map.of("Money", scaled(75, 1.22, s.count("shed")), "Lumber", scaled(55, 1.32, s.count("shed")), "Stone", scaled(45, 1.32, s.count("shed"))),
                s -> {
                    s.addStructure("shed");
                    s.resources.get("Lumber").capacity += 300;
                    s.resources.get("Stone").capacity += 300;
                    if (s.resources.get("Copper").visible) s.resources.get("Copper").capacity += 90;
                    if (s.resources.get("Iron").visible) s.resources.get("Iron").capacity += 125;
                }, CivilizationRules::shedTooltip, "shed", null));
        rules.put("city-university", new Rule("city-university", "science", "city_university",
                s -> s.phase == GameSnapshot.Phase.PRIMITIVE && s.tech("science") > 0,
                s -> Map.of("Money", scaled(900, 1.5, s.count("university")) - 500,
                        "Lumber", scaled(500, 1.36, s.count("university")) - 200,
                        "Stone", scaled(750, 1.36, s.count("university")) - 350),
                s -> { s.addStructure("university"); s.resources.get("Knowledge").capacity += 500; },
                s -> List.of(TextRef.of("city_university_effect", 1), TextRef.of("city_max_knowledge", 500)), "university", null));
        rules.put("city-bank", new Rule("city-bank", "commercial", "city_bank",
                s -> s.phase == GameSnapshot.Phase.PRIMITIVE && s.tech("banking") > 0,
                s -> Map.of("Money", scaled(250, 1.35, s.count("bank")), "Lumber", scaled(75, 1.32, s.count("bank")),
                        "Stone", scaled(100, 1.35, s.count("bank"))),
                s -> { s.addStructure("bank"); s.resources.get("Money").capacity += 1800; },
                s -> s.tech("banking") >= 2 ? List.of(TextRef.of("ui_bank_capacity", 1800), TextRef.of("ui_banker_capacity", 1)) :
                        List.of(TextRef.of("ui_bank_capacity", 1800)), "bank", null));
        rules.put("city-mine", new Rule("city-mine", "industrial", "ui_copper_mine",
                s -> s.phase == GameSnapshot.Phase.PRIMITIVE && s.tech("mining") >= 2,
                s -> Map.of("Money", scaled(60, 1.6, s.count("mine")), "Lumber", scaled(175, 1.38, s.count("mine"))),
                s -> { s.addStructure("mine"); s.resources.get("Copper").visible = true; },
                s -> List.of(TextRef.of("ui_miner_capacity", 1)), "mine", null));
        rules.put("city-smelter", new Rule("city-smelter", "industrial", "city_smelter",
                s -> s.phase == GameSnapshot.Phase.PRIMITIVE && s.tech("smelting") > 0,
                s -> Map.of("Money", scaled(1000, 1.32, s.count("smelter")), "Iron", scaled(500, 1.33, s.count("smelter"))),
                s -> {
                    s.addStructure("smelter");
                    var current = s.civilization.smelter();
                    setSmelter(s, new SmelterState(current.wood() + 1, current.iron() + 1));
                }, s -> List.of(TextRef.of("city_smelter_effect1", 10), TextRef.of("ui_smelter_wood_cost", 3)), "smelter", null));
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

    private static double scaled(int base, double scale, int count) { return Math.round(base * Math.pow(scale, count)); }

    private static List<TextRef> shedTooltip(EvolutionState state) {
        var texts = new java.util.ArrayList<TextRef>();
        texts.add(TextRef.of("ui_shed_effect", 300));
        if (state.resources.get("Copper").visible) texts.add(TextRef.of("ui_shed_copper", 90));
        if (state.resources.get("Iron").visible) texts.add(TextRef.of("ui_shed_iron", 125));
        return List.copyOf(texts);
    }

    private static void building(Map<String, Rule> rules, String id, String tech, String title,
                                 int moneyAfter, int money, double moneyScale, int lumber, double lumberScale,
                                 int stone, double stoneScale, Consumer<EvolutionState> extra) {
        String actionId = "city-" + id;
        rules.put(actionId, new Rule(actionId, "production", title,
                s -> s.phase == GameSnapshot.Phase.PRIMITIVE && s.tech(tech) > 0,
                s -> {
                    int count = s.count(id);
                    var costs = new LinkedHashMap<String, Double>();
                    if (count >= moneyAfter) costs.put("Money", (double) Math.round(money * Math.pow(moneyScale, count)));
                    costs.put("Lumber", (double) Math.round(lumber * Math.pow(lumberScale, count)));
                    costs.put("Stone", (double) Math.round(stone * Math.pow(stoneScale, count)));
                    return costs;
                }, s -> { s.addStructure(id); extra.accept(s); },
                s -> List.of(TextRef.of(title + (id.equals("lumber_yard") ? "" : "_desc"))), id, null));
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
        research(rules, id, Map.of("primitive", primitive), grant, rank, costs, extra);
    }

    private static void research(Map<String, Rule> rules, String id, Map<String, Integer> requirements, String grant, int rank,
                                 Map<String, Double> costs, Consumer<EvolutionState> extra) {
        String actionId = "tech-" + id;
        rules.put(actionId, new Rule(actionId, "research", "tech_" + id,
                s -> s.phase == GameSnapshot.Phase.PRIMITIVE && requirements.entrySet().stream().allMatch(e -> s.tech(e.getKey()) >= e.getValue()) && s.tech(grant) < rank,
                s -> costs, s -> { extra.accept(s); s.grant(grant, rank); },
                s -> List.of(TextRef.of("tech_" + id + "_desc")), null, null));
    }
}
