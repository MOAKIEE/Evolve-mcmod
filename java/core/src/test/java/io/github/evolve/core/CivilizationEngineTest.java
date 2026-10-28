package io.github.evolve.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import static io.github.evolve.core.CommandResult.Status;
import static org.junit.jupiter.api.Assertions.*;

class CivilizationEngineTest {
    static EvolutionEngine handoff(String species, EvolutionContext context, boolean primary) {
        var resources = new LinkedHashMap<String, GameSave.ResourceState>();
        resources.put("RNA", new GameSave.ResourceState(300, 500, false, 0));
        resources.put("DNA", new GameSave.ResourceState(300, 500, false, 0));
        String genus = SpeciesCatalog.all().get(species).genus();
        return EvolutionEngine.restore(new GameSave(GameSave.SCHEMA_VERSION, GameSave.RULESET, 0, 0, 0, 0, false,
                GameSnapshot.Phase.CIVILIZATION_READY, 2, species, genus, 100, context,
                resources, Map.of(), Map.of(), SpeciesCatalog.startingTraits(species), new CivilizationState(primary ? genus : "", 0)));
    }

    static EvolutionEngine primitive() {
        var game = handoff("human", EvolutionContext.fresh(), true);
        assertEquals(Status.OK, game.execute(new GameCommand.Action("civilization-start")).status());
        return game;
    }

    static void researchHousing(EvolutionEngine game) {
        act(game, "city-lumber", 5);
        act(game, "tech-club", 1);
        act(game, "city-food", 10);
        act(game, "tech-bone_tools", 1);
        act(game, "city-lumber", 8);
        act(game, "city-stone", 10);
        act(game, "tech-sundial", 1);
        game.advanceTicks(200);
        act(game, "tech-housing", 1);
    }

    static void act(EvolutionEngine game, String id, int times) { assertEquals(Status.OK, game.execute(new GameCommand.Action(id, times)).status(), id); }

    @Test
    void initializedOnceAndUnsupportedBranchesStayAtTheHandoff() {
        var game = handoff("human", EvolutionContext.fresh(), true);
        var before = game.exportSave();
        game.advanceTicks(1200);
        assertEquals(before, game.exportSave());
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("city-lumber")).status());
        var result = game.execute(new GameCommand.Action("civilization-start", 2));
        assertEquals(Status.LOCKED, result.status());
        assertEquals(1, result.applied());
        assertEquals(GameSnapshot.Phase.PRIMITIVE, game.snapshot().phase());
        var initialized = game.exportSave();
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("civilization-start")).status());
        assertEquals(initialized, game.exportSave());
        var unsupported = handoff("elven", EvolutionContext.fresh(), true);
        assertTrue(unsupported.snapshot().actions().isEmpty());
        assertEquals(Status.LOCKED, unsupported.execute(new GameCommand.Action("civilization-start")).status());
        assertEquals(GameSnapshot.Phase.CIVILIZATION_READY, unsupported.snapshot().phase());
    }

    @Test
    void primitiveProgressionRechecksEveryCostAndStopsAtTheCurrencyBoundary() {
        var game = primitive();
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("city-food")).status());
        assertEquals(Status.UNAFFORDABLE, game.execute(new GameCommand.Action("tech-club")).status());
        researchHousing(game);
        act(game, "city-lumber", 100);
        var batch = game.execute(new GameCommand.Action("city-basic_housing", 100));
        assertEquals(5, batch.applied());
        assertEquals(Status.STORAGE_TOO_SMALL, batch.status());
        assertEquals(5, game.exportSave().structures().get("basic_housing"));
        assertEquals(5, game.exportSave().resources().get("human").capacity());
        assertFalse(game.snapshot().actions().stream().filter(a -> a.id().equals("city-basic_housing")).findFirst().orElseThrow().enabled());
        var before = game.exportSave();
        assertEquals(Status.STORAGE_TOO_SMALL, game.execute(new GameCommand.Action("city-basic_housing")).status());
        assertEquals(before, game.exportSave());
        act(game, "city-food", 100);
        game.advanceTicks(20);
        assertTrue(game.exportSave().resources().get("human").amount() > 0);
        assertEquals(game.exportSave().resources().get("human").amount(), game.snapshot().jobs().getFirst().workers());
        assertThrows(UnsupportedOperationException.class, () -> game.snapshot().jobs().clear());
    }

    @Test
    void gathersStopAtCapacityAndFailedResearchNeverPartiallySpends() {
        var game = primitive();
        act(game, "city-lumber", 100);
        act(game, "city-lumber", 99);
        var full = game.execute(new GameCommand.Action("city-lumber", 3));
        assertEquals(1, full.applied());
        assertEquals(Status.RESOURCE_FULL, full.status());
        assertEquals(200, game.exportSave().resources().get("Lumber").amount());
        act(game, "tech-club", 1);
        act(game, "city-food", 10);
        act(game, "tech-bone_tools", 1);
        var before = game.exportSave();
        assertEquals(Status.UNAFFORDABLE, game.execute(new GameCommand.Action("tech-sundial")).status());
        assertEquals(before, game.exportSave(), "Lumber must remain when Stone is missing");
        assertEquals(Status.UNKNOWN_ACTION, game.execute(new GameCommand.Action("set-population")).status());
    }

    @Test
    void primitiveTicksRemainDeterministicAcrossPauseReloadChunkingAndSessions() throws Exception {
        var single = primitive();
        researchHousing(single);
        act(single, "city-lumber", 100);
        single.execute(new GameCommand.Action("city-basic_housing", 5));
        act(single, "city-food", 100);
        single.advanceTicks(3);
        var batch = EvolutionEngine.restore(single.exportSave());
        for (int i = 0; i < 997; i++) single.advanceTicks(1);
        batch.advanceTicks(213);
        batch = EvolutionEngine.restore(batch.exportSave());
        batch.advanceTicks(784);
        assertEquals(single.exportSave(), batch.exportSave());
        batch.execute(new GameCommand.Pause(true));
        var paused = batch.exportSave();
        batch.advanceTicks(1200);
        assertEquals(Status.PAUSED, batch.execute(new GameCommand.Action("city-food")).status());
        assertEquals(paused, batch.exportSave());
        var independent = primitive();
        assertEquals(0, independent.exportSave().resources().get("Lumber").amount());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var owned = batch;
            assertTrue(executor.submit(() -> {
                assertThrows(IllegalStateException.class, () -> owned.execute(new GameCommand.Action("city-food")));
                return true;
            }).get());
        }
    }
}
