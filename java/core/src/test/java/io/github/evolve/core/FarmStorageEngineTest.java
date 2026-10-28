package io.github.evolve.core;

import org.junit.jupiter.api.Test;

import static io.github.evolve.core.CivilizationEngineTest.*;
import static io.github.evolve.core.IronEngineTest.buyExpanded;
import static io.github.evolve.core.CommandResult.Status;
import static org.junit.jupiter.api.Assertions.*;

class FarmStorageEngineTest {
    @Test
    void farmHousingAppliesToOldFarmsAtMidLoopAndNewFarmsImmediately() {
        var game = ScienceEngineTest.researchSettlement();
        int houses = game.exportSave().structures().get("basic_housing");
        int farms = game.exportSave().structures().get("farm");
        buyExpanded(game, "tech-farm_house");
        assertEquals(houses, game.exportSave().resources().get("human").capacity());
        assertEquals(houses, EvolutionEngine.restore(game.exportSave()).exportSave().resources().get("human").capacity());
        var farm = game.snapshot().actions().stream().filter(a -> a.id().equals("city-farm")).findFirst().orElseThrow();
        farm.costs().forEach((id, amount) -> waitFor(game, id, amount));
        double previous = game.exportSave().resources().get("human").capacity();
        act(game, "city-farm", 1);
        assertEquals(previous + 1, game.exportSave().resources().get("human").capacity());
        assertTrue(game.snapshot().actions().stream().filter(a -> a.id().equals("city-farm")).findFirst().orElseThrow()
                .tooltip().stream().anyMatch(t -> t.key().equals("evolve.ui_farm_population")));
        var restored = EvolutionEngine.restore(game.exportSave());
        game.advanceTicks(23);
        restored.advanceTicks(23);
        assertEquals(game.exportSave(), restored.exportSave());
        assertEquals(houses + farms + 1, game.exportSave().resources().get("human").capacity());
        waitFor(game, "human", houses + 1);
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("farmer", 1)).status());
        waitFor(game, "human", houses + farms + 1);
        var before = game.exportSave();
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("tech-farm_house")).status());
        assertEquals(before, game.exportSave());
    }

    @Test
    void reinforcedShedsKeepPendingAndMixedCapacitiesUntilMidLoop() {
        var game = IronEngineTest.ironToolsSettlement();
        buyExpanded(game, "tech-cement");
        buyExpanded(game, "city-cement_plant");
        waitFor(game, "Stone", 3000);
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("banker", -1)).status());
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("cement_worker", 1)).status());
        waitFor(game, "Cement", 500);
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("cement_worker", -1)).status());
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("banker", 1)).status());
        var shed = game.snapshot().actions().stream().filter(a -> a.id().equals("city-shed")).findFirst().orElseThrow();
        shed.costs().forEach((id, amount) -> waitFor(game, id, amount + (id.equals("Money") ? 3750 : 0)));
        waitFor(game, "Iron", 750);
        waitFor(game, "Knowledge", 2550);
        var before = game.exportSave();
        act(game, "tech-reinforced_shed", 1);
        assertEquals(before.resources().get("Lumber").capacity(), game.exportSave().resources().get("Lumber").capacity());
        assertEquals(game.exportSave(), EvolutionEngine.restore(game.exportSave()).exportSave());
        act(game, "city-shed", 1);
        for (var entry : java.util.Map.of("Lumber", 675, "Stone", 675, "Copper", 203, "Iron", 281, "Cement", 225).entrySet())
            assertEquals(before.resources().get(entry.getKey()).capacity() + entry.getValue(), game.exportSave().resources().get(entry.getKey()).capacity());
        var restored = EvolutionEngine.restore(game.exportSave());
        int sheds = game.exportSave().structures().get("shed");
        game.advanceTicks(23);
        restored.advanceTicks(23);
        assertEquals(game.exportSave(), restored.exportSave());
        assertEquals(100 + 203d * sheds, game.exportSave().resources().get("Copper").capacity());
        assertEquals(100 + 281d * sheds, game.exportSave().resources().get("Iron").capacity());
        assertEquals(100 + 225d * sheds, game.exportSave().resources().get("Cement").capacity());
        assertEquals(before.resources().get("Lumber").capacity() + 675 + 375d * (sheds - 1), game.exportSave().resources().get("Lumber").capacity());
        before = game.exportSave();
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("tech-reinforced_shed")).status());
        assertEquals(before, game.exportSave());
    }

    @Test
    void housingAndReinforcementRequireTheirTechnologiesAndActualCosts() {
        var game = primitive();
        var before = game.exportSave();
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("tech-farm_house")).status());
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("tech-reinforced_shed")).status());
        assertEquals(before, game.exportSave());
        game = workingSettlement();
        before = game.exportSave();
        assertEquals(Status.STORAGE_TOO_SMALL, game.execute(new GameCommand.Action("tech-farm_house")).status());
        assertEquals(before, game.exportSave());
    }
}
