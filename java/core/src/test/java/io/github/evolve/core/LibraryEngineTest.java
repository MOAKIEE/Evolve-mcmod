package io.github.evolve.core;

import org.junit.jupiter.api.Test;

import static io.github.evolve.core.CivilizationEngineTest.*;
import static io.github.evolve.core.IronEngineTest.buyExpanded;
import static io.github.evolve.core.CommandResult.Status;
import static org.junit.jupiter.api.Assertions.*;

class LibraryEngineTest {
    @Test
    void realCraftingAndHuntingBuildLibrariesAndReachResearchGrants() {
        var game = IronEngineTest.ironToolsSettlement();
        buyExpanded(game, "tech-cement");
        buyExpanded(game, "city-cement_plant");
        waitFor(game, "Stone", 3000);
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("banker", -1)).status());
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("cement_worker", 1)).status());
        buyExpanded(game, "tech-foundry");
        buyExpanded(game, "city-foundry");
        buyExpanded(game, "tech-garrison");
        buyExpanded(game, "city-garrison");
        buyExpanded(game, "tech-library");
        double capacity = game.exportSave().resources().get("Knowledge").capacity();
        buyExpanded(game, "city-library");
        assertEquals(capacity + 125, game.exportSave().resources().get("Knowledge").capacity());
        waitFor(game, "Knowledge", 1125);
        alignMidLoop(game);
        act(game, "tech-thesis", 1);
        assertEquals(0.5, game.exportSave().civilization().professorImpact());
        var restored = EvolutionEngine.restore(game.exportSave());
        game.advanceTicks(5);
        restored.advanceTicks(5);
        assertEquals(game.exportSave(), restored.exportSave());
        assertEquals(1.5 * 1.05, game.exportSave().resources().get("Knowledge").changePerSecond(), 1e-12);
        game.advanceTicks(15);
        assertEquals(0.51, game.exportSave().civilization().professorImpact());
        game.advanceTicks(5);
        assertEquals(1.51 * 1.05, game.exportSave().resources().get("Knowledge").changePerSecond(), 1e-12);
        fundLibrary(game);
        waitFor(game, "Knowledge", 3240);
        alignMidLoop(game);
        capacity = game.exportSave().resources().get("Knowledge").capacity();
        act(game, "tech-research_grant", 1);
        assertEquals(capacity, game.exportSave().resources().get("Knowledge").capacity());
        act(game, "city-library", 1);
        assertEquals(capacity + 125, game.exportSave().resources().get("Knowledge").capacity());
        assertEquals(0.52, game.exportSave().civilization().professorImpact());
        restored = EvolutionEngine.restore(game.exportSave());
        game.advanceTicks(23);
        restored.advanceTicks(23);
        assertEquals(game.exportSave(), restored.exportSave());
        int universities = game.exportSave().structures().get("university");
        assertEquals(100 + universities * 520d + 250, game.exportSave().resources().get("Knowledge").capacity(), 1e-9);
        game.advanceTicks(5);
        assertEquals(1.52 * 1.1, game.exportSave().resources().get("Knowledge").changePerSecond(), 1e-12);
        var before = game.exportSave();
        for (String id : java.util.List.of("tech-library", "tech-thesis", "tech-research_grant"))
            assertEquals(Status.LOCKED, game.execute(new GameCommand.Action(id)).status());
        assertEquals(before, game.exportSave());
    }

    private static void fundLibrary(EvolutionEngine game) {
        var library = game.snapshot().actions().stream().filter(a -> a.id().equals("city-library")).findFirst().orElseThrow();
        library.costs().forEach((id, amount) -> {
            if (CraftingRules.RESOURCES.contains(id)) {
                while (game.exportSave().resources().get(id).amount() < amount) buyExpanded(game, "craft-" + id);
            } else waitFor(game, id, amount);
        });
    }

    private static void alignMidLoop(EvolutionEngine game) {
        while (game.exportSave().tickRemainder() != 0 || game.exportSave().loopCycle() % 4 != 0) game.advanceTicks(1);
    }

    @Test
    void libraryResearchKeepsPrerequisitesAndKnowledgeCapacityCosts() {
        var game = primitive();
        var before = game.exportSave();
        for (String id : java.util.List.of("tech-library", "city-library", "tech-thesis", "tech-research_grant"))
            assertEquals(Status.LOCKED, game.execute(new GameCommand.Action(id)).status());
        assertEquals(before, game.exportSave());
        game = ScienceEngineTest.researchSettlement();
        buyExpanded(game, "tech-cement");
        before = game.exportSave();
        assertEquals(Status.STORAGE_TOO_SMALL, game.execute(new GameCommand.Action("tech-library")).status());
        assertEquals(before, game.exportSave());
    }
}
