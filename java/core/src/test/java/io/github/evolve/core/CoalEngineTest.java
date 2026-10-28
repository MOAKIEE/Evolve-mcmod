package io.github.evolve.core;

import org.junit.jupiter.api.Test;

import static io.github.evolve.core.CivilizationEngineTest.*;
import static io.github.evolve.core.IronEngineTest.buyExpanded;
import static io.github.evolve.core.CommandResult.Status;
import static org.junit.jupiter.api.Assertions.*;

class CoalEngineTest {
    @Test
    void realManufacturingBuildsCoalMinesAndSwitchesSmelterFuels() {
        var game = CraftingUpgradesEngineTest.manufacturingSettlement();
        buyExpanded(game, "tech-smelting");
        buyExpanded(game, "city-smelter");
        assertEquals(Status.LOCKED, game.execute(new GameCommand.AdjustSmelter("Coal", 1)).status());
        waitFor(game, "Knowledge", 4320);
        while (game.exportSave().tickRemainder() != 0 || game.exportSave().loopCycle() % 4 != 0) game.advanceTicks(1);
        act(game, "tech-coal_mining", 1);
        assertEquals(50, game.exportSave().resources().get("Coal").capacity());
        assertTrue(game.exportSave().resources().get("Coal").visible());
        var restored = EvolutionEngine.restore(game.exportSave());
        game.advanceTicks(20);
        restored.advanceTicks(20);
        assertEquals(game.exportSave(), restored.exportSave());
        assertEquals(50 + 75d * game.exportSave().structures().get("shed"), game.exportSave().resources().get("Coal").capacity());
        assertEquals(Status.OK, game.execute(new GameCommand.AdjustSmelter("Coal", 1)).status());
        assertEquals(new SmelterState(0, 1, 1), game.exportSave().civilization().smelter());
        buyExpanded(game, "city-coal_mine");
        buyExpanded(game, "city-coal_mine");
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("coal_miner", 2)).status());
        waitFor(game, "Coal", 1);
        game.advanceTicks(5);
        assertEquals(2 * 1.24 * 0.2 - 0.25, game.exportSave().resources().get("Coal").changePerSecond(), 1e-12);
        assertEquals(1.3 * 0.25 * 1.1, game.exportSave().resources().get("Iron").changePerSecond(), 1e-12);
        buyExpanded(game, "city-smelter");
        assertEquals(new SmelterState(1, 1, 2), game.exportSave().civilization().smelter());
        var result = game.execute(new GameCommand.AdjustSmelter("Coal", 100));
        assertEquals(Status.NO_AVAILABLE_CAPACITY, result.status());
        assertEquals(1, result.applied());
        assertEquals(new SmelterState(0, 2, 2), game.exportSave().civilization().smelter());
        assertEquals(Status.OK, game.execute(new GameCommand.AdjustSmelter("Wood", 1)).status());
        assertEquals(new SmelterState(1, 1, 2), game.exportSave().civilization().smelter());
        restored = EvolutionEngine.restore(game.exportSave());
        game.advanceTicks(79);
        restored.advanceTicks(79);
        assertEquals(game.exportSave(), restored.exportSave());
        game.execute(new GameCommand.Pause(true));
        var before = game.exportSave();
        assertEquals(Status.PAUSED, game.execute(new GameCommand.AdjustSmelter("Coal", -1)).status());
        assertEquals(Status.PAUSED, game.execute(new GameCommand.AssignJob("coal_miner", -1)).status());
        assertEquals(before, game.exportSave());
    }

    @Test
    void coalCannotBeProducedBeforeResearchAndConstruction() {
        var game = primitive();
        var before = game.exportSave();
        for (String id : java.util.List.of("tech-coal_mining", "city-coal_mine"))
            assertEquals(Status.LOCKED, game.execute(new GameCommand.Action(id)).status());
        assertEquals(Status.LOCKED, game.execute(new GameCommand.AssignJob("coal_miner", 1)).status());
        assertEquals(before, game.exportSave());
    }
}
