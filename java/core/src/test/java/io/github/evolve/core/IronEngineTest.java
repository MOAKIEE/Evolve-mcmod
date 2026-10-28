package io.github.evolve.core;

import org.junit.jupiter.api.Test;

import static io.github.evolve.core.CivilizationEngineTest.*;
import static io.github.evolve.core.CommandResult.Status;
import static org.junit.jupiter.api.Assertions.*;

class IronEngineTest {
    static EvolutionEngine ironToolsSettlement() {
        var game = ScienceEngineTest.researchSettlement();
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("professor", 1)).status());
        buyExpanded(game, "city-farm");
        buyExpanded(game, "city-farm");
        for (int i = 0; i < 4; i++) buyExpanded(game, "city-basic_housing");
        waitFor(game, "human", 10);
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("farmer", 2)).status());
        buyExpanded(game, "tech-metal_working");
        buyExpanded(game, "city-mine");
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("miner", 1)).status());
        buyExpanded(game, "tech-banking");
        buyExpanded(game, "city-bank");
        buyExpanded(game, "tech-copper_axes");
        buyExpanded(game, "tech-copper_pickaxe");
        buyExpanded(game, "tech-investing");
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("banker", 1)).status());
        buyExpanded(game, "tech-iron_mining");
        assertEquals(100, game.exportSave().resources().get("Iron").capacity());
        var pending = EvolutionEngine.restore(game.exportSave());
        assertEquals(100, pending.exportSave().resources().get("Iron").capacity());
        var sheds = game.exportSave().structures().get("shed");
        game.advanceTicks(20);
        assertEquals(100 + 125d * sheds, game.exportSave().resources().get("Iron").capacity());
        assertEquals(1.15 * 0.25, game.exportSave().resources().get("Iron").changePerSecond(), 1e-12);
        assertEquals(1.15 / 7, game.exportSave().resources().get("Copper").changePerSecond(), 1e-12);
        buyExpanded(game, "tech-iron_axes");
        buyExpanded(game, "tech-iron_pickaxe");
        return game;
    }

    @Test
    void normalProgressionBuildsCapacityForIronMiningAndBothIronTools() {
        var game = ironToolsSettlement();
        game.advanceTicks(20);
        assertEquals(3, game.exportSave().technologies().get("axe"));
        assertEquals(2, game.exportSave().technologies().get("pickaxe"));
        assertEquals(1.7, game.exportSave().resources().get("Lumber").changePerSecond(), 1e-12);
        assertEquals(1.3 / 7, game.exportSave().resources().get("Copper").changePerSecond(), 1e-12);
        assertEquals(1.3 * 0.25, game.exportSave().resources().get("Iron").changePerSecond(), 1e-12);
        assertTrue(game.exportSave().structures().get("university") >= 7);
        var restored = EvolutionEngine.restore(game.exportSave());
        for (int i = 0; i < 39; i++) game.advanceTicks(1);
        restored.advanceTicks(39);
        assertEquals(game.exportSave(), restored.exportSave());
        var before = game.exportSave();
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("tech-iron_mining")).status());
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("tech-iron_axes")).status());
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("tech-iron_pickaxe")).status());
        assertEquals(before, game.exportSave());
    }

    @Test
    void ironResearchRequiresTheActualMiningPrerequisites() {
        var game = primitive();
        var before = game.exportSave();
        for (String id : java.util.List.of("tech-iron_mining", "tech-iron_axes", "tech-iron_pickaxe"))
            assertEquals(Status.LOCKED, game.execute(new GameCommand.Action(id)).status());
        assertEquals(before, game.exportSave());
        game = ScienceEngineTest.researchSettlement();
        buyExpanded(game, "tech-metal_working");
        before = game.exportSave();
        assertEquals(Status.STORAGE_TOO_SMALL, game.execute(new GameCommand.Action("tech-iron_mining")).status());
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("tech-iron_axes")).status());
        assertEquals(Status.LOCKED, game.execute(new GameCommand.Action("tech-iron_pickaxe")).status());
        assertEquals(before, game.exportSave());
    }

    static void buyExpanded(EvolutionEngine game, String id) {
        var action = game.snapshot().actions().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
        for (int expansions = 0; !action.fitsStorage(); expansions++) {
            assertTrue(expansions < 100, "Expansion stalled: " + id);
            var save = game.exportSave();
            var blocked = action.costs().entrySet().stream().filter(e -> e.getValue() > save.resources().get(e.getKey()).capacity()).findFirst().orElseThrow();
            var candidates = switch (blocked.getKey()) {
                case "Money" -> java.util.List.of("city-bank");
                case "Knowledge" -> java.util.List.of("city-university");
                case "Lumber" -> java.util.List.of("city-shed", "city-lumber_yard");
                case "Stone" -> java.util.List.of("city-shed", "city-rock_quarry");
                default -> java.util.List.of("city-shed");
            };
            var actions = game.snapshot().actions();
            String expansion = candidates.stream().filter(candidate -> !candidate.equals(id)).filter(candidate ->
                    actions.stream().anyMatch(a -> a.id().equals(candidate) && a.fitsStorage())).findFirst().orElse(candidates.getFirst());
            assertNotEquals(id, expansion, "No available storage expansion for " + id);
            buyExpanded(game, expansion);
            action = game.snapshot().actions().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
        }
        action.costs().forEach((resource, amount) -> {
            for (int attempts = 0; game.exportSave().resources().get(resource).amount() < amount; attempts++) {
                assertTrue(attempts < 600, "Production stalled: " + resource);
                if (CraftingRules.RESOURCES.contains(resource)) buyExpanded(game, "craft-" + resource);
                else game.advanceTicks(200);
            }
        });
        act(game, id, 1);
    }
}
