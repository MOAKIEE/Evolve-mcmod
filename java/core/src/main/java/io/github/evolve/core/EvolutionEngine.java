package io.github.evolve.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.github.evolve.core.CommandResult.Status;
import static io.github.evolve.core.GameSnapshot.Phase;

/**
 * Single-owner, deterministic game session. One Minecraft tick is 50 ms;
 * the upstream fast/mid/long cadence is 5/20/100 ticks. No background tasks,
 * wall clock, file access, client API, static mutable game or script engine.
 */
public final class EvolutionEngine implements GameEngine {
    public static final int MAX_TICKS_PER_CALL = 1200;
    public static final int MAX_REPETITIONS = 100;
    private final Thread owner = Thread.currentThread();
    private final EvolutionState state;

    private EvolutionEngine(EvolutionState state) { this.state = state; }

    public static EvolutionEngine newGame(int seed) { return newGame(seed, EvolutionContext.fresh()); }

    public static EvolutionEngine newGame(int seed, EvolutionContext context) {
        Objects.requireNonNull(context, "context");
        validateContext(context);
        var state = new EvolutionState(context);
        state.randomSeed = Math.floorMod(seed, 233280);
        state.resources.put("RNA", new EvolutionState.Resource(0, baseCapacity(context), true));
        state.resources.put("DNA", new EvolutionState.Resource(0, baseCapacity(context), false));
        if (context.master() > 0) {
            for (String id : EvolutionRules.STRUCTURES) state.structures.put(id, context.master());
            state.structures.put("membrane", context.master() * 2);
            state.structures.put("organelles", context.master() * 2);
            state.structures.put("nucleus", context.master() * 2);
            state.resources.get("DNA").visible = true;
            state.grant("evo", 2);
        }
        if (context.grandmaster() >= 5) {
            state.grant("evo", 6);
            state.progress = 80;
            for (String type : List.of("humanoid", "giant", "small", "demonic", "angelic", "insectoid",
                    "eldritch", "sand", "polar", "heat", "fey", "aquatic")) state.grant("evo_" + type, 1);
            state.grant("evo_animalism", 2);
            state.grant("evo_eggshell", 2);
        }
        return new EvolutionEngine(state);
    }

    public static EvolutionEngine restore(GameSave save) {
        validateSave(save);
        var state = new EvolutionState(save.context());
        state.elapsedTicks = save.elapsedTicks();
        state.revision = save.revision();
        state.tickRemainder = save.tickRemainder();
        state.loopCycle = save.loopCycle();
        state.paused = save.paused();
        state.phase = save.phase();
        state.randomSeed = save.randomSeed();
        state.species = save.species();
        state.genus = save.genus();
        state.progress = save.progress();
        save.resources().forEach((id, resource) -> {
            var restored = new EvolutionState.Resource(resource.amount(), resource.capacity(), resource.visible());
            restored.rate = resource.changePerSecond();
            state.resources.put(id, restored);
        });
        state.structures.putAll(save.structures());
        state.technologies.putAll(save.technologies());
        state.traits.putAll(save.traits());
        state.civilization = save.civilization();
        return new EvolutionEngine(state);
    }

