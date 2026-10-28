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
                resources, Map.of(), Map.of(), SpeciesCatalog.startingTraits(species), CivilizationState.handoff(primary ? genus : "")));
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

    static void waitFor(EvolutionEngine game, String id, double amount) {
        for (int ticks = 0; ticks <= 60000; ticks += 5) {
            if (game.exportSave().resources().get(id).amount() >= amount) return;
            game.advanceTicks(5);
        }
        fail("Production did not reach " + id + " " + amount);
    }

    static EvolutionEngine workingSettlement() {
        var game = primitive();
        researchHousing(game);
        act(game, "city-lumber", 100);
        act(game, "city-basic_housing", 5);
        act(game, "city-food", 100);
        waitFor(game, "Knowledge", 10);
        act(game, "tech-agriculture", 1);
        act(game, "city-lumber", 100);
        act(game, "city-stone", 100);
        act(game, "city-farm", 3);
        waitFor(game, "human", 5);
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("farmer", 3)).status());
        waitFor(game, "Knowledge", 45);
        act(game, "tech-stone_axe", 1);
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("lumberjack", 1)).status());
        waitFor(game, "Knowledge", 45);
        act(game, "tech-mining", 1);
        assertEquals(Status.LOCKED, game.execute(new GameCommand.AssignJob("quarry_worker", 1)).status());
        waitFor(game, "Lumber", 50);
        act(game, "city-rock_quarry", 1);
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("quarry_worker", 1)).status());
        waitFor(game, "Knowledge", 22);
        waitFor(game, "Lumber", 10);
        act(game, "tech-currency", 1);
        return game;
    }

    @Test
    void normalCommandsReachSelfSustainingWorkAndSixthHousing() {
        var game = workingSettlement();
        var before = game.exportSave();
        game.advanceTicks(100);
        var produced = game.exportSave();
        for (String id : java.util.List.of("Food", "Lumber", "Stone", "Money"))
            assertTrue(produced.resources().get(id).amount() > before.resources().get(id).amount(), id);
        assertEquals(1000, produced.resources().get("Food").capacity());
        waitFor(game, "Money", 44);
        waitFor(game, "Lumber", 28);
        act(game, "city-basic_housing", 1);
        assertEquals(6, game.exportSave().structures().get("basic_housing"));
        assertEquals(6, game.exportSave().resources().get("human").capacity());
        assertEquals(300, game.exportSave().resources().get("Stone").capacity());
        assertEquals(game.exportSave(), EvolutionEngine.restore(game.exportSave()).exportSave());
    }

    @Test
    void assignmentIsBoundedAndRevalidatedAndDeathsKeepWorkforceConsistent() {
        var game = workingSettlement();
        var before = game.exportSave();
        for (int invalid : new int[] { 0, 101, -101, Integer.MIN_VALUE })
            assertEquals(Status.INVALID_COMMAND, game.execute(new GameCommand.AssignJob("farmer", invalid)).status());
        assertEquals(Status.INVALID_COMMAND, game.execute(new GameCommand.AssignJob(null, 1)).status());
        assertEquals(Status.LOCKED, game.execute(new GameCommand.AssignJob("unemployed", 1)).status());
        assertEquals(Status.LOCKED, game.execute(new GameCommand.AssignJob("banker", 1)).status());
        assertEquals(Status.NO_AVAILABLE_WORKERS, game.execute(new GameCommand.AssignJob("farmer", 1)).status());
        assertEquals(before, game.exportSave());
        var removed = game.execute(new GameCommand.AssignJob("farmer", -100));
        assertEquals(Status.NO_AVAILABLE_WORKERS, removed.status());
        assertEquals(3, removed.applied());
        var added = game.execute(new GameCommand.AssignJob("lumberjack", 100));
        assertEquals(Status.NO_AVAILABLE_WORKERS, added.status());
        assertEquals(3, added.applied());
        var save = game.exportSave();
        assertThrows(UnsupportedOperationException.class, () -> save.civilization().workers().clear());
        var resources = new LinkedHashMap<>(save.resources());
        resources.put("Food", new GameSave.ResourceState(0, resources.get("Food").capacity(), true, 0));
        game = EvolutionEngine.restore(new GameSave(save.schemaVersion(), save.ruleset(), save.elapsedTicks(), save.revision(),
                save.tickRemainder(), save.loopCycle(), false, save.phase(), save.randomSeed(), save.species(), save.genus(),
                save.progress(), save.context(), resources, save.structures(), save.technologies(), save.traits(), save.civilization()));
        var restored = EvolutionEngine.restore(game.exportSave());
        for (int i = 0; i < 300; i++) {
            game.advanceTicks(1);
            var state = game.exportSave();
            assertEquals(state.resources().get("human").amount(), state.civilization().unemployed() +
                    state.civilization().workers().values().stream().mapToInt(Integer::intValue).sum());
        }
        restored.advanceTicks(300);
        assertEquals(game.exportSave(), restored.exportSave());
        assertTrue(game.exportSave().resources().get("human").amount() < 5);
        game.execute(new GameCommand.Pause(true));
        var paused = game.exportSave();
        assertEquals(Status.PAUSED, game.execute(new GameCommand.AssignJob("lumberjack", -1)).status());
        assertEquals(paused, game.exportSave());
    }

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
