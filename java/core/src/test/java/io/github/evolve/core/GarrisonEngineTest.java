package io.github.evolve.core;

import org.junit.jupiter.api.Test;

import static io.github.evolve.core.CivilizationEngineTest.*;
import static io.github.evolve.core.IronEngineTest.buyExpanded;
import static io.github.evolve.core.CommandResult.Status;
import static org.junit.jupiter.api.Assertions.*;

class GarrisonEngineTest {
    @Test
    void normalCommandsTrainSeparateSoldiersAndPreserveTheCompletionBoundary() {
        var game = ScienceEngineTest.researchSettlement();
        buyExpanded(game, "tech-garrison");
        buyExpanded(game, "city-garrison");
        assertEquals(2, game.snapshot().garrison().capacity());
        assertEquals(GarrisonState.empty(), game.exportSave().civilization().garrison());
        assertEquals(100, game.exportSave().resources().get("Furs").capacity());
        assertEquals(100, EvolutionEngine.restore(game.exportSave()).exportSave().resources().get("Furs").capacity());
        int remainder = game.exportSave().tickRemainder();
        game.advanceTicks(995 - remainder);
        assertEquals(0, game.snapshot().garrison().soldiers());
        assertEquals(99.5, game.snapshot().garrison().trainingProgress());
        assertEquals(2, game.snapshot().garrison().trainingPerSecond());
        assertEquals(225, game.exportSave().resources().get("Furs").capacity());
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("professor", 1)).status());
        assertEquals(99.5, game.snapshot().garrison().trainingProgress(), "Civilian assignments preserve training");
        var restored = EvolutionEngine.restore(game.exportSave());
        double food = game.exportSave().resources().get("Food").changePerSecond();
        double money = game.exportSave().resources().get("Money").changePerSecond();
        double civilianPopulation = game.exportSave().resources().get("human").amount();
        game.advanceTicks(5);
        restored.advanceTicks(5);
        assertEquals(game.exportSave(), restored.exportSave());
        assertEquals(1, game.snapshot().garrison().soldiers());
        assertEquals(0, game.exportSave().resources().get("Furs").amount(), "Training completes after production");
        assertEquals(civilianPopulation, game.exportSave().resources().get("human").amount());
        // Assigning the professor raises civilian consumption by 0.5 and tax by 0.4.
        game.advanceTicks(5);
        assertEquals(0.1, game.exportSave().resources().get("Furs").changePerSecond());
        assertEquals(food - 0.5 - 2d / 3, game.exportSave().resources().get("Food").changePerSecond(), 1e-12);
        assertEquals(money + 0.8, game.exportSave().resources().get("Money").changePerSecond(), 1e-12);
        game.advanceTicks(995);
        assertEquals(2, game.snapshot().garrison().soldiers());
        assertEquals(0, game.snapshot().garrison().trainingProgress());
        var full = game.exportSave().civilization().garrison();
        game.advanceTicks(20);
        assertEquals(full, game.exportSave().civilization().garrison());
        assertEquals(0.2, game.exportSave().resources().get("Furs").changePerSecond());
        assertEquals(Status.OK, game.execute(new GameCommand.Pause(true)).status());
        var paused = game.exportSave();
        game.advanceTicks(1200);
        assertEquals(paused, game.exportSave());
    }

    @Test
    void garrisonResearchAndBuildingsCannotSkipPrerequisitesOrCosts() {
        var game = primitive();
        var before = game.exportSave();
        for (String id : java.util.List.of("tech-garrison", "city-garrison"))
            assertEquals(Status.LOCKED, game.execute(new GameCommand.Action(id)).status());
        assertEquals(before, game.exportSave());
        game = ScienceEngineTest.researchSettlement();
        buyExpanded(game, "tech-garrison");
        before = game.exportSave();
        assertEquals(Status.UNAFFORDABLE, game.execute(new GameCommand.Action("city-garrison")).status());
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("tech-garrison")).status());
        assertEquals(before, game.exportSave());
        assertEquals(Status.LOCKED, game.execute(new GameCommand.AssignJob("soldier", 1)).status());
    }
}