    @Override
    public CommandResult execute(GameCommand command) {
        checkOwner();
        if (command instanceof GameCommand.Pause pause) {
            if (state.paused != pause.paused()) { state.paused = pause.paused(); state.revision++; }
            return result(Status.OK, 1);
        }
        if (!(command instanceof GameCommand.Action action) || action.actionId() == null ||
                action.repetitions() < 1 || action.repetitions() > MAX_REPETITIONS)
            return result(Status.INVALID_COMMAND, 0);
        if (state.paused) return result(Status.PAUSED, 0);
        if (state.phase != Phase.EVOLUTION) return executeCivilization(action);
        var rule = EvolutionRules.ALL.get(action.actionId());
        if (rule == null) return result(Status.UNKNOWN_ACTION, 0);

        int applied = 0;
        Status status = Status.OK;
        for (int i = 0; i < action.repetitions(); i++) {
            if (state.phase != Phase.EVOLUTION || !rule.visible().test(state)) { status = Status.LOCKED; break; }
            if (state.count(action.actionId().substring("evolution-".length())) >= 1_000_000) {
                status = Status.LOCKED;
                break;
            }
            if (action.actionId().equals("evolution-rna") && state.amount("RNA") >= state.resources.get("RNA").capacity) {
                status = Status.RESOURCE_FULL;
                break;
            }
            Map<String, Double> costs = rule.cost().apply(state);
            if (!fitsStorage(costs)) { status = Status.STORAGE_TOO_SMALL; break; }
            if (!affordable(costs)) { status = Status.UNAFFORDABLE; break; }
            // The entire cost vector is checked before any deduction.
            costs.forEach((id, cost) -> state.resources.get(id).amount -= cost);
            rule.effect().accept(state);
            state.revision++;
            applied++;
        }
        return result(applied == action.repetitions() ? Status.OK : status, applied);
    }

    private CommandResult executeCivilization(GameCommand.Action action) {
        var rule = CivilizationRules.ALL.get(action.actionId());
        if (rule == null) return result(state.phase == Phase.CIVILIZATION_READY ? Status.PHASE_COMPLETE : Status.UNKNOWN_ACTION, 0);
        int applied = 0;
        Status status = Status.OK;
        for (int i = 0; i < action.repetitions(); i++) {
            if (!rule.visible().test(state)) { status = Status.LOCKED; break; }
            if (rule.structure() != null && state.count(rule.structure()) >= 1_000_000) { status = Status.LOCKED; break; }
            if (rule.gathers() != null && state.amount(rule.gathers()) >= state.resources.get(rule.gathers()).capacity) {
                status = Status.RESOURCE_FULL;
                break;
            }
            var costs = rule.costs().apply(state);
            if (!fitsStorage(costs)) { status = Status.STORAGE_TOO_SMALL; break; }
            if (!affordable(costs)) { status = Status.UNAFFORDABLE; break; }
            costs.forEach((id, cost) -> state.resources.get(id).amount -= cost);
            rule.effect().accept(state);
            state.revision++;
            applied++;
        }
        return result(applied == action.repetitions() ? Status.OK : status, applied);
    }

    @Override
    public void advanceTicks(int ticks) {
        checkOwner();
        if (ticks < 0 || ticks > MAX_TICKS_PER_CALL)
            throw new IllegalArgumentException("ticks must be in [0, " + MAX_TICKS_PER_CALL + "]");
        if (state.paused || state.phase == Phase.CIVILIZATION_READY || ticks == 0) return;
        if (state.elapsedTicks > Long.MAX_VALUE - ticks || state.revision > Long.MAX_VALUE - ticks)
            throw new IllegalStateException("Session time exhausted");
        for (int i = 0; i < ticks; i++) {
            state.elapsedTicks++;
            state.revision++;
            if (++state.tickRemainder == 5) {
                state.tickRemainder = 0;
                state.loopCycle = (state.loopCycle + 1) % 20;
                if (state.phase == Phase.EVOLUTION) {
                    fastLoop();
                    if (state.loopCycle % 4 == 0) updateCapacities();
                } else CivilizationRules.fastLoop(state);
            }
        }
    }

