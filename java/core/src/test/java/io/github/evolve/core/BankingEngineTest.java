package io.github.evolve.core;

import org.junit.jupiter.api.Test;

import static io.github.evolve.core.CivilizationEngineTest.*;
import static io.github.evolve.core.CommandResult.Status;
import static org.junit.jupiter.api.Assertions.*;

class BankingEngineTest {
    @Test
    void normalProgressionReachesBankInvestmentAndBankerIncome() {
        var game = ScienceEngineTest.researchSettlement();
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("professor", 1)).status());
        buy(game, "tech-banking");
        assertEquals(Status.LOCKED, game.execute(new GameCommand.AssignJob("banker", 1)).status());
        buy(game, "city-bank");
        assertEquals(2800, game.exportSave().resources().get("Money").capacity());
        game.advanceTicks(20);
        assertEquals(2800, game.exportSave().resources().get("Money").capacity());
        assertTrue(game.snapshot().jobs().stream().noneMatch(job -> job.id().equals("banker")));
        var before = game.exportSave();
        assertEquals(Status.STORAGE_TOO_SMALL, game.execute(new GameCommand.Action("tech-investing")).status());
        assertEquals(before, game.exportSave());
        buy(game, "city-shed");
        buy(game, "city-university");
        buy(game, "tech-investing");
        assertEquals(2, game.snapshot().technologies().get("banking"));
        buy(game, "city-basic_housing");
        waitFor(game, "human", 7);
        var assigned = game.execute(new GameCommand.AssignJob("banker", 100));
        assertEquals(Status.NO_AVAILABLE_WORKERS, assigned.status());
        assertEquals(1, assigned.applied());
        var job = game.snapshot().jobs().stream().filter(j -> j.id().equals("banker")).findFirst().orElseThrow();
        assertEquals(1, job.capacity());
        assertEquals(1, job.workers());
        game.advanceTicks(20);
        assertEquals(3.08, game.exportSave().resources().get("Money").changePerSecond(), 1e-9);
        var restored = EvolutionEngine.restore(game.exportSave());
        game.advanceTicks(39);
        restored.advanceTicks(39);
        assertEquals(game.exportSave(), restored.exportSave());
        game.execute(new GameCommand.Pause(true));
        before = game.exportSave();
        assertEquals(Status.PAUSED, game.execute(new GameCommand.AssignJob("banker", -1)).status());
        assertEquals(before, game.exportSave());
        game.execute(new GameCommand.Pause(false));
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("banker", -1)).status());
        assertEquals(0, game.exportSave().civilization().assigned().get("banker"));
        game.advanceTicks(5);
        assertEquals(2.4, game.exportSave().resources().get("Money").changePerSecond(), 1e-9);
    }

    @Test
    void bankingCannotBeBoughtEarlyOrTwiceAndFailuresSpendNothing() {
        var game = primitive();
        var before = game.exportSave();
        for (String id : java.util.List.of("tech-banking", "tech-investing", "city-bank"))
            assertEquals(Status.LOCKED, game.execute(new GameCommand.Action(id)).status());
        assertEquals(before, game.exportSave());
        game = workingSettlement();
        before = game.exportSave();
        assertEquals(Status.UNAFFORDABLE, game.execute(new GameCommand.Action("tech-banking")).status());
        assertEquals(before, game.exportSave());
        waitFor(game, "Knowledge", 90);
        var result = game.execute(new GameCommand.Action("tech-banking", 2));
        assertEquals(Status.LOCKED, result.status());
        assertEquals(1, result.applied());
        assertEquals(1, game.exportSave().technologies().get("banking"));
        assertEquals(Status.LOCKED, game.execute(new GameCommand.AssignJob("banker", 1)).status());
    }

    private static void buy(EvolutionEngine game, String id) {
        var action = game.snapshot().actions().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
        assertTrue(action.fitsStorage(), id);
        action.costs().forEach((resource, amount) -> waitFor(game, resource, amount));
        act(game, id, 1);
    }
}
