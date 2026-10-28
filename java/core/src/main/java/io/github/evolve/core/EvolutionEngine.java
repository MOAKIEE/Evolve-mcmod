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
        if (command instanceof GameCommand.AssignJob assignment) return assignJob(assignment);
        if (command instanceof GameCommand.AdjustSmelter adjustment) return adjustSmelter(adjustment);
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

    private CommandResult assignJob(GameCommand.AssignJob command) {
        if (command.jobId() == null || command.delta() == 0 || command.delta() < -MAX_REPETITIONS || command.delta() > MAX_REPETITIONS)
            return result(Status.INVALID_COMMAND, 0);
        if (state.paused) return result(Status.PAUSED, 0);
        if (state.phase != Phase.PRIMITIVE || !CivilizationRules.jobVisible(state, command.jobId())) return result(Status.LOCKED, 0);
        var civ = state.civilization;
        var workers = new LinkedHashMap<>(civ.workers());
        int available = command.delta() > 0 ? civ.unemployed() : workers.get(command.jobId());
        int capacity = CivilizationRules.jobCapacity(state, command.jobId());
        if (command.delta() > 0 && capacity >= 0) available = Math.min(available, capacity - workers.get(command.jobId()));
        int applied = Math.min(Math.abs(command.delta()), available);
        if (applied > 0) {
            int change = command.delta() > 0 ? applied : -applied;
            workers.put(command.jobId(), workers.get(command.jobId()) + change);
            var assigned = new LinkedHashMap<>(civ.assigned());
            assigned.put(command.jobId(), workers.get(command.jobId()));
            state.civilization = new CivilizationState(civ.primaryGenus(), civ.unemployed() - change, workers, assigned, civ.capacitiesInitialized(), civ.smelter());
            state.revision += applied;
        }
        return result(applied == Math.abs(command.delta()) ? Status.OK : Status.NO_AVAILABLE_WORKERS, applied);
    }

    private CommandResult executeCivilization(GameCommand.Action action) {
        var rule = CivilizationRules.ALL.get(action.actionId());
        if (rule == null) return result(state.phase == Phase.CIVILIZATION_READY ? Status.PHASE_COMPLETE : Status.UNKNOWN_ACTION, 0);
        int applied = 0;
        Status status = Status.OK;
        for (int i = 0; i < action.repetitions(); i++) {
            if (!rule.visible().test(state)) { status = Status.LOCKED; break; }
            if (rule.structure() != null && state.count(rule.structure()) >= 1_000_000) { status = Status.LOCKED; break; }
            if (rule.gathers() != null && resourceFull(rule.gathers())) {
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

    private CommandResult adjustSmelter(GameCommand.AdjustSmelter command) {
        if (command.allocationId() == null || command.delta() == 0 || command.delta() < -MAX_REPETITIONS || command.delta() > MAX_REPETITIONS)
            return result(Status.INVALID_COMMAND, 0);
        if (state.paused) return result(Status.PAUSED, 0);
        if (state.phase != Phase.PRIMITIVE || state.count("smelter") == 0 ||
                !List.of("Wood", "Iron").contains(command.allocationId())) return result(Status.LOCKED, 0);
        var current = state.civilization.smelter();
        boolean fuel = command.allocationId().equals("Wood");
        int available = command.delta() > 0 ? (fuel ? state.count("smelter") - current.wood() : current.wood() - current.iron()) :
                (fuel ? current.wood() : current.iron());
        int applied = Math.min(Math.abs(command.delta()), available);
        int change = command.delta() > 0 ? applied : -applied;
        if (applied > 0) {
            int wood = fuel ? current.wood() + change : current.wood();
            int iron = fuel ? (change > 0 ? current.iron() + change : Math.min(current.iron(), wood)) : current.iron() + change;
            CivilizationRules.setSmelter(state, new SmelterState(wood, iron));
            state.revision += applied;
        }
        return result(applied == Math.abs(command.delta()) ? Status.OK : Status.NO_AVAILABLE_CAPACITY, applied);
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
                } else {
                    CivilizationRules.fastLoop(state);
                    if (state.loopCycle % 4 == 0) CivilizationRules.updateCapacities(state);
                }
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
                boolean full = rule.gathers() != null && resourceFull(rule.gathers());
                int count = rule.structure() == null ? 0 : state.count(rule.structure());
                actions.add(new GameSnapshot.ActionView(rule.id(), rule.category(), TextRef.of(rule.title()),
                        rule.tooltip().apply(state), count, costs,
                        !state.paused && !full && count < 1_000_000 && affordable(costs), fitsStorage(costs)));
            }
        }
        var jobs = new ArrayList<GameSnapshot.JobView>();
        if (state.phase == Phase.PRIMITIVE) {
            jobs.add(new GameSnapshot.JobView("unemployed", TextRef.of("job_unemployed"), state.civilization.unemployed(), -1, true));
            for (String job : CivilizationRules.JOBS) if (CivilizationRules.jobVisible(state, job))
                jobs.add(new GameSnapshot.JobView(job, TextRef.of("job_" + job), state.civilization.workers().get(job), CivilizationRules.jobCapacity(state, job), false));
        }
        return new GameSnapshot(state.revision, state.elapsedTicks, state.phase, state.paused,
                state.species, state.genus, state.progress, resources, actions, state.technologies, state.traits, jobs,
                new GameSnapshot.SmelterView(state.count("smelter"), state.civilization.smelter().wood(),
                        state.civilization.smelter().iron(), !state.paused && state.count("smelter") > 0));
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
                (state.resources.get(e.getKey()).capacity < 0 || state.resources.get(e.getKey()).capacity >= e.getValue()));
    }

    private boolean resourceFull(String id) {
        var resource = state.resources.get(id);
        return resource.amount >= (resource.capacity < 0 ? 1e15 : resource.capacity);
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
        Objects.requireNonNull(save.civilization().smelter(), "smelter");
        boolean primitive = save.phase() == Phase.PRIMITIVE;
        if (!save.resources().keySet().equals(primitive ? CivilizationRules.RESOURCES : java.util.Set.of("RNA", "DNA")))
            throw new IllegalArgumentException("Invalid phase resources");
        for (var entry : save.resources().entrySet()) {
            var resource = entry.getValue();
            boolean crafted = primitive && CraftingRules.RESOURCES.contains(entry.getKey());
            if (!Double.isFinite(resource.amount()) || !Double.isFinite(resource.capacity()) || !Double.isFinite(resource.changePerSecond()) ||
                    resource.amount() < 0 || resource.amount() > 1e15 || (crafted ? resource.capacity() != -1 :
                    resource.capacity() < (entry.getKey().equals("human") ? 0 : 100) || resource.capacity() > 1e15 || resource.amount() > resource.capacity()))
                throw new IllegalArgumentException("Invalid resource amount or capacity");
        }
        save.structures().forEach((id, count) -> {
            if (!(primitive ? CivilizationRules.STRUCTURES : EvolutionRules.STRUCTURES).contains(id) || count < 0 || count > 1_000_000)
                throw new IllegalArgumentException("Invalid phase structure");
        });
        save.technologies().forEach((id, rank) -> {
            boolean valid = primitive ? rank >= 1 && rank <= CivilizationRules.TECH_MAX.getOrDefault(id, 0) :
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
                    !SpeciesCatalog.startingTraits(save.species()).equals(save.traits()) || save.civilization().unemployed() != 0 ||
                    !save.civilization().workers().isEmpty() || !save.civilization().assigned().isEmpty() || save.civilization().capacitiesInitialized() ||
                    !save.civilization().smelter().equals(SmelterState.empty()))
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
        if (housing && primitive != 3 || save.structures().containsKey("basic_housing") != housing)
            throw new IllegalArgumentException("Inconsistent primitive structures");
        for (var dependency : Map.of("farm", "agriculture", "lumber_yard", "axe", "rock_quarry", "mining", "shed", "storage", "university", "science", "bank", "banking").entrySet()) {
            if (save.structures().containsKey(dependency.getKey()) != save.technologies().containsKey(dependency.getValue()) ||
                    save.technologies().containsKey(dependency.getValue()) && primitive != 3)
                throw new IllegalArgumentException("Inconsistent production structure");
        }
        boolean currency = save.technologies().containsKey("currency");
        if (currency && !housing || houses > 5 && !currency) throw new IllegalArgumentException("Missing currency prerequisite");
        if (save.technologies().containsKey("storage") && !currency || save.technologies().containsKey("science") && !housing ||
                save.technologies().containsKey("banking") && !currency)
            throw new IllegalArgumentException("Missing storage, science or banking prerequisite");
        int sheds = save.structures().getOrDefault("shed", 0);
        int universities = save.structures().getOrDefault("university", 0);
        int banks = save.structures().getOrDefault("bank", 0);
        int mines = save.structures().getOrDefault("mine", 0);
        int cementPlants = save.structures().getOrDefault("cement_plant", 0);
        int mining = save.technologies().getOrDefault("mining", 0);
        int foundries = save.structures().getOrDefault("foundry", 0);
        if (save.structures().containsKey("foundry") != save.technologies().containsKey("foundry") ||
                save.technologies().containsKey("foundry") && mining < 2 || foundries > 0 && mines == 0)
            throw new IllegalArgumentException("Missing foundry prerequisite");
        for (var recipe : CraftingRules.RECIPES) {
            boolean visible = foundries > 0 && (!recipe.resource().equals("Wrought_Iron") || mining >= 3);
            var resource = save.resources().get(recipe.resource());
            checkResource(save, recipe.resource(), -1, visible);
            if (!visible && resource.changePerSecond() != 0)
                throw new IllegalArgumentException("Active hidden crafted resource");
        }
        if (save.structures().containsKey("mine") != (mining >= 2) || mines > 0 && !currency ||
                save.technologies().getOrDefault("axe", 0) >= 2 && (mining < 2 || mines == 0) ||
                save.technologies().containsKey("pickaxe") && (mining < 2 || mines == 0))
            throw new IllegalArgumentException("Missing copper mining prerequisite");
        if (save.technologies().getOrDefault("axe", 0) >= 3 && mining < 3 ||
                save.technologies().getOrDefault("pickaxe", 0) >= 2 && mining < 3)
            throw new IllegalArgumentException("Missing iron mining prerequisite");
        boolean smelting = save.technologies().containsKey("smelting");
        var smelter = save.civilization().smelter();
        if (save.structures().containsKey("smelter") != smelting || smelting && mining < 3 ||
                smelter.wood() < 0 || smelter.wood() > save.structures().getOrDefault("smelter", 0) ||
                smelter.iron() < 0 || smelter.iron() > smelter.wood())
            throw new IllegalArgumentException("Invalid smelter allocation or prerequisite");
        boolean cement = save.technologies().containsKey("cement");
        boolean saw = save.technologies().containsKey("saw");
        if (save.structures().containsKey("cement_plant") != cement || cement &&
                (mining < 1 || !save.technologies().containsKey("storage") || !save.technologies().containsKey("science")) ||
                save.structures().containsKey("sawmill") != saw || saw &&
                (mining < 3 || !save.technologies().containsKey("axe")) || save.structures().getOrDefault("sawmill", 0) > 0 && cementPlants == 0)
            throw new IllegalArgumentException("Missing cement or sawmill prerequisite");
        int storage = save.technologies().getOrDefault("storage", 0);
        boolean farmHousing = save.technologies().containsKey("farm");
        if (storage >= 2 && (!cement || mining < 3 || cementPlants == 0) || farmHousing &&
                (!housing || !currency || !save.technologies().containsKey("agriculture")))
            throw new IllegalArgumentException("Missing reinforced storage or farm housing prerequisite");
        if (universities > 0 && !currency) throw new IllegalArgumentException("Missing university currency prerequisite");
        checkResource(save, "Food", save.civilization().capacitiesInitialized() ? 1000 : 250, primitive >= 1);
        double lumberSheds = save.resources().get("Lumber").capacity() - 200 - 100d * save.structures().getOrDefault("lumber_yard", 0) -
                200d * save.structures().getOrDefault("sawmill", 0);
        double stoneSheds = save.resources().get("Stone").capacity() - 200 - 100d * save.structures().getOrDefault("rock_quarry", 0);
        if (lumberSheds != stoneSheds || !validShedGain(lumberSheds, 300, storage, sheds, true))
            throw new IllegalArgumentException("Inconsistent lumber or stone shed capacity");
        checkResource(save, "Lumber", save.resources().get("Lumber").capacity(), true);
        checkResource(save, "Stone", save.resources().get("Stone").capacity(), primitive >= 2);
        checkResource(save, "Knowledge", 100 + 500d * universities, primitive >= 3);
        checkResource(save, "Money", 1000 + 1800d * banks, currency);
        checkStoredMaterial(save, "Copper", 90, mines > 0, sheds);
        checkStoredMaterial(save, "Iron", 125, mining >= 3, sheds);
        checkStoredMaterial(save, "Cement", 100, cementPlants > 0, sheds);
        var population = save.resources().get("human");
        double farmCapacity = population.capacity() - houses;
        if (farmCapacity != Math.rint(farmCapacity) || farmCapacity < 0 ||
                farmCapacity > (farmHousing ? save.structures().getOrDefault("farm", 0) : 0))
            throw new IllegalArgumentException("Inconsistent farm population capacity");
        checkResource(save, "human", population.capacity(), population.capacity() > 0);
        if (!save.civilization().workers().keySet().equals(java.util.Set.copyOf(CivilizationRules.JOBS)) ||
                !save.civilization().assigned().keySet().equals(java.util.Set.copyOf(CivilizationRules.JOBS)))
            throw new IllegalArgumentException("Invalid job set");
        long totalWorkers = save.civilization().unemployed();
        if (totalWorkers < 0) throw new IllegalArgumentException("Invalid unemployed count");
        for (var job : save.civilization().workers().entrySet()) {
            boolean visible = switch (job.getKey()) {
                case "farmer" -> save.structures().getOrDefault("farm", 0) > 0;
                case "lumberjack" -> save.technologies().containsKey("axe");
                case "quarry_worker" -> save.structures().getOrDefault("rock_quarry", 0) > 0;
                case "professor" -> universities > 0;
                case "banker" -> save.technologies().getOrDefault("banking", 0) >= 2;
                case "miner" -> mines > 0;
                case "cement_worker" -> cementPlants > 0;
                case "craft_Plywood", "craft_Brick" -> foundries > 0;
                case "craft_Wrought_Iron" -> foundries > 0 && mining >= 3;
                default -> false;
            };
            if (job.getValue() < 0 || job.getValue() > 1_000_000 || !visible && job.getValue() != 0)
                throw new IllegalArgumentException("Invalid worker assignment");
            int target = save.civilization().assigned().get(job.getKey());
            int capacity = switch (job.getKey()) {
                case "professor" -> universities;
                case "banker" -> banks;
                case "miner" -> mines;
                case "cement_worker" -> cementPlants * 2;
                case "craft_Plywood", "craft_Brick", "craft_Wrought_Iron" -> foundries;
                default -> 1_000_000;
            };
            if (target < job.getValue() || target > capacity || target < 0 || !visible && target != 0 || job.getValue() > capacity)
                throw new IllegalArgumentException("Invalid job target or capacity");
            if (CraftingRules.recipeForJob(job.getKey()) != null && target != job.getValue())
                throw new IllegalArgumentException("Craftsmen do not keep vacant assignment targets");
            totalWorkers += job.getValue();
        }
        if (CraftingRules.RECIPES.stream().mapToLong(r -> save.civilization().workers().get(r.job())).sum() > foundries)
            throw new IllegalArgumentException("Craftsmen exceed shared foundry capacity");
        if (population.amount() != Math.rint(population.amount()) || totalWorkers != population.amount())
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

    private static void checkStoredMaterial(GameSave save, String id, int perShed, boolean visible, int sheds) {
        var resource = save.resources().get(id);
        // Unlocks and storage research apply to old sheds at the next midLoop; new sheds apply immediately.
        if (resource.visible() != visible || !validShedGain(resource.capacity() - 100, perShed,
                save.technologies().getOrDefault("storage", 0), sheds, false) ||
                !visible && (resource.amount() != 0 || resource.capacity() != 100 || resource.changePerSecond() != 0))
            throw new IllegalArgumentException("Inconsistent material capacity or visibility: " + id);
    }

    private static boolean validShedGain(double gain, int original, int storage, int sheds, boolean allApplied) {
        if (gain < 0 || gain != Math.rint(gain)) return false;
        int upgraded = CivilizationRules.shedCapacity(original, storage);
        if (storage < 2) {
            double applied = gain / original;
            return applied == Math.rint(applied) && applied <= sheds && (!allApplied || applied == sheds);
        }
        if (allApplied) {
            double updated = (gain - (double) original * sheds) / (upgraded - original);
            return updated == Math.rint(updated) && updated >= 0 && updated <= sheds;
        }
        // Find an integer combination of old and upgraded sheds without scanning the building count.
        int highest = (int) Math.min(sheds, gain / upgraded);
        for (int updated = highest; updated >= Math.max(0, highest - original + 1); updated--) {
            double old = (gain - (double) updated * upgraded) / original;
            if (old == Math.rint(old)) return old + updated <= sheds;
        }
        return false;
    }

    private static void validateContext(EvolutionContext context) {
        if (!SpeciesCatalog.all().keySet().containsAll(context.extinctSpecies()))
            throw new IllegalArgumentException("Unknown extinct species");
    }
}