    private void fastLoop() {
        double previousRna = state.amount("RNA");
        double previousDna = state.amount("DNA");
        double multiplier = state.context.productionMultiplier();
        var rna = state.resources.get("RNA");
        var dna = state.resources.get("DNA");
        if (state.has("nucleus") && dna.amount < dna.capacity) {
            int count = Math.min(state.count("nucleus"), (int) Math.floor(rna.amount / 2));
            dna.amount += count * (state.tech("evo") >= 5 ? 2 : 1) * multiplier * 0.25;
            rna.amount -= count * 0.5;
        }
        int rnaMultiplier = (state.context.rapidMutation() ? 2 : 1) + (state.tech("evo") >= 2 ? 1 : 0);
        rna.amount += state.count("organelles") * rnaMultiplier * multiplier * 0.25;
        rna.amount += state.context.novice() / 2d * multiplier * 0.25;
        if (dna.visible) dna.amount += state.context.novice() / 4d * multiplier * 0.25;

        // Keep the original else-if order: at most one new structure appears per fast loop.
        if (rna.amount >= 2 && !dna.visible) {
            dna.visible = true;
            if (state.context.massExtinction() > 1) {
                rna.amount += rna.capacity;
                dna.amount += rna.capacity;
            }
        } else if (rna.amount >= 10 && !state.has("membrane")) {
            state.structures.put("membrane", 0);
        } else if (dna.amount >= 4 && !state.has("organelles")) {
            state.structures.put("organelles", 0);
        } else if (state.count("organelles") >= 2 && !state.has("nucleus")) {
            state.structures.put("nucleus", 0);
        } else if (state.count("nucleus") >= 1 && !state.has("eukaryotic_cell")) {
            state.structures.put("eukaryotic_cell", 0);
        } else if (state.count("eukaryotic_cell") >= 1 && !state.has("mitochondria")) {
            state.structures.put("mitochondria", 0);
        } else if (state.has("mitochondria") && state.tech("evo") == 0) {
            state.grant("evo", 1);
        }
        rna.rate = (rna.amount - previousRna) * 4;
        dna.rate = (dna.amount - previousDna) * 4;
        rna.addCapped(0);
        dna.addCapped(0);
    }

    private void updateCapacities() {
        int mitochondria = state.count("mitochondria");
        double base = baseCapacity(state.context);
        state.resources.get("RNA").capacity = base + state.count("membrane") * (mitochondria + 1d) * 5;
        state.resources.get("DNA").capacity = base + state.count("eukaryotic_cell") * (mitochondria + 1d) * 10;
    }

    @Override
    public GameSnapshot snapshot() {
        checkOwner();
        var resources = state.resources.entrySet().stream().map(entry -> {
            var resource = entry.getValue();
            return new GameSnapshot.ResourceView(entry.getKey(), TextRef.of(entry.getKey().equals("human") ? "race_human" : "resource_" + entry.getKey() + "_name"),
                    resource.amount, resource.capacity, resource.rate, resource.visible);
        }).toList();
        var actions = new ArrayList<GameSnapshot.ActionView>();
        if (state.phase == Phase.EVOLUTION) {
            for (var rule : EvolutionRules.ALL.values()) {
                if (!rule.visible().test(state)) continue;
                Map<String, Double> costs = rule.cost().apply(state);
                boolean full = rule.id().equals("evolution-rna") && state.amount("RNA") >= state.resources.get("RNA").capacity;
                String shortId = rule.id().substring("evolution-".length());
                boolean countLimit = state.count(shortId) >= 1_000_000;
                actions.add(new GameSnapshot.ActionView(rule.id(), "evolution", TextRef.of(rule.titleKey()),
                        rule.tooltip().apply(state), state.count(shortId), costs,
                        !state.paused && !full && !countLimit && affordable(costs), fitsStorage(costs)));
            }
        } else {
            for (var rule : CivilizationRules.ALL.values()) {
                if (!rule.visible().test(state)) continue;
                var costs = rule.costs().apply(state);
                boolean full = rule.gathers() != null && state.amount(rule.gathers()) >= state.resources.get(rule.gathers()).capacity;
                int count = rule.structure() == null ? 0 : state.count(rule.structure());
                actions.add(new GameSnapshot.ActionView(rule.id(), rule.category(), TextRef.of(rule.title()),
                        rule.tooltip().apply(state), count, costs,
                        !state.paused && !full && count < 1_000_000 && affordable(costs), fitsStorage(costs)));
            }
        }
        List<GameSnapshot.JobView> jobs = state.phase == Phase.PRIMITIVE ?
                List.of(new GameSnapshot.JobView("unemployed", TextRef.of("job_unemployed"), state.civilization.unemployed(), -1, true)) : List.of();
        return new GameSnapshot(state.revision, state.elapsedTicks, state.phase, state.paused,
                state.species, state.genus, state.progress, resources, actions, state.technologies, state.traits, jobs);
    }

