package io.github.evolve.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static io.github.evolve.core.CommandResult.Status;
import static org.junit.jupiter.api.Assertions.*;

class EvolutionEngineTest {
    @Test
    void originalUnlocksAreDelayedUntilTheirOwnFastLoops() {
        var game = EvolutionEngine.newGame(2);
        assertEquals(100, resource(game, "RNA").capacity());
        assertEquals(1, game.snapshot().actions().size());
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("evolution-dna")).status());
        game.execute(new GameCommand.Action("evolution-rna", 10));
        game.advanceTicks(4);
        assertFalse(resource(game, "DNA").visible());
        game.advanceTicks(1);
        assertTrue(resource(game, "DNA").visible());
        assertFalse(game.exportSave().structures().containsKey("membrane"));
        game.advanceTicks(5);
        assertEquals(0, game.exportSave().structures().get("membrane"));
    }

    @Test
    void failedAndInvalidCommandsNeverSpendAnything() {
        var game = EvolutionEngine.newGame(2);
        game.execute(new GameCommand.Action("evolution-rna", 2));
        game.advanceTicks(5);
        game.execute(new GameCommand.Action("evolution-dna"));
        var before = game.exportSave();
        assertEquals(Status.UNAFFORDABLE, game.execute(new GameCommand.Action("evolution-dna")).status());
        assertEquals(before, game.exportSave());
        assertEquals(Status.INVALID_COMMAND, game.execute(new GameCommand.Action("evolution-rna", -1)).status());
        assertEquals(Status.INVALID_COMMAND, game.execute(new GameCommand.Action("evolution-rna", 101)).status());
        assertEquals(Status.INVALID_COMMAND, game.execute(null).status());
        assertEquals(Status.UNKNOWN_ACTION, game.execute(new GameCommand.Action("set-resource")).status());
        assertEquals(before, game.exportSave());
    }

    @Test
    void pauseAndTickChunkingSurviveReloadExactly() {
        var single = EvolutionEngine.newGame(23);
        var batched = EvolutionEngine.newGame(23);
        for (int tick = 0; tick < 137; tick++) single.advanceTicks(1);
        batched.advanceTicks(3);
        batched = EvolutionEngine.restore(batched.exportSave());
        batched.advanceTicks(134);
        assertEquals(single.exportSave(), batched.exportSave());
        batched.execute(new GameCommand.Pause(true));
        var paused = batched.exportSave();
        batched.advanceTicks(1200);
        assertEquals(paused, batched.exportSave());
        assertEquals(Status.PAUSED, batched.execute(new GameCommand.Action("evolution-rna")).status());
        assertThrows(IllegalArgumentException.class, () -> single.advanceTicks(1201));
        assertThrows(IllegalArgumentException.class, () -> single.advanceTicks(-1));
    }

    @Test
    void independentPlayersDoNotShareStateAndEngineRejectsWrongThread() throws Exception {
        var first = EvolutionEngine.newGame(1);
        var second = EvolutionEngine.newGame(1);
        first.execute(new GameCommand.Action("evolution-rna", 12));
        assertEquals(0, resource(second, "RNA").amount());
        var snapshot = first.snapshot();
        try (var executor = Executors.newSingleThreadExecutor()) {
            assertTrue(executor.submit(() -> {
                assertThrows(IllegalStateException.class, first::snapshot);
                return snapshot.resources().getFirst().amount() == 12;
            }).get());
        }
    }

    @Test
    void freshGameCanReachCivilizationHandoffThroughRealCommands() {
        var game = EvolutionEngine.newGame(2);
        buildCell(game);
        for (int i = 0; i < 10; i++) buy(game, "membrane");
        for (int i = 0; i < 5; i++) buy(game, "eukaryotic_cell");
        buy(game, "mitochondria");
        buy(game, "mitochondria");
        for (int i = 0; i < 20; i++) buy(game, "membrane");
        for (int i = 0; i < 20; i++) buy(game, "eukaryotic_cell");
        for (String id : new String[] { "sexual_reproduction", "phagocytosis", "multicellular", "bilateral_symmetry", "mammals", "humanoid", "sentience" })
            buy(game, id);
        assertEquals(GameSnapshot.Phase.CIVILIZATION_READY, game.snapshot().phase());
        assertEquals("humanoid", game.snapshot().genus());
        assertEquals(100, game.snapshot().progress());
        assertEquals(game.snapshot().species().equals("human") ? 1 : 0, game.snapshot().actions().size());
        assertEquals(SpeciesCatalog.startingTraits(game.snapshot().species()), game.snapshot().traits());
        assertEquals(game.exportSave(), EvolutionEngine.restore(game.exportSave()).exportSave());
        assertEquals(Status.PHASE_COMPLETE, game.execute(new GameCommand.Action("evolution-rna")).status());
        var handoff = game.exportSave();
        game.advanceTicks(100);
        assertEquals(handoff, game.exportSave());
        if (game.snapshot().species().equals("human")) {
            assertEquals("humanoid", game.exportSave().civilization().primaryGenus());
            assertEquals(Status.OK, game.execute(new GameCommand.Action("civilization-start")).status());
            assertEquals(GameSnapshot.Phase.PRIMITIVE, game.snapshot().phase());
        }
    }

    static void buildCell(EvolutionEngine game) {
        game.execute(new GameCommand.Action("evolution-rna", 100));
        game.advanceTicks(10);
        game.execute(new GameCommand.Action("evolution-dna", 50));
        game.advanceTicks(5);
        buy(game, "organelles");
        buy(game, "organelles");
        game.advanceTicks(5);
        buy(game, "nucleus");
        game.advanceTicks(5);
        buy(game, "eukaryotic_cell");
        game.advanceTicks(5);
        buy(game, "mitochondria");
        game.advanceTicks(20);
    }

    static void buy(EvolutionEngine game, String id) {
        String actionId = "evolution-" + id;
        for (int attempt = 0; attempt < 2000; attempt++) {
            var action = game.snapshot().actions().stream().filter(a -> a.id().equals(actionId)).findFirst().orElseThrow();
            assertTrue(action.fitsStorage(), "Insufficient capacity for " + id);
            if (action.enabled()) {
                assertEquals(1, game.execute(new GameCommand.Action(actionId)).applied(), id);
                game.advanceTicks(5);
                return;
            }
            game.execute(new GameCommand.Action("evolution-rna", 100));
            if (resource(game, "DNA").amount() < action.costs().getOrDefault("DNA", 0d)) {
                int count = Math.min(100, (int) (resource(game, "RNA").amount() / 2));
                if (count > 0) game.execute(new GameCommand.Action("evolution-dna", count));
            }
            game.advanceTicks(5);
        }
        fail("Could not fund " + id);
    }

    static GameSnapshot.ResourceView resource(EvolutionEngine game, String id) {
        return game.snapshot().resources().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }
}
