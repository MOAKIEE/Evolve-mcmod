package io.github.evolve.core;

import java.util.Map;

/** Current manual market prices, independent of resource storage and production. */
public record MarketState(Map<String, Double> prices) {
    public MarketState { prices = GameSnapshot.ordered(prices); }
    public static MarketState initial() { return new MarketState(MarketRules.BASE_VALUES); }
}
