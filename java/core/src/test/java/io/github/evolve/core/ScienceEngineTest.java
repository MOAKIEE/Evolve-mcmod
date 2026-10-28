package io.github.evolve.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static io.github.evolve.core.CivilizationEngineTest.*;
import static io.github.evolve.core.CommandResult.Status;
import static org.junit.jupiter.api.Assertions.*;

class ScienceEngineTest {
    static EvolutionEngine researchSettlement() {
        var game = workingSettlement();
        waitFor(game, "Knowledge", 55);
        act(game, "tech-irrigation", 1);
        waitFor(game, "Money", 44);
        waitFor(game, "Lumber", 28);
        act(game, "city-basic_housing", 1);
        waitFor(game, "Knowledge", 20);
        act(game, "tech-storage", 1);
        waitFor(game, "Money", 75);
        waitFor(game, "Lumber", 55);
        waitFor(game, "Stone", 45);
        act(game, "city-shed", 1);
        waitFor(game, "Knowledge", 65);
        act(game, "tech-science", 1);
        waitFor(game, "Money", 400);
        waitFor(game, "Lumber", 300);
        waitFor(game, "Stone", 400);
        act(game, "city-university", 1);
        waitFor(game, "human", 6);
        return game;
    }

    @Test
    void realCommandsReachStorageUniversityAndCappedResearchJobs() {
        var game = researchSettlement();
        assertEquals(500, game.exportSave().resources().get("Lumber").capacity());
        assertEquals(600, game.exportSave().resources().get("Stone").capacity());
        assertEquals(600, game.exportSave().resources().get("Knowledge").capacity());
        var assigned = game.execute(new GameCommand.AssignJob("professor", 100));
        assertEquals(Status.NO_AVAILABLE_WORKERS, assigned.status());
        assertEquals(1, assigned.applied());
        var job = game.snapshot().jobs().stream().filter(j -> j.id().equals("professor")).findFirst().orElseThrow();
        assertEquals(1, job.capacity());
        assertEquals(1, job.workers());
        assertThrows(UnsupportedOperationException.class, () -> game.exportSave().civilization().assigned().clear());
        var before = game.exportSave();
        assertEquals(0, game.execute(new GameCommand.AssignJob("professor", 1)).applied());
        assertEquals(before, game.exportSave());
        game.advanceTicks(20);
        assertEquals(1.5, game.exportSave().resources().get("Knowledge").changePerSecond());
        assertEquals(600, game.exportSave().resources().get("Knowledge").capacity(), "midLoop retains university storage");
        assertEquals(game.exportSave(), EvolutionEngine.restore(game.exportSave()).exportSave());
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("tech-storage")).status());
    }

    @Test
    void lockedAndUnaffordableResearchNeverSpendResources() {
        var game = primitive();
        var before = game.exportSave();
        for (String id : java.util.List.of("tech-science", "tech-storage", "city-shed", "city-university"))
            assertEquals(Status.LOCKED, game.execute(new GameCommand.Action(id)).status());
        assertEquals(Status.LOCKED, game.execute(new GameCommand.AssignJob("professor", 1)).status());
        assertEquals(before, game.exportSave());
        researchHousing(game);
        before = game.exportSave();
        assertEquals(Status.UNAFFORDABLE, game.execute(new GameCommand.Action("tech-science")).status());
        assertEquals(before, game.exportSave());
        waitFor(game, "Knowledge", 65);
        var batch = game.execute(new GameCommand.Action("tech-science", 2));
        assertEquals(1, batch.applied());
        assertEquals(Status.LOCKED, batch.status());
        before = game.exportSave();
        assertEquals(Status.STORAGE_TOO_SMALL, game.execute(new GameCommand.Action("city-university")).status());
        assertEquals(before, game.exportSave());
    }

    @Test
    void finiteJobsRefillOncePerFastLoopAndPersistAfterDeath() {
        var game = researchSettlement();
        game.execute(new GameCommand.AssignJob("professor", 1));
        var save = game.exportSave();
        var resources = new LinkedHashMap<>(save.resources());
        var food = resources.get("Food");
        resources.put("Food", new GameSave.ResourceState(0, food.capacity(), true, 0));
        var workers = new LinkedHashMap<>(save.civilization().workers());
        workers.put("farmer", 0);
        workers.put("lumberjack", 5);
        workers.put("quarry_worker", 0);
        var civ = new CivilizationState("humanoid", 0, workers, true);
        game = restore(save, resources, civ, 20);
        game.advanceTicks(5);
        assertEquals(1.25, game.exportSave().resources().get("Knowledge").changePerSecond(), "hunger halves professor output only");
        assertEquals(0, game.exportSave().civilization().workers().get("professor"));
        assertEquals(1, game.exportSave().civilization().assigned().get("professor"));
        save = game.exportSave();
        workers = new LinkedHashMap<>(save.civilization().workers());
        workers.put("lumberjack", 4);
        civ = new CivilizationState("humanoid", 1, workers, save.civilization().assigned(), true);
        resources = new LinkedHashMap<>(save.resources());
        resources.put("Food", new GameSave.ResourceState(100, food.capacity(), true, 0));
        game = restore(save, resources, civ, save.randomSeed());
        var copy = EvolutionEngine.restore(game.exportSave());
        game.advanceTicks(5);
        assertEquals(1, game.exportSave().civilization().workers().get("professor"));
        assertEquals(1.5, game.exportSave().resources().get("Knowledge").changePerSecond());
        copy.advanceTicks(5);
        assertEquals(copy.exportSave(), game.exportSave());
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("professor", -1)).status());
        assertEquals(0, game.exportSave().civilization().assigned().get("professor"));
        game.advanceTicks(5);
        assertEquals(0, game.exportSave().civilization().workers().get("professor"));
    }

    private static EvolutionEngine restore(GameSave save, java.util.Map<String, GameSave.ResourceState> resources,
                                           CivilizationState civilization, int seed) {
        return EvolutionEngine.restore(new GameSave(save.schemaVersion(), save.ruleset(), save.elapsedTicks(), save.revision(),
                save.tickRemainder(), save.loopCycle(), false, save.phase(), seed, save.species(), save.genus(), save.progress(),
                save.context(), resources, save.structures(), save.technologies(), save.traits(), civilization));
    }
}
