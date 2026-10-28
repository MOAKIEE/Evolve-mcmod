package io.github.evolve.core;

import org.junit.jupiter.api.Test;

import static io.github.evolve.core.CivilizationEngineTest.*;
import static io.github.evolve.core.IronEngineTest.buyExpanded;
import static io.github.evolve.core.CommandResult.Status;
import static org.junit.jupiter.api.Assertions.*;

class CementEngineTest {
    @Test
    void realCommandsConvertStoneAndBuildASawmill() {
        var game = IronEngineTest.ironToolsSettlement();
        buyExpanded(game, "tech-cement");
        assertEquals(Status.LOCKED, game.execute(new GameCommand.AssignJob("cement_worker", 1)).status());
        buyExpanded(game, "city-cement_plant");
        assertEquals(100, game.exportSave().resources().get("Cement").capacity());
        var pending = EvolutionEngine.restore(game.exportSave());
        assertEquals(100, pending.exportSave().resources().get("Cement").capacity());
        game.advanceTicks(20);
        assertEquals(100 + game.exportSave().structures().get("shed") * 100d, game.exportSave().resources().get("Cement").capacity());
        buyExpanded(game, "tech-iron_saw");
        waitFor(game, "Stone", 3000);
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("banker", -1)).status());
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("cement_worker", 1)).status());
        var job = game.snapshot().jobs().stream().filter(j -> j.id().equals("cement_worker")).findFirst().orElseThrow();
        assertEquals(2, job.capacity());
        assertEquals(1, job.workers());
        game.advanceTicks(20);
        assertEquals(0.4, game.exportSave().resources().get("Cement").changePerSecond(), 1e-12);
        double stone = 1 + game.exportSave().structures().get("rock_quarry") * 0.02;
        assertEquals(stone - 3, game.exportSave().resources().get("Stone").changePerSecond(), 1e-12);
        double oldCapacity = game.exportSave().resources().get("Lumber").capacity();
        buyExpanded(game, "city-sawmill");
        assertEquals(oldCapacity + 200, game.exportSave().resources().get("Lumber").capacity());
        game.advanceTicks(20);
        assertEquals(oldCapacity + 200, game.exportSave().resources().get("Lumber").capacity());
        double lumber = 1.7 * 1.05 * (1 + game.exportSave().structures().get("lumber_yard") * 0.02);
        assertEquals(lumber, game.exportSave().resources().get("Lumber").changePerSecond(), 1e-12);
        var restored = EvolutionEngine.restore(game.exportSave());
        for (int i = 0; i < 39; i++) game.advanceTicks(1);
        restored.advanceTicks(39);
        assertEquals(game.exportSave(), restored.exportSave());
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("cement_worker", -1)).status());
        game.advanceTicks(5);
        assertEquals(0, game.exportSave().resources().get("Cement").changePerSecond());
        assertEquals(stone, game.exportSave().resources().get("Stone").changePerSecond());
    }

    @Test
    void cementAndSawmillPrerequisitesCannotBeSkipped() {
        var game = primitive();
        var before = game.exportSave();
        for (String id : java.util.List.of("tech-cement", "city-cement_plant", "tech-iron_saw", "city-sawmill"))
            assertEquals(Status.LOCKED, game.execute(new GameCommand.Action(id)).status());
        assertEquals(Status.LOCKED, game.execute(new GameCommand.AssignJob("cement_worker", 1)).status());
        assertEquals(before, game.exportSave());
        game = ScienceEngineTest.researchSettlement();
        before = game.exportSave();
        assertEquals(Status.UNAFFORDABLE, game.execute(new GameCommand.Action("tech-cement")).status());
        assertEquals(before, game.exportSave());
        waitFor(game, "Knowledge", 500);
        var result = game.execute(new GameCommand.Action("tech-cement", 2));
        assertEquals(1, result.applied());
        assertEquals(Status.LOCKED, result.status());
        before = game.exportSave();
        assertEquals(Status.STORAGE_TOO_SMALL, game.execute(new GameCommand.Action("city-cement_plant")).status());
        assertEquals(before, game.exportSave());
    }
}
