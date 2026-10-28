package io.github.evolve.core;

import org.junit.jupiter.api.Test;

import static io.github.evolve.core.CivilizationEngineTest.*;
import static io.github.evolve.core.CommandResult.Status;
import static org.junit.jupiter.api.Assertions.*;

class SmeltingEngineTest {
    @Test
    void normalProgressionBuildsAndOperatesWoodSmeltersThroughAuthorityCommands() {
        var game = IronEngineTest.ironToolsSettlement();
        IronEngineTest.buyExpanded(game, "tech-smelting");
        IronEngineTest.buyExpanded(game, "city-smelter");
        assertEquals(new SmelterState(1, 1), game.exportSave().civilization().smelter());
        assertEquals(1, game.snapshot().smelter().capacity());
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("banker", -1)).status());
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("lumberjack", 1)).status());
        waitFor(game, "Lumber", 10);
        game.advanceTicks(20);
        double lumber = 2 * 1.7 * (1 + game.exportSave().structures().get("lumber_yard") * 0.02);
        assertEquals(lumber - 3, game.exportSave().resources().get("Lumber").changePerSecond(), 1e-12);
        assertEquals(1.3 * 0.25 * 1.1, game.exportSave().resources().get("Iron").changePerSecond(), 1e-12);
        assertEquals(1.3 / 7, game.exportSave().resources().get("Copper").changePerSecond(), 1e-12);
        var result = game.execute(new GameCommand.AdjustSmelter("Wood", -100));
        assertEquals(1, result.applied());
        assertEquals(Status.NO_AVAILABLE_CAPACITY, result.status());
        assertEquals(SmelterState.empty(), game.exportSave().civilization().smelter());
        game.advanceTicks(5);
        assertEquals(1.3 * 0.25, game.exportSave().resources().get("Iron").changePerSecond(), 1e-12);
        assertEquals(lumber, game.exportSave().resources().get("Lumber").changePerSecond(), 1e-12);
        assertEquals(Status.OK, game.execute(new GameCommand.AdjustSmelter("Wood", 1)).status());
        assertEquals(Status.OK, game.execute(new GameCommand.AdjustSmelter("Iron", -1)).status());
        var saved = game.exportSave();
        var restored = EvolutionEngine.restore(saved);
        assertEquals(new SmelterState(1, 0), restored.exportSave().civilization().smelter());
        game.advanceTicks(5);
        restored.advanceTicks(5);
        assertEquals(new SmelterState(1, 1), restored.exportSave().civilization().smelter());
        assertEquals(game.exportSave(), restored.exportSave());
        game.execute(new GameCommand.Pause(true));
        var before = game.exportSave();
        assertFalse(game.snapshot().smelter().enabled());
        assertEquals(Status.PAUSED, game.execute(new GameCommand.AdjustSmelter("Wood", -1)).status());
        game.advanceTicks(100);
        assertEquals(before, game.exportSave());
    }

    @Test
    void smelterControlsValidateIntentAndNeverAllocateBeforeUnlock() {
        var game = primitive();
        var before = game.exportSave();
        for (int delta : new int[] { 0, 101, -101, Integer.MIN_VALUE })
            assertEquals(Status.INVALID_COMMAND, game.execute(new GameCommand.AdjustSmelter("Wood", delta)).status());
        assertEquals(Status.INVALID_COMMAND, game.execute(new GameCommand.AdjustSmelter(null, 1)).status());
        for (String id : java.util.List.of("Wood", "Iron", "Coal", "Steel"))
            assertEquals(Status.LOCKED, game.execute(new GameCommand.AdjustSmelter(id, 1)).status());
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("tech-smelting")).status());
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("city-smelter")).status());
        assertEquals(before, game.exportSave());
    }
}
