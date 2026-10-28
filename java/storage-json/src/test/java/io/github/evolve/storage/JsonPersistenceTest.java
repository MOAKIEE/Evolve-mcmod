package io.github.evolve.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.evolve.core.EvolutionEngine;
import io.github.evolve.core.GameCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

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
        invalid(root -> root.addProperty("schemaVersion", 2));
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
        assertThrows(SaveFormatException.class, () -> codec.decode(valid.replaceFirst("\\{", "{\"schemaVersion\":1,")));
        assertThrows(SaveFormatException.class, () -> codec.decode(valid + "false"));
        assertThrows(SaveFormatException.class, () -> codec.decode(valid.substring(0, valid.length() / 2)));
        assertThrows(SaveFormatException.class, () -> codec.decode("[".repeat(32) + "0" + "]".repeat(32)));
        assertThrows(SaveFormatException.class, () -> codec.decode(" ".repeat(JsonSaveCodec.MAX_CHARACTERS + 1)));
        assertThrows(SaveFormatException.class, () -> codec.decode(valid.replace("100.0", "NaN")));
        assertThrows(SaveFormatException.class, () -> codec.decode(valid.replace("100.0", "1e999")));
        assertThrows(SaveFormatException.class, () -> codec.decode("[browser LZString save]"));
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