    @Override
    public GameSave exportSave() {
        checkOwner();
        var resources = new LinkedHashMap<String, GameSave.ResourceState>();
        state.resources.forEach((id, resource) -> resources.put(id,
                new GameSave.ResourceState(resource.amount, resource.capacity, resource.visible, resource.rate)));
        return new GameSave(GameSave.SCHEMA_VERSION, GameSave.RULESET, state.elapsedTicks, state.revision,
                state.tickRemainder, state.loopCycle, state.paused, state.phase, state.randomSeed,
                state.species, state.genus, state.progress, state.context,
                resources, state.structures, state.technologies, state.traits, state.civilization);
    }

    private boolean affordable(Map<String, Double> costs) {
        return costs.entrySet().stream().allMatch(e -> state.amount(e.getKey()) >= e.getValue());
    }

    private boolean fitsStorage(Map<String, Double> costs) {
        return costs.entrySet().stream().allMatch(e -> state.resources.get(e.getKey()).visible &&
                state.resources.get(e.getKey()).capacity >= e.getValue());
    }

    private CommandResult result(Status status, int applied) { return new CommandResult(status, applied, state.revision); }

    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("GameEngine must be used on its owning thread");
    }

    private static double baseCapacity(EvolutionContext context) {
        return 100 + (context.massExtinction() > 1 ? (context.massExtinction() - 1) * 50 : 0);
    }

    /** Reject incompatible or corrupt state before replacing any live session. */
    public static void validateSave(GameSave save) {
        Objects.requireNonNull(save, "save");
        if (save.schemaVersion() != GameSave.SCHEMA_VERSION || !GameSave.RULESET.equals(save.ruleset()))
            throw new IllegalArgumentException("Unsupported save schema or ruleset");
        if (save.elapsedTicks() < 0 || save.elapsedTicks() > Long.MAX_VALUE - MAX_TICKS_PER_CALL ||
                save.revision() < save.elapsedTicks() || save.revision() > Long.MAX_VALUE - MAX_TICKS_PER_CALL ||
                save.tickRemainder() < 0 || save.tickRemainder() > 4 || save.loopCycle() < 0 || save.loopCycle() >= 20 ||
                save.randomSeed() < 0 || save.randomSeed() >= 233280 || save.progress() < 0 || save.progress() > 100)
            throw new IllegalArgumentException("Invalid session counters");
        if (save.elapsedTicks() % 5 != save.tickRemainder() || (save.elapsedTicks() / 5) % 20 != save.loopCycle())
            throw new IllegalArgumentException("Inconsistent loop phase");
        validateContext(Objects.requireNonNull(save.context(), "context"));
        Objects.requireNonNull(save.civilization(), "civilization");
        boolean primitive = save.phase() == Phase.PRIMITIVE;
        if (!save.resources().keySet().equals(primitive ? CivilizationRules.RESOURCES : java.util.Set.of("RNA", "DNA")))
            throw new IllegalArgumentException("Invalid phase resources");
        for (var entry : save.resources().entrySet()) {
            var resource = entry.getValue();
            if (!Double.isFinite(resource.amount()) || !Double.isFinite(resource.capacity()) || !Double.isFinite(resource.changePerSecond()) ||
                    resource.amount() < 0 || resource.capacity() < (entry.getKey().equals("human") ? 0 : 100) ||
                    resource.capacity() > 1e15 || resource.amount() > resource.capacity())
                throw new IllegalArgumentException("Invalid resource amount or capacity");
        }
        save.structures().forEach((id, count) -> {
            if (!(primitive ? CivilizationRules.STRUCTURES : EvolutionRules.STRUCTURES).contains(id) || count < 0 || count > 1_000_000)
                throw new IllegalArgumentException("Invalid phase structure");
        });
        save.technologies().forEach((id, rank) -> {
            boolean valid = primitive ? (id.equals("primitive") && rank >= 1 && rank <= 3 || id.equals("housing") && rank == 1) :
                    EvolutionRules.TECHS.contains(id) && rank >= 1 && rank <= 8;
            if (!valid) throw new IllegalArgumentException("Invalid phase technology");
        });
        if (save.phase() == Phase.EVOLUTION) {
            if (!save.species().equals("protoplasm") || !save.genus().equals("organism") || !save.traits().isEmpty() ||
                    !save.resources().get("RNA").visible() || !save.civilization().equals(CivilizationState.empty()))
                throw new IllegalArgumentException("Invalid evolution phase");
        } else if (save.phase() == Phase.CIVILIZATION_READY) {
            var species = SpeciesCatalog.all().get(save.species());
            if (species == null || !species.genus().equals(save.genus()) || save.progress() != 100 ||
                    !save.structures().isEmpty() || !save.technologies().isEmpty() ||
                    save.resources().values().stream().anyMatch(GameSave.ResourceState::visible) ||
                    !SpeciesCatalog.startingTraits(save.species()).equals(save.traits()) || save.civilization().unemployed() != 0)
                throw new IllegalArgumentException("Invalid civilization handoff");
            validatePrimaryGenus(save);
        } else if (primitive) {
            validatePrimaryGenus(save);
            validateCivilization(save);
        } else throw new IllegalArgumentException("Missing game phase");
    }

    private static void validatePrimaryGenus(GameSave save) {
        String primary = save.civilization().primaryGenus();
        if (primary == null || !primary.isEmpty() && !primary.equals(save.genus()))
            throw new IllegalArgumentException("Invalid primary genus");
    }

    private static void validateCivilization(GameSave save) {
        if (!CivilizationRules.supported(save.species(), save.context()) || !save.genus().equals("humanoid") ||
                save.progress() != 100 || !save.traits().equals(CivilizationRules.traits(save.civilization())))
            throw new IllegalArgumentException("Unsupported primitive civilization");
        int primitive = save.technologies().getOrDefault("primitive", 0);
        boolean housing = save.technologies().containsKey("housing");
        int houses = save.structures().getOrDefault("basic_housing", 0);
        if (housing && primitive != 3 || save.structures().containsKey("basic_housing") != housing || houses > 5)
            throw new IllegalArgumentException("Inconsistent primitive structures");
        checkResource(save, "Food", 250, primitive >= 1);
        checkResource(save, "Lumber", 200, true);
        checkResource(save, "Stone", 200, primitive >= 2);
        checkResource(save, "Knowledge", 100, primitive >= 3);
        checkResource(save, "Money", 1000, false);
        checkResource(save, "human", houses, houses > 0);
        var population = save.resources().get("human");
        if (population.amount() != Math.rint(population.amount()) || save.civilization().unemployed() != population.amount())
            throw new IllegalArgumentException("Inconsistent primitive population and jobs");
        for (String id : List.of("RNA", "DNA")) {
            var resource = save.resources().get(id);
            if (resource.visible() || resource.changePerSecond() != 0) throw new IllegalArgumentException("Active evolution resource");
        }
    }

    private static void checkResource(GameSave save, String id, double capacity, boolean visible) {
        var resource = save.resources().get(id);
        if (resource.capacity() != capacity || resource.visible() != visible || !visible && resource.amount() != 0)
            throw new IllegalArgumentException("Inconsistent primitive resource: " + id);
    }

    private static void validateContext(EvolutionContext context) {
        if (!SpeciesCatalog.all().keySet().containsAll(context.extinctSpecies()))
            throw new IllegalArgumentException("Unknown extinct species");
    }
}
