package io.github.evolve.core;

import org.junit.jupiter.api.Test;

import static io.github.evolve.core.CivilizationEngineTest.*;
import static io.github.evolve.core.IronEngineTest.buyExpanded;
import static io.github.evolve.core.CommandResult.Status;
import static org.junit.jupiter.api.Assertions.*;

class CraftingUpgradesEngineTest {
    static EvolutionEngine manufacturingSettlement() {
        var game = IronEngineTest.ironToolsSettlement();
        buyExpanded(game, "tech-foundry");
        for (int i = 0; i < 3; i++) buyExpanded(game, "city-foundry");
        buyExpanded(game, "tech-artisans");
        buyExpanded(game, "tech-apprentices");
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("tech-carpentry")).status());
        buyExpanded(game, "tech-cement");
        buyExpanded(game, "city-cement_plant");
        buyExpanded(game, "tech-farm_house");
        for (int i = 0; i < 4; i++) buyExpanded(game, "city-farm");
        waitFor(game, "human", 19);
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("farmer", 4)).status());
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("quarry_worker", 2)).status());
        waitFor(game, "Stone", 3000);
        game.execute(new GameCommand.AssignJob("banker", -1));
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("cement_worker", 1)).status());
        buyExpanded(game, "tech-iron_saw");
        buyExpanded(game, "city-sawmill");
        buyExpanded(game, "city-sawmill");
        buyExpanded(game, "tech-garrison");
        buyExpanded(game, "city-garrison");
        buyExpanded(game, "tech-library");
        buyExpanded(game, "city-library");
        buyExpanded(game, "tech-thesis");
        buyExpanded(game, "tech-research_grant");
        while (game.exportSave().resources().get("Knowledge").capacity() < 5200) {
            buyExpanded(game, "city-library");
            game.advanceTicks(20);
        }
        buyExpanded(game, "tech-carpentry");
        return game;
    }

    @Test
    void realResearchAndRecipeAssignmentsUpdateManualAndAutomaticCrafting() {
        var game = manufacturingSettlement();
        waitFor(game, "Lumber", 300);
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("craft_Plywood", 3)).status());
        double before = game.exportSave().resources().get("Plywood").amount();
        buyExpanded(game, "craft-Plywood");
        assertEquals(1.19, game.exportSave().resources().get("Plywood").amount() - before, 1e-10);
        game.advanceTicks(5);
        assertEquals(1.19 * 3 / 140, game.exportSave().resources().get("Plywood").changePerSecond(), 1e-12);
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("craft_Plywood", -1)).status());
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("craft_Brick", 1)).status());
        var restored = EvolutionEngine.restore(game.exportSave());
        game.advanceTicks(37);
        restored.advanceTicks(37);
        assertEquals(game.exportSave(), restored.exportSave());
        assertEquals(1.16 * 2 / 140, game.exportSave().resources().get("Plywood").changePerSecond(), 1e-12);
        waitFor(game, "Cement", 44);
        before = game.exportSave().resources().get("Brick").amount();
        buyExpanded(game, "craft-Brick");
        assertEquals(1.09, game.exportSave().resources().get("Brick").amount() - before, 1e-10);
        before = game.exportSave().resources().get("Wrought_Iron").amount();
        buyExpanded(game, "craft-Wrought_Iron");
        assertEquals(1.09, game.exportSave().resources().get("Wrought_Iron").amount() - before, 1e-10);
    }

    @Test
    void manufacturingResearchCannotSkipItsTechnologyChain() {
        var game = primitive();
        var before = game.exportSave();
        for (String id : java.util.List.of("tech-apprentices", "tech-carpentry"))
            assertEquals(Status.LOCKED, game.execute(new GameCommand.Action(id)).status());
        assertEquals(before, game.exportSave());
    }
}
