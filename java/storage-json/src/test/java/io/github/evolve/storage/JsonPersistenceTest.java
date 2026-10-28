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
        invalidCivilization(root -> root.getAsJsonObject("civilization").getAsJsonObject("workers").addProperty("banker", 0));
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

    private void invalid(Consumer<JsonObject> change) {
        var root = JsonParser.parseString(codec.encode(EvolutionEngine.newGame(2).exportSave())).getAsJsonObject();
        change.accept(root);
        assertThrows(SaveFormatException.class, () -> codec.decode(root.toString()));
    }
}
