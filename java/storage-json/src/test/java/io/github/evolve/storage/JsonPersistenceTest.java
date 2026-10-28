package io.github.evolve.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.evolve.core.EvolutionEngine;
import io.github.evolve.core.GameCommand;
import io.github.evolve.core.GameSave;
import io.github.evolve.core.GameSnapshot;
import io.github.evolve.core.CivilizationState;
import io.github.evolve.core.SpeciesCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonPersistenceTest {
    @TempDir Path temporary;
    private final JsonSaveCodec codec = new JsonSaveCodec();

    @Test
    void roundTripPreservesFutureSimulationIncludingRemainder() {
        var original = EvolutionEngine.newGame(9287);
        original.execute(new GameCommand.Action("evolution-rna", 20));
        original.advanceTicks(13);
        var restored = EvolutionEngine.restore(codec.decode(codec.encode(original.exportSave())));
        assertEquals(original.snapshot(), restored.snapshot());
        for (int i = 0; i < 13; i++) {
            original.execute(new GameCommand.Action("evolution-dna"));
            restored.execute(new GameCommand.Action("evolution-dna"));
            original.advanceTicks(9);
            restored.advanceTicks(9);
        }
        assertEquals(original.exportSave(), restored.exportSave());
    }

    @Test
    void rejectsUnknownMissingWrongTypeAndNonIntegralFields() {
        invalid(root -> root.addProperty("schemaVersion", 1));
        invalid(root -> root.addProperty("ruleset", "unknown"));
        invalid(root -> root.remove("resources"));
        invalid(root -> root.addProperty("extra", true));
        invalid(root -> root.addProperty("paused", "false"));
        invalid(root -> root.addProperty("randomSeed", 0.75));
        invalid(root -> root.addProperty("randomSeed", 4_294_967_296L));
        invalid(root -> root.addProperty("elapsedTicks", -1));
        invalid(root -> root.addProperty("tickRemainder", 2));
        invalid(root -> root.getAsJsonObject("resources").getAsJsonObject("RNA").addProperty("amount", -1));
        invalid(root -> root.getAsJsonObject("resources").getAsJsonObject("RNA").addProperty("amount", 101));
        invalid(root -> root.getAsJsonObject("resources").getAsJsonObject("RNA").addProperty("capacity", "100"));
        invalid(root -> root.getAsJsonObject("context").addProperty("productionMultiplier", 0));
    }

    @Test
    void rejectsDuplicateFieldsGarbageDeepTreesAndTruncatedInput() {
        String valid = codec.encode(EvolutionEngine.newGame(2).exportSave());
        assertThrows(SaveFormatException.class, () -> codec.decode(valid.replaceFirst("\\{", "{\"schemaVersion\":2,")));
        assertThrows(SaveFormatException.class, () -> codec.decode(valid + "false"));
        assertThrows(SaveFormatException.class, () -> codec.decode(valid.substring(0, valid.length() / 2)));
        assertThrows(SaveFormatException.class, () -> codec.decode("[".repeat(32) + "0" + "]".repeat(32)));
        assertThrows(SaveFormatException.class, () -> codec.decode(" ".repeat(JsonSaveCodec.MAX_CHARACTERS + 1)));
        assertThrows(SaveFormatException.class, () -> codec.decode(valid.replace("100.0", "NaN")));
        assertThrows(SaveFormatException.class, () -> codec.decode(valid.replace("100.0", "1e999")));
        assertThrows(SaveFormatException.class, () -> codec.decode("[browser LZString save]"));
    }

    @Test
    void primitiveRoundTripPreservesPopulationJobsTraitsAndRandomDraws() {
        var game = primitive();
        game.execute(new GameCommand.Action("city-lumber", 5));
        game.execute(new GameCommand.Action("tech-club"));
        game.execute(new GameCommand.Action("city-food", 10));
        game.execute(new GameCommand.Action("tech-bone_tools"));
        game.execute(new GameCommand.Action("city-lumber", 18));
        game.execute(new GameCommand.Action("city-stone", 10));
        game.execute(new GameCommand.Action("tech-sundial"));
        game.advanceTicks(203);
        game.execute(new GameCommand.Action("tech-housing"));
        game.execute(new GameCommand.Action("city-basic_housing"));
        game.execute(new GameCommand.Action("city-food", 100));
        game.advanceTicks(5);
        assertEquals(1, game.exportSave().civilization().unemployed());
        game.execute(new GameCommand.Action("city-stone", 20));
        game.execute(new GameCommand.Action("city-lumber", 20));
        game.advanceTicks(900);
        assertEquals(io.github.evolve.core.CommandResult.Status.OK, game.execute(new GameCommand.Action("tech-stone_axe")).status());
        assertEquals(io.github.evolve.core.CommandResult.Status.OK, game.execute(new GameCommand.AssignJob("lumberjack", 1)).status());
        var restored = EvolutionEngine.restore(codec.decode(codec.encode(game.exportSave())));
        assertEquals(game.snapshot(), restored.snapshot());
        game.advanceTicks(997);
        restored.advanceTicks(997);
        assertEquals(game.exportSave(), restored.exportSave());
    }

    @Test
    void rejectsOldSchemaAndInconsistentCivilizationStates() {
        var old = JsonParser.parseString(codec.encode(EvolutionEngine.newGame(2).exportSave())).getAsJsonObject();
        old.addProperty("schemaVersion", 1);
        old.addProperty("ruleset", "evolve-1.4.10/evolution-1");
        old.remove("civilization");
        assertThrows(SaveFormatException.class, () -> codec.decode(old.toString()));
        invalidCivilization(root -> root.remove("civilization"));
        invalidCivilization(root -> root.getAsJsonObject("civilization").addProperty("unemployed", 1));
        invalidCivilization(root -> root.getAsJsonObject("civilization").addProperty("unemployed", 0.5));
        invalidCivilization(root -> root.getAsJsonObject("civilization").addProperty("unemployed", 4_294_967_296L));
        invalidCivilization(root -> root.getAsJsonObject("civilization").addProperty("primaryGenus", "plant"));
        invalidCivilization(root -> root.getAsJsonObject("civilization").getAsJsonObject("workers").addProperty("lumberjack", 1));
        invalidCivilization(root -> root.getAsJsonObject("civilization").getAsJsonObject("workers").addProperty("farmer", -1));
        invalidCivilization(root -> root.getAsJsonObject("civilization").getAsJsonObject("workers").addProperty("quarry_worker", 0.5));
        invalidCivilization(root -> root.getAsJsonObject("civilization").getAsJsonObject("workers").addProperty("farmer", 4_294_967_296L));
        invalidCivilization(root -> root.getAsJsonObject("civilization").getAsJsonObject("workers").remove("farmer"));
        invalidCivilization(root -> root.getAsJsonObject("civilization").getAsJsonObject("workers").addProperty("scientist", 0));
        invalidCivilization(root -> root.getAsJsonObject("civilization").addProperty("capacitiesInitialized", true));
        invalidCivilization(root -> root.getAsJsonObject("context").addProperty("universe", "evil"));
        invalidCivilization(root -> root.getAsJsonObject("resources").remove("Food"));
        invalidCivilization(root -> root.getAsJsonObject("resources").getAsJsonObject("human").addProperty("amount", 0.5));
        invalidCivilization(root -> root.getAsJsonObject("resources").getAsJsonObject("Food").addProperty("capacity", 500));
        invalidCivilization(root -> root.getAsJsonObject("resources").getAsJsonObject("Food").addProperty("amount", 10));
        invalidCivilization(root -> root.getAsJsonObject("resources").getAsJsonObject("RNA").addProperty("visible", true));
        invalidCivilization(root -> root.getAsJsonObject("traits").addProperty("creative", 2));
        invalidCivilization(root -> root.getAsJsonObject("technologies").addProperty("housing", 1));
        invalidCivilization(root -> root.getAsJsonObject("technologies").addProperty("currency", 1));
        invalidCivilization(root -> root.getAsJsonObject("structures").addProperty("basic_housing", 1));
        invalidCivilization(root -> root.getAsJsonObject("technologies").addProperty("agriculture", 2));
        invalidCivilization(root -> root.getAsJsonObject("structures").addProperty("farm", 1));
        invalidCivilization(root -> root.getAsJsonObject("structures").addProperty("rock_quarry", 1));
    }

    private void invalidCivilization(Consumer<JsonObject> change) {
        var root = JsonParser.parseString(codec.encode(primitive().exportSave())).getAsJsonObject();
        change.accept(root);
        assertThrows(SaveFormatException.class, () -> codec.decode(root.toString()));
    }

    @Test
    void scienceSavePreservesVacantTargetsAndFutureProduction() {
        var save = scienceSave();
        var game = EvolutionEngine.restore(save);
        var restored = EvolutionEngine.restore(codec.decode(codec.encode(save)));
        assertEquals(0, restored.exportSave().civilization().workers().get("professor"));
        assertEquals(1, restored.exportSave().civilization().assigned().get("professor"));
        game.advanceTicks(23);
        restored.advanceTicks(23);
        assertEquals(game.exportSave(), restored.exportSave());
        assertEquals(1, restored.exportSave().civilization().workers().get("professor"));
        assertEquals(600, restored.exportSave().resources().get("Knowledge").capacity());
        assertEquals(500, restored.exportSave().resources().get("Lumber").capacity());
        assertEquals(1.5, restored.exportSave().resources().get("Knowledge").changePerSecond());
    }

    @Test
    void rejectsOldSettlementSavesAndCorruptScienceDependenciesTargetsOrCapacities() {
        var changes = java.util.List.<Consumer<JsonObject>>of(
                root -> root.addProperty("schemaVersion", 3),
                root -> root.addProperty("ruleset", "evolve-1.4.10/settlement-1"),
                root -> root.getAsJsonObject("civilization").remove("assigned"),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("assigned").remove("professor"),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("assigned").addProperty("professor", 2),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("assigned").addProperty("professor", -1),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("assigned").addProperty("professor", 0.5),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("assigned").addProperty("professor", 4_294_967_296L),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("workers").addProperty("professor", 2),
                root -> root.getAsJsonObject("technologies").remove("science"),
                root -> root.getAsJsonObject("technologies").remove("storage"),
                root -> root.getAsJsonObject("technologies").remove("currency"),
                root -> root.getAsJsonObject("structures").addProperty("university", 0),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Knowledge").addProperty("capacity", 100),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Lumber").addProperty("capacity", 200),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Stone").addProperty("capacity", 200)
        );
        for (var change : changes) {
            var root = JsonParser.parseString(codec.encode(scienceSave())).getAsJsonObject();
            change.accept(root);
            assertThrows(SaveFormatException.class, () -> codec.decode(root.toString()));
        }
    }

    private static GameSave scienceSave() {
        var save = primitive().exportSave();
        var resources = new LinkedHashMap<>(save.resources());
        resources.put("Food", new GameSave.ResourceState(100, 250, true, 0));
        resources.put("Lumber", new GameSave.ResourceState(0, 500, true, 0));
        resources.put("Stone", new GameSave.ResourceState(0, 500, true, 0));
        resources.put("Knowledge", new GameSave.ResourceState(0, 600, true, 0));
        resources.put("Money", new GameSave.ResourceState(0, 1000, true, 0));
        resources.put("human", new GameSave.ResourceState(2, 2, true, 0));
        var workers = Map.of("farmer", 0, "lumberjack", 0, "quarry_worker", 0, "miner", 0, "craft_Plywood", 0, "craft_Brick", 0, "craft_Wrought_Iron", 0, "cement_worker", 0, "professor", 0, "banker", 0);
        var assigned = Map.of("farmer", 0, "lumberjack", 0, "quarry_worker", 0, "miner", 0, "craft_Plywood", 0, "craft_Brick", 0, "craft_Wrought_Iron", 0, "cement_worker", 0, "professor", 1, "banker", 0);
        return new GameSave(save.schemaVersion(), save.ruleset(), 0, 0, 0, 0, false, save.phase(), save.randomSeed(),
                save.species(), save.genus(), save.progress(), save.context(), resources,
                Map.of("basic_housing", 2, "shed", 1, "university", 1),
                Map.of("primitive", 3, "housing", 1, "currency", 1, "storage", 1, "science", 1), save.traits(),
                new CivilizationState("humanoid", 2, workers, assigned, false));
    }

    @Test
    void bankingRoundTripRestoresVacantPositionsAndRejectsForgedJobsOrStorage() {
        var base = scienceSave();
        var resources = new LinkedHashMap<>(base.resources());
        resources.put("Lumber", new GameSave.ResourceState(0, 800, true, 0));
        resources.put("Stone", new GameSave.ResourceState(0, 800, true, 0));
        resources.put("Knowledge", new GameSave.ResourceState(0, 1100, true, 0));
        resources.put("Money", new GameSave.ResourceState(0, 2800, true, 0));
        var assigned = new LinkedHashMap<>(base.civilization().assigned());
        assigned.put("banker", 1);
        var tech = new LinkedHashMap<>(base.technologies());
        tech.put("banking", 2);
        var save = new GameSave(base.schemaVersion(), base.ruleset(), 0, 0, 0, 0, false, base.phase(), base.randomSeed(),
                base.species(), base.genus(), base.progress(), base.context(), resources,
                Map.of("basic_housing", 2, "shed", 2, "university", 2, "bank", 1), tech, base.traits(),
                new CivilizationState("humanoid", 2, base.civilization().workers(), assigned, false));
        var original = EvolutionEngine.restore(save);
        var restored = EvolutionEngine.restore(codec.decode(codec.encode(save)));
        original.advanceTicks(23);
        restored.advanceTicks(23);
        assertEquals(original.exportSave(), restored.exportSave());
        assertEquals(1, restored.exportSave().civilization().workers().get("banker"));
        assertEquals(0.88, restored.exportSave().resources().get("Money").changePerSecond(), 1e-9);
        assertEquals(2800, restored.exportSave().resources().get("Money").capacity());
        for (var change : java.util.List.<Consumer<JsonObject>>of(
                root -> root.addProperty("schemaVersion", 4),
                root -> root.addProperty("ruleset", "evolve-1.4.10/science-1"),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Money").addProperty("capacity", 1000),
                root -> root.getAsJsonObject("structures").remove("bank"),
                root -> root.getAsJsonObject("technologies").remove("banking"),
                root -> root.getAsJsonObject("technologies").addProperty("banking", 1),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("assigned").remove("banker"),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("assigned").addProperty("banker", 2),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("assigned").addProperty("banker", 0.5),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("workers").addProperty("banker", 2)
        )) {
            var root = JsonParser.parseString(codec.encode(save)).getAsJsonObject();
            change.accept(root);
            assertThrows(SaveFormatException.class, () -> codec.decode(root.toString()));
        }
    }

    private static EvolutionEngine primitive() {
        var fresh = EvolutionEngine.newGame(2).exportSave();
        var resources = new LinkedHashMap<String, GameSave.ResourceState>();
        fresh.resources().forEach((id, r) -> resources.put(id, new GameSave.ResourceState(r.amount(), r.capacity(), false, 0)));
        var game = EvolutionEngine.restore(new GameSave(GameSave.SCHEMA_VERSION, GameSave.RULESET, 0, 0, 0, 0, false,
                GameSnapshot.Phase.CIVILIZATION_READY, 2, "human", "humanoid", 100, fresh.context(),
                resources, Map.of(), Map.of(), SpeciesCatalog.startingTraits("human"), CivilizationState.handoff("humanoid")));
        game.execute(new GameCommand.Action("civilization-start"));
        return game;
    }

    @Test
    void copperRoundTripKeepsPendingStorageAndValidatesMiningDependencies() {
        var base = scienceSave();
        var resources = new LinkedHashMap<>(base.resources());
        resources.put("Copper", new GameSave.ResourceState(25, 100, true, 0));
        var assigned = new LinkedHashMap<>(base.civilization().assigned());
        assigned.put("miner", 1);
        var tech = new LinkedHashMap<>(base.technologies());
        tech.put("mining", 2);
        var structures = new LinkedHashMap<>(base.structures());
        structures.put("rock_quarry", 0);
        structures.put("mine", 1);
        var save = new GameSave(base.schemaVersion(), base.ruleset(), 0, 0, 0, 0, false, base.phase(), base.randomSeed(),
                base.species(), base.genus(), base.progress(), base.context(), resources, structures, tech, base.traits(),
                new CivilizationState("humanoid", 2, base.civilization().workers(), assigned, false));
        var original = EvolutionEngine.restore(save);
        var restored = EvolutionEngine.restore(codec.decode(codec.encode(save)));
        assertEquals(100, restored.exportSave().resources().get("Copper").capacity());
        original.advanceTicks(23);
        restored.advanceTicks(23);
        assertEquals(original.exportSave(), restored.exportSave());
        assertEquals(190, restored.exportSave().resources().get("Copper").capacity());
        assertEquals(1, restored.exportSave().civilization().workers().get("miner"));
        assertEquals(1d / 7, restored.exportSave().resources().get("Copper").changePerSecond(), 1e-12);
        for (var change : java.util.List.<Consumer<JsonObject>>of(
                root -> root.addProperty("schemaVersion", 5),
                root -> root.addProperty("ruleset", "evolve-1.4.10/banking-1"),
                root -> root.getAsJsonObject("resources").remove("Copper"),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Copper").addProperty("visible", false),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Copper").addProperty("capacity", 101),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Copper").addProperty("capacity", 280),
                root -> root.getAsJsonObject("structures").remove("mine"),
                root -> root.getAsJsonObject("structures").remove("rock_quarry"),
                root -> root.getAsJsonObject("technologies").addProperty("mining", 1),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("assigned").remove("miner"),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("assigned").addProperty("miner", 2),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("assigned").addProperty("miner", 0.5),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("workers").addProperty("miner", 2)
        )) {
            var root = JsonParser.parseString(codec.encode(save)).getAsJsonObject();
            change.accept(root);
            assertThrows(SaveFormatException.class, () -> codec.decode(root.toString()));
        }
    }

    @Test
    void savesKeepPreviousBackupAndBadPrimaryIsNeverSilentlyOverwritten() throws IOException {
        var store = new AtomicFileSaveStore(temporary.resolve("world/game.json"), codec);
        var game = EvolutionEngine.newGame(2);
        assertTrue(store.load().isEmpty());
        var first = game.exportSave();
        store.save(first);
        assertEquals(first, store.load().orElseThrow());
        assertTrue(store.loadBackup().isEmpty());
        game.execute(new GameCommand.Action("evolution-rna", 10));
        store.save(game.exportSave());
        assertEquals(first, store.loadBackup().orElseThrow());
        assertEquals(game.exportSave(), store.load().orElseThrow());
        Files.writeString(store.path(), "broken");
        assertThrows(IOException.class, store::load);
        assertThrows(IOException.class, () -> store.save(game.exportSave()));
        assertEquals("broken", Files.readString(store.path()));
        assertEquals(first, store.loadBackup().orElseThrow());
        try (var files = Files.list(store.path().getParent())) {
            assertEquals(2, files.count(), "Temporary save files must be cleaned up");
        }
    }

    @Test
    void ironRoundTripKeepsPendingShedCapacityAndRejectsForgedUnlocks() {
        var base = scienceSave();
        var resources = new LinkedHashMap<>(base.resources());
        resources.put("Lumber", new GameSave.ResourceState(0, 2600, true, 0));
        resources.put("Stone", new GameSave.ResourceState(0, 2600, true, 0));
        resources.put("Knowledge", new GameSave.ResourceState(0, 2600, true, 0));
        resources.put("Money", new GameSave.ResourceState(0, 4600, true, 0));
        resources.put("Copper", new GameSave.ResourceState(0, 100, true, 0));
        resources.put("Iron", new GameSave.ResourceState(0, 100, true, 0));
        var assigned = new LinkedHashMap<>(base.civilization().assigned());
        assigned.put("miner", 1);
        var tech = new LinkedHashMap<>(base.technologies());
        tech.put("mining", 3);
        tech.put("banking", 1);
        var save = new GameSave(base.schemaVersion(), base.ruleset(), 0, 0, 0, 0, false, base.phase(), base.randomSeed(),
                base.species(), base.genus(), base.progress(), base.context(), resources,
                Map.of("basic_housing", 2, "shed", 8, "university", 5, "bank", 2, "mine", 1, "rock_quarry", 0), tech, base.traits(),
                new CivilizationState("humanoid", 2, base.civilization().workers(), assigned, false));
        var original = EvolutionEngine.restore(save);
        var restored = EvolutionEngine.restore(codec.decode(codec.encode(save)));
        assertEquals(100, restored.exportSave().resources().get("Iron").capacity());
        original.advanceTicks(23);
        restored.advanceTicks(23);
        assertEquals(original.exportSave(), restored.exportSave());
        assertEquals(1100, restored.exportSave().resources().get("Iron").capacity());
        assertEquals(0.25, restored.exportSave().resources().get("Iron").changePerSecond());
        for (var change : java.util.List.<Consumer<JsonObject>>of(
                root -> root.addProperty("schemaVersion", 6),
                root -> root.addProperty("ruleset", "evolve-1.4.10/copper-1"),
                root -> root.getAsJsonObject("resources").remove("Iron"),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Iron").addProperty("visible", false),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Iron").addProperty("capacity", 101),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Iron").addProperty("capacity", 1225),
                root -> root.getAsJsonObject("technologies").addProperty("mining", 2)
        )) {
            var root = JsonParser.parseString(codec.encode(save)).getAsJsonObject();
            change.accept(root);
            assertThrows(SaveFormatException.class, () -> codec.decode(root.toString()));
        }
    }

    @Test
    void cementRoundTripPreservesPendingStorageInputThresholdAndSawmillCapacity() {
        var base = scienceSave();
        var resources = new LinkedHashMap<>(base.resources());
        resources.put("Lumber", new GameSave.ResourceState(0, 700, true, 0));
        resources.put("Stone", new GameSave.ResourceState(0.75, 500, true, 0));
        resources.put("Copper", new GameSave.ResourceState(0, 100, true, 0));
        resources.put("Iron", new GameSave.ResourceState(0, 100, true, 0));
        resources.put("Cement", new GameSave.ResourceState(0, 100, true, 0));
        var workers = new LinkedHashMap<>(base.civilization().workers());
        workers.put("lumberjack", 1);
        workers.put("cement_worker", 1);
        var tech = new LinkedHashMap<>(base.technologies());
        tech.put("mining", 3);
        tech.put("axe", 1);
        tech.put("cement", 1);
        tech.put("saw", 1);
        var structures = new LinkedHashMap<>(base.structures());
        structures.put("lumber_yard", 0);
        structures.put("rock_quarry", 0);
        structures.put("mine", 1);
        structures.put("cement_plant", 1);
        structures.put("sawmill", 1);
        var save = new GameSave(base.schemaVersion(), base.ruleset(), 0, 0, 0, 0, false, base.phase(), base.randomSeed(),
                base.species(), base.genus(), base.progress(), base.context(), resources, structures, tech, base.traits(),
                new CivilizationState("humanoid", 0, workers, workers, false));
        var original = EvolutionEngine.restore(save);
        var restored = EvolutionEngine.restore(codec.decode(codec.encode(save)));
        assertEquals(100, restored.exportSave().resources().get("Cement").capacity());
        original.advanceTicks(5);
        restored.advanceTicks(5);
        assertEquals(original.exportSave(), restored.exportSave());
        assertEquals(0.1, restored.exportSave().resources().get("Cement").amount(), 1e-12);
        assertEquals(-3, restored.exportSave().resources().get("Stone").changePerSecond());
        assertEquals(1.05, restored.exportSave().resources().get("Lumber").changePerSecond());
        restored = EvolutionEngine.restore(codec.decode(codec.encode(restored.exportSave())));
        original.advanceTicks(18);
        restored.advanceTicks(18);
        assertEquals(original.exportSave(), restored.exportSave());
        assertEquals(0.1, restored.exportSave().resources().get("Cement").amount(), 1e-12);
        assertEquals(0, restored.exportSave().resources().get("Cement").changePerSecond());
        assertEquals(200, restored.exportSave().resources().get("Cement").capacity());
        assertEquals(700, restored.exportSave().resources().get("Lumber").capacity());
        for (var change : java.util.List.<Consumer<JsonObject>>of(
                root -> root.addProperty("schemaVersion", 8),
                root -> root.addProperty("ruleset", "evolve-1.4.10/smelting-1"),
                root -> root.getAsJsonObject("resources").remove("Cement"),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Cement").addProperty("visible", false),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Cement").addProperty("capacity", 101),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Cement").addProperty("capacity", 300),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Lumber").addProperty("capacity", 500),
                root -> root.getAsJsonObject("technologies").remove("cement"),
                root -> root.getAsJsonObject("technologies").remove("saw"),
                root -> root.getAsJsonObject("technologies").remove("storage"),
                root -> root.getAsJsonObject("technologies").addProperty("mining", 2),
                root -> root.getAsJsonObject("structures").remove("cement_plant"),
                root -> root.getAsJsonObject("structures").addProperty("cement_plant", 0),
                root -> root.getAsJsonObject("structures").remove("sawmill"),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("assigned").remove("cement_worker"),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("assigned").addProperty("cement_worker", 3),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("workers").addProperty("cement_worker", 3)
        )) {
            var root = JsonParser.parseString(codec.encode(save)).getAsJsonObject();
            change.accept(root);
            assertThrows(SaveFormatException.class, () -> codec.decode(root.toString()));
        }
    }

    @Test
    void farmAndReinforcedStorageRoundTripKeepsMixedCapacitiesAndRejectsImpossibleTotals() {
        var base = scienceSave();
        var resources = new LinkedHashMap<>(base.resources());
        resources.put("Lumber", new GameSave.ResourceState(0, 1175, true, 0));
        resources.put("Stone", new GameSave.ResourceState(0, 1175, true, 0));
        resources.put("Copper", new GameSave.ResourceState(0, 393, true, 0));
        resources.put("Iron", new GameSave.ResourceState(0, 506, true, 0));
        resources.put("Cement", new GameSave.ResourceState(0, 425, true, 0));
        resources.put("human", new GameSave.ResourceState(2, 3, true, 0));
        var tech = new LinkedHashMap<>(base.technologies());
        tech.put("mining", 3);
        tech.put("cement", 1);
        tech.put("storage", 2);
        tech.put("farm", 1);
        tech.put("agriculture", 1);
        var structures = new LinkedHashMap<>(base.structures());
        structures.put("shed", 2);
        structures.put("rock_quarry", 0);
        structures.put("mine", 1);
        structures.put("cement_plant", 1);
        structures.put("farm", 2);
        var save = new GameSave(base.schemaVersion(), base.ruleset(), 0, 0, 0, 0, false, base.phase(), base.randomSeed(),
                base.species(), base.genus(), base.progress(), base.context(), resources, structures, tech, base.traits(), base.civilization());
        var original = EvolutionEngine.restore(save);
        var restored = EvolutionEngine.restore(codec.decode(codec.encode(save)));
        assertEquals(save, restored.exportSave());
        original.advanceTicks(23);
        restored.advanceTicks(23);
        assertEquals(original.exportSave(), restored.exportSave());
        assertEquals(1550, restored.exportSave().resources().get("Lumber").capacity());
        assertEquals(1550, restored.exportSave().resources().get("Stone").capacity());
        assertEquals(506, restored.exportSave().resources().get("Copper").capacity());
        assertEquals(662, restored.exportSave().resources().get("Iron").capacity());
        assertEquals(550, restored.exportSave().resources().get("Cement").capacity());
        assertEquals(4, restored.exportSave().resources().get("human").capacity());
        for (var change : java.util.List.<Consumer<JsonObject>>of(
                root -> root.addProperty("schemaVersion", 9),
                root -> root.addProperty("ruleset", "evolve-1.4.10/cement-1"),
                root -> root.getAsJsonObject("technologies").remove("farm"),
                root -> root.getAsJsonObject("technologies").remove("currency"),
                root -> root.getAsJsonObject("technologies").remove("agriculture"),
                root -> root.getAsJsonObject("technologies").remove("cement"),
                root -> root.getAsJsonObject("technologies").addProperty("mining", 2),
                root -> root.getAsJsonObject("technologies").addProperty("storage", 1),
                root -> root.getAsJsonObject("resources").getAsJsonObject("human").addProperty("capacity", 5),
                root -> root.getAsJsonObject("resources").getAsJsonObject("human").addProperty("capacity", 2.5),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Stone").addProperty("capacity", 1176),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Lumber").addProperty("capacity", 1550),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Copper").addProperty("capacity", 392),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Iron").addProperty("capacity", 663),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Cement").addProperty("capacity", 551)
        )) {
            var root = JsonParser.parseString(codec.encode(save)).getAsJsonObject();
            change.accept(root);
            assertThrows(SaveFormatException.class, () -> codec.decode(root.toString()));
        }
    }

    @Test
    void craftingRoundTripPreservesUncappedMaterialsSharedJobsAndInputThresholds() {
        var base = scienceSave();
        var resources = new LinkedHashMap<>(base.resources());
        resources.put("Lumber", new GameSave.ResourceState(0.78, 500, true, 0));
        resources.put("Copper", new GameSave.ResourceState(0, 100, true, 0));
        resources.put("Iron", new GameSave.ResourceState(0, 100, true, 0));
        resources.put("Cement", new GameSave.ResourceState(44, 100, true, 0));
        for (String id : java.util.List.of("Plywood", "Brick", "Wrought_Iron"))
            resources.put(id, new GameSave.ResourceState(1000, -1, true, 0));
        var workers = new LinkedHashMap<>(base.civilization().workers());
        workers.put("craft_Plywood", 1);
        workers.put("craft_Brick", 1);
        var tech = new LinkedHashMap<>(base.technologies());
        tech.put("mining", 3);
        tech.put("cement", 1);
        tech.put("foundry", 2);
        var structures = new LinkedHashMap<>(base.structures());
        structures.put("rock_quarry", 0);
        structures.put("mine", 1);
        structures.put("cement_plant", 1);
        structures.put("foundry", 2);
        var save = new GameSave(base.schemaVersion(), base.ruleset(), 0, 0, 0, 0, false, base.phase(), base.randomSeed(),
                base.species(), base.genus(), base.progress(), base.context(), resources, structures, tech, base.traits(),
                new CivilizationState("humanoid", 0, workers, workers, false));
        var original = EvolutionEngine.restore(save);
        var restored = EvolutionEngine.restore(codec.decode(codec.encode(save)));
        original.advanceTicks(5);
        restored.advanceTicks(5);
        assertEquals(original.exportSave(), restored.exportSave());
        assertEquals(1000, restored.exportSave().resources().get("Plywood").amount());
        assertEquals(0, restored.exportSave().resources().get("Plywood").changePerSecond());
        assertEquals(1.06 / 140, restored.exportSave().resources().get("Brick").changePerSecond(), 1e-12);
        assertEquals(-44d / 140, restored.exportSave().resources().get("Cement").changePerSecond(), 1e-12);
        restored = EvolutionEngine.restore(codec.decode(codec.encode(restored.exportSave())));
        original.advanceTicks(18);
        restored.advanceTicks(18);
        assertEquals(original.exportSave(), restored.exportSave());
        assertEquals(-1, restored.exportSave().resources().get("Brick").capacity());
        for (var change : java.util.List.<Consumer<JsonObject>>of(
                root -> root.addProperty("schemaVersion", 10),
                root -> root.addProperty("ruleset", "evolve-1.4.10/farm-storage-1"),
                root -> root.getAsJsonObject("resources").remove("Brick"),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Plywood").addProperty("capacity", 100),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Iron").addProperty("capacity", -1),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Brick").addProperty("visible", false),
                root -> root.getAsJsonObject("technologies").remove("foundry"),
                root -> root.getAsJsonObject("structures").remove("foundry"),
                root -> root.getAsJsonObject("structures").addProperty("foundry", 1),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("assigned").addProperty("craft_Plywood", 2),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("workers").remove("craft_Plywood")
        )) {
            var root = JsonParser.parseString(codec.encode(save)).getAsJsonObject();
            change.accept(root);
            assertThrows(SaveFormatException.class, () -> codec.decode(root.toString()));
        }
    }

    @Test
    void garrisonRoundTripKeepsTrainingBoundaryAndRejectsSoldierOrFurForgeries() {
        var base = scienceSave();
        var resources = new LinkedHashMap<>(base.resources());
        resources.put("Furs", new GameSave.ResourceState(0, 100, true, 0));
        var structures = new LinkedHashMap<>(base.structures());
        structures.put("garrison", 1);
        var tech = new LinkedHashMap<>(base.technologies());
        tech.put("military", 1);
        var civ = base.civilization();
        var save = new GameSave(base.schemaVersion(), base.ruleset(), 0, 0, 0, 0, false, base.phase(), base.randomSeed(),
                base.species(), base.genus(), base.progress(), base.context(), resources, structures, tech, base.traits(),
                new CivilizationState(civ.primaryGenus(), civ.unemployed(), civ.workers(), civ.assigned(), false, civ.smelter(),
                        new io.github.evolve.core.GarrisonState(1, 99.5, 0.5)));
        var original = EvolutionEngine.restore(save);
        var restored = EvolutionEngine.restore(codec.decode(codec.encode(save)));
        assertEquals(save, restored.exportSave());
        original.advanceTicks(5);
        restored.advanceTicks(5);
        assertEquals(original.exportSave(), restored.exportSave());
        assertEquals(2, restored.snapshot().garrison().soldiers());
        assertEquals(0.025, restored.exportSave().resources().get("Furs").amount(), 1e-12);
        assertEquals(0, restored.snapshot().garrison().trainingProgress());
        restored = EvolutionEngine.restore(codec.decode(codec.encode(restored.exportSave())));
        original.advanceTicks(18);
        restored.advanceTicks(18);
        assertEquals(original.exportSave(), restored.exportSave());
        assertEquals(225, restored.exportSave().resources().get("Furs").capacity());
        assertEquals(0.2, restored.exportSave().resources().get("Furs").changePerSecond());
        for (var change : java.util.List.<Consumer<JsonObject>>of(
                root -> root.addProperty("schemaVersion", 11),
                root -> root.addProperty("ruleset", "evolve-1.4.10/crafting-1"),
                root -> root.getAsJsonObject("civilization").remove("garrison"),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("garrison").remove("trainingRate"),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("garrison").addProperty("soldiers", 3),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("garrison").addProperty("soldiers", 0.5),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("garrison").addProperty("soldiers", 4_294_967_296L),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("garrison").addProperty("trainingProgress", 100),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("garrison").addProperty("trainingProgress", -0.5),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("garrison").addProperty("trainingProgress", 99.25),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("garrison").addProperty("trainingRate", 0),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("garrison").addProperty("trainingRate", 1),
                root -> root.getAsJsonObject("structures").remove("garrison"),
                root -> root.getAsJsonObject("technologies").remove("military"),
                root -> root.getAsJsonObject("resources").remove("Furs"),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Furs").addProperty("visible", false),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Furs").addProperty("capacity", 101),
                root -> root.getAsJsonObject("resources").getAsJsonObject("Furs").addProperty("capacity", 350)
        )) {
            var root = JsonParser.parseString(codec.encode(save)).getAsJsonObject();
            change.accept(root);
            assertThrows(SaveFormatException.class, () -> codec.decode(root.toString()));
        }
    }

    private void invalid(Consumer<JsonObject> change) {
        var root = JsonParser.parseString(codec.encode(EvolutionEngine.newGame(2).exportSave())).getAsJsonObject();
        change.accept(root);
        assertThrows(SaveFormatException.class, () -> codec.decode(root.toString()));
    }

    @Test
    void smeltingRoundTripPreservesFuelAndUnderassignedIronAcrossLoopBoundaries() {
        var base = scienceSave();
        var resources = new LinkedHashMap<>(base.resources());
        resources.put("Lumber", new GameSave.ResourceState(1.5, 5000, true, 0));
        resources.put("Stone", new GameSave.ResourceState(0, 5000, true, 0));
        resources.put("Knowledge", new GameSave.ResourceState(0, 4100, true, 0));
        resources.put("Money", new GameSave.ResourceState(0, 15400, true, 0));
        resources.put("Copper", new GameSave.ResourceState(0, 100, true, 0));
        resources.put("Iron", new GameSave.ResourceState(0, 100, true, 0));
        var assigned = new LinkedHashMap<>(base.civilization().assigned());
        assigned.put("miner", 1);
        var tech = new LinkedHashMap<>(base.technologies());
        tech.put("mining", 3);
        tech.put("banking", 1);
        tech.put("smelting", 1);
        var save = new GameSave(base.schemaVersion(), base.ruleset(), 0, 0, 0, 0, false, base.phase(), base.randomSeed(),
                base.species(), base.genus(), base.progress(), base.context(), resources,
                Map.of("basic_housing", 2, "shed", 16, "university", 8, "bank", 8, "mine", 1, "rock_quarry", 0, "smelter", 2), tech, base.traits(),
                new CivilizationState("humanoid", 2, base.civilization().workers(), assigned, false, new io.github.evolve.core.SmelterState(2, 0)));
        var original = EvolutionEngine.restore(save);
        var restored = EvolutionEngine.restore(codec.decode(codec.encode(save)));
        assertEquals(0, restored.snapshot().smelter().iron());
        original.advanceTicks(5);
        restored.advanceTicks(5);
        assertEquals(original.exportSave(), restored.exportSave());
        assertEquals(1, restored.snapshot().smelter().iron());
        assertEquals(0, restored.exportSave().resources().get("Lumber").amount());
        assertEquals(-6, restored.exportSave().resources().get("Lumber").changePerSecond());
        assertEquals(0.25 * 1.1, restored.exportSave().resources().get("Iron").changePerSecond(), 1e-12);
        restored = EvolutionEngine.restore(codec.decode(codec.encode(restored.exportSave())));
        original.advanceTicks(18);
        restored.advanceTicks(18);
        assertEquals(original.exportSave(), restored.exportSave());
        assertEquals(2, restored.snapshot().smelter().iron());
        assertEquals(0.25, restored.exportSave().resources().get("Iron").changePerSecond());
        for (var change : java.util.List.<Consumer<JsonObject>>of(
                root -> root.addProperty("schemaVersion", 7),
                root -> root.addProperty("ruleset", "evolve-1.4.10/iron-1"),
                root -> root.getAsJsonObject("civilization").remove("smelter"),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("smelter").remove("wood"),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("smelter").addProperty("wood", -1),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("smelter").addProperty("wood", 3),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("smelter").addProperty("wood", 0.5),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("smelter").addProperty("wood", 4_294_967_296L),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("smelter").addProperty("iron", 3),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("smelter").addProperty("iron", -1),
                root -> root.getAsJsonObject("civilization").getAsJsonObject("smelter").addProperty("Coal", 0),
                root -> root.getAsJsonObject("structures").remove("smelter"),
                root -> root.getAsJsonObject("technologies").remove("smelting"),
                root -> root.getAsJsonObject("technologies").addProperty("mining", 2)
        )) {
            var root = JsonParser.parseString(codec.encode(save)).getAsJsonObject();
            change.accept(root);
            assertThrows(SaveFormatException.class, () -> codec.decode(root.toString()));
        }
    }
}
