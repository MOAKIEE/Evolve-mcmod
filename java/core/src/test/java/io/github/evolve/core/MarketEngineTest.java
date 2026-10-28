package io.github.evolve.core;

import org.junit.jupiter.api.Test;

import static io.github.evolve.core.CivilizationEngineTest.*;
import static io.github.evolve.core.IronEngineTest.buyExpanded;
import static io.github.evolve.core.CommandResult.Status;
import static org.junit.jupiter.api.Assertions.*;

class MarketEngineTest {
    @Test
    void realResearchTradesWholeUnitsAndKeepsMarketAcrossOtherStateUpdates() {
        var game = IronEngineTest.ironToolsSettlement();
        assertTrue(game.snapshot().market().isEmpty());
        buyExpanded(game, "tech-government");
        buyExpanded(game, "tech-market");
        assertEquals(2, game.exportSave().technologies().get("currency"));
        assertEquals(1, game.exportSave().technologies().get("govern"));
        buyExpanded(game, "city-bank");
        waitFor(game, "Copper", 100);
        var before = game.exportSave();
        double price = before.civilization().market().prices().get("Copper");
        assertEquals(Status.OK, game.execute(new GameCommand.Trade("Copper", 50, false)).status());
        assertEquals(before.resources().get("Copper").amount() - 50, game.exportSave().resources().get("Copper").amount());
        assertEquals(before.resources().get("Money").amount() + Math.round(price / 4 * 50), game.exportSave().resources().get("Money").amount());
        before = game.exportSave();
        price = before.civilization().market().prices().get("Copper");
        assertEquals(Status.OK, game.execute(new GameCommand.Trade("Copper", 25, true)).status());
        assertEquals(before.resources().get("Copper").amount() + 25, game.exportSave().resources().get("Copper").amount());
        assertEquals(before.resources().get("Money").amount() - Math.round(price * 25), game.exportSave().resources().get("Money").amount());
        assertNotEquals(before.randomSeed(), game.exportSave().randomSeed());
        var market = game.exportSave().civilization().market();
        assertThrows(UnsupportedOperationException.class, () -> market.prices().put("Copper", 0d));
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("miner", -1)).status());
        assertEquals(market, game.exportSave().civilization().market());
        assertEquals(Status.OK, game.execute(new GameCommand.AssignJob("miner", 1)).status());
        var restored = EvolutionEngine.restore(game.exportSave());
        for (int i = 0; i < 217; i++) game.advanceTicks(1);
        restored.advanceTicks(217);
        assertEquals(game.exportSave(), restored.exportSave());
        assertNotEquals(market, game.exportSave().civilization().market());
        var quote = game.snapshot().market().stream().filter(o -> o.resourceId().equals("Copper")).findFirst().orElseThrow();
        assertEquals(game.exportSave().civilization().market().prices().get("Copper"), quote.buyPrice());
        assertEquals(quote.buyPrice() / 4, quote.sellPrice());
        game.execute(new GameCommand.Pause(true));
        before = game.exportSave();
        assertEquals(Status.PAUSED, game.execute(new GameCommand.Trade("Copper", 10, true)).status());
        assertTrue(game.snapshot().market().stream().allMatch(o -> o.buyAvailable() == 0 && o.sellAvailable() == 0));
        assertEquals(before, game.exportSave());
    }

    @Test
    void marketChecksQuantityResearchAndResourceBeforeConsumingRandomness() {
        var game = primitive();
        var before = game.exportSave();
        for (int quantity : new int[] { Integer.MIN_VALUE, -1, 0, 101, Integer.MAX_VALUE })
            assertEquals(Status.INVALID_COMMAND, game.execute(new GameCommand.Trade("Lumber", quantity, true)).status());
        assertEquals(Status.INVALID_COMMAND, game.execute(new GameCommand.Trade(null, 1, true)).status());
        for (String id : java.util.List.of("Lumber", "Money", "Knowledge", "Plywood", "unknown"))
            assertEquals(Status.LOCKED, game.execute(new GameCommand.Trade(id, 1, true)).status());
        for (String id : java.util.List.of("tech-government", "tech-market"))
            assertEquals(Status.LOCKED, game.execute(new GameCommand.Action(id)).status());
        assertEquals(before, game.exportSave());
    }
}
