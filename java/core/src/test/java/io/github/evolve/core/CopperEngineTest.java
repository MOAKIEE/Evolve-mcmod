package io.github.evolve.core;

import org.junit.jupiter.api.Test;

import static io.github.evolve.core.CivilizationEngineTest.*;
import static io.github.evolve.core.CommandResult.Status;
import static org.junit.jupiter.api.Assertions.*;

class CopperEngineTest {
    @Test
    void realCommandsReachCopperAxesAndPickaxesWithOriginalStorageTiming() {
        var game = ScienceEngineTest.researchSettlement();
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("professor", 1)).status());
        assertFalse(game.exportSave().resources().get("Copper").visible());
        buy(game, "tech-metal_working");
        assertEquals(Status.LOCKED, game.execute(new GameCommand.AssignJob("miner", 1)).status());
        buy(game, "city-mine");
        assertTrue(game.exportSave().resources().get("Copper").visible());
        assertEquals(100, game.exportSave().resources().get("Copper").capacity());
        var copy = EvolutionEngine.restore(game.exportSave());
        assertEquals(100, copy.exportSave().resources().get("Copper").capacity(), "restore must not apply old sheds early");
        act(game, "city-lumber", 100);
        act(game, "city-shed", 1);
        assertEquals(190, game.exportSave().resources().get("Copper").capacity(), "new shed applies immediately");
        game.advanceTicks(20);
        assertEquals(280, game.exportSave().resources().get("Copper").capacity(), "midLoop includes both sheds");
        buy(game, "city-basic_housing");
        waitFor(game, "human", 7);
        var assignment = game.execute(new GameCommand.AssignJob("miner", 100));
        assertEquals(Status.NO_AVAILABLE_WORKERS, assignment.status());
        assertEquals(1, assignment.applied());
        game.advanceTicks(20);
        assertEquals(1d / 7, game.exportSave().resources().get("Copper").changePerSecond(), 1e-12);
        var before = game.exportSave();
        assertEquals(Status.UNAFFORDABLE, game.execute(new GameCommand.Action("tech-copper_axes")).status());
        assertEquals(before, game.exportSave());
        buy(game, "tech-copper_axes");
        game.advanceTicks(5);
        assertEquals(1.35, game.exportSave().resources().get("Lumber").changePerSecond(), 1e-12);
        assertEquals(2, game.exportSave().technologies().get("axe"));
        assertEquals(Status.STORAGE_TOO_SMALL, game.execute(new GameCommand.Action("tech-copper_pickaxe")).status());
        buy(game, "tech-banking");
        buy(game, "city-bank");
        buy(game, "city-university");
        buy(game, "tech-copper_pickaxe");
        game.advanceTicks(5);
        assertEquals(1.15 * (1d / 7), game.exportSave().resources().get("Copper").changePerSecond(), 1e-12);
        copy = EvolutionEngine.restore(game.exportSave());
        game.advanceTicks(73);
        copy.advanceTicks(73);
        assertEquals(game.exportSave(), copy.exportSave());
    }

    @Test
    void copperActionsAreLockedBeforeMiningAndKnowledgeStorageIsAuthoritative() {
        var game = primitive();
        var before = game.exportSave();
        for (String id : java.util.List.of("tech-metal_working", "city-mine", "tech-copper_axes", "tech-copper_pickaxe"))
            assertEquals(Status.LOCKED, game.execute(new GameCommand.Action(id)).status());
        assertEquals(before, game.exportSave());
        game = workingSettlement();
        before = game.exportSave();
        assertEquals(Status.STORAGE_TOO_SMALL, game.execute(new GameCommand.Action("tech-metal_working")).status());
        assertEquals(before, game.exportSave());
    }

    private static void buy(EvolutionEngine game, String id) {
        var action = game.snapshot().actions().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
        assertTrue(action.fitsStorage(), id);
        action.costs().forEach((resource, amount) -> waitFor(game, resource, amount));
        act(game, id, 1);
    }
}
