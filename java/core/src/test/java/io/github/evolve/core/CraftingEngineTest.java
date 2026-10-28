package io.github.evolve.core;

import org.junit.jupiter.api.Test;

import static io.github.evolve.core.CivilizationEngineTest.*;
import static io.github.evolve.core.IronEngineTest.buyExpanded;
import static io.github.evolve.core.CommandResult.Status;
import static org.junit.jupiter.api.Assertions.*;

class CraftingEngineTest {
    @Test
    void realCommandsBuildSharedFoundriesAndCraftAllThreeMaterials() {
        var game = IronEngineTest.ironToolsSettlement();
        buyExpanded(game, "tech-foundry");
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("craft-Plywood")).status());
        buyExpanded(game, "city-foundry");
        buyExpanded(game, "city-foundry");
        buyExpanded(game, "tech-artisans");
        double before = game.exportSave().resources().get("Lumber").amount();
        act(game, "craft-Plywood", 2);
        assertEquals(before - 220, game.exportSave().resources().get("Lumber").amount());
        assertEquals(2.12, game.exportSave().resources().get("Plywood").amount(), 1e-12);
        assertEquals(-1, game.exportSave().resources().get("Plywood").capacity());
        act(game, "craft-Wrought_Iron", 1);
        assertEquals(1.06, game.exportSave().resources().get("Wrought_Iron").amount(), 1e-12);
        var save = game.exportSave();
        assertEquals(Status.STORAGE_TOO_SMALL, game.execute(new GameCommand.Action("craft-Brick")).status());
        assertEquals(save, game.exportSave());
        buyExpanded(game, "tech-cement");
        buyExpanded(game, "city-cement_plant");
        waitFor(game, "Stone", 3000);
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("banker", -1)).status());
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("cement_worker", 1)).status());
        waitFor(game, "Cement", 44);
        act(game, "craft-Brick", 1);
        assertEquals(1.06, game.exportSave().resources().get("Brick").amount(), 1e-12);
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("cement_worker", -1)).status());
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("craft_Plywood", 1)).status());
        assertEquals(Status.NO_AVAILABLE_WORKERS, game.execute(new GameCommand.AssignJob("craft_Brick", 1)).status());
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("professor", -1)).status());
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("craft_Wrought_Iron", 1)).status());
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("farmer", -1)).status());
        save = game.exportSave();
        assertEquals(Status.NO_AVAILABLE_WORKERS, game.execute(new GameCommand.AssignJob("craft_Brick", 1)).status());
        assertEquals(save, game.exportSave());
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("farmer", 1)).status());
        game.advanceTicks(5);
        assertEquals(1.06 / 140, game.exportSave().resources().get("Plywood").changePerSecond(), 1e-12);
        assertEquals(1.7 - 110d / 140, game.exportSave().resources().get("Lumber").changePerSecond(), 1e-12);
        var restored = EvolutionEngine.restore(game.exportSave());
        for (int i = 0; i < 39; i++) game.advanceTicks(1);
        restored.advanceTicks(39);
        assertEquals(game.exportSave(), restored.exportSave());
        game.execute(new GameCommand.Pause(true));
        save = game.exportSave();
        assertEquals(Status.PAUSED, game.execute(new GameCommand.Action("craft-Plywood", 100)).status());
        assertEquals(Status.PAUSED, game.execute(new GameCommand.AssignJob("craft_Plywood", -1)).status());
        assertEquals(save, game.exportSave());
    }

    @Test
    void craftingRequiresFoundryAndFailedBatchesKeepUnspentMaterials() {
        var game = primitive();
        var before = game.exportSave();
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("craft-Plywood")).status());
        assertEquals(Status.LOCKED, game.execute(new GameCommand.AssignJob("craft_Plywood", 1)).status());
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("tech-foundry")).status());
        assertEquals(before, game.exportSave());
        game = IronEngineTest.ironToolsSettlement();
        buyExpanded(game, "tech-foundry");
        buyExpanded(game, "city-foundry");
        double lumber = game.exportSave().resources().get("Lumber").amount();
        int possible = Math.min(100, (int) Math.floor(lumber / 110));
        var result = game.execute(new GameCommand.Action("craft-Plywood", 100));
        assertEquals(possible, result.applied());
        assertEquals(Status.UNAFFORDABLE, result.status());
        assertEquals(lumber - possible * 110, game.exportSave().resources().get("Lumber").amount(), 1e-9);
        assertEquals(possible, game.exportSave().resources().get("Plywood").amount());
    }
}
