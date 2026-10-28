package io.github.evolve.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.evolve.core.CommandResult.Status;

/** Human manual market rules and the market portion of the five-second longLoop. */
final class MarketRules {
    // Keep resource_values order: each visible resource consumes its own random rolls.
    static final Map<String, Double> BASE_VALUES;
    static {
        var values = new LinkedHashMap<String, Double>();
        values.put("Food", 5d); values.put("Lumber", 5d); values.put("Stone", 5d); values.put("Furs", 8d);
        values.put("Copper", 25d); values.put("Iron", 40d); values.put("Cement", 15d); values.put("Coal", 20d);
        BASE_VALUES = GameSnapshot.ordered(values);
    }

    private MarketRules() {}

    static boolean unlocked(EvolutionState state) {
        return state.phase == GameSnapshot.Phase.PRIMITIVE && state.tech("currency") >= 2;
    }

    private static int available(EvolutionState state, String resource, boolean buy) {
        var target = state.resources.get(resource);
        var money = state.resources.get("Money");
        double price = state.civilization.market().prices().get(resource) / (buy ? 1 : 4);
        return (int) Math.max(0, Math.floor(Math.min(100, buy ?
                Math.min(money.amount / price, target.capacity - target.amount) :
                Math.min(target.amount, (money.capacity - money.amount) / price))));
    }

    static CommandResult trade(EvolutionState state, GameCommand.Trade command) {
        if (command.resourceId() == null || command.quantity() < 1 || command.quantity() > 100)
            return new CommandResult(Status.INVALID_COMMAND, 0, state.revision);
        if (state.paused) return new CommandResult(Status.PAUSED, 0, state.revision);
        if (!unlocked(state) || !BASE_VALUES.containsKey(command.resourceId()) || !state.resources.get(command.resourceId()).visible)
            return new CommandResult(Status.LOCKED, 0, state.revision);
        String id = command.resourceId();
        var target = state.resources.get(id);
        var money = state.resources.get("Money");
        double value = state.civilization.market().prices().get(id);
        int amount = Math.min(command.quantity(), available(state, id, command.buy()));
        if (amount > 0) {
            target.amount += command.buy() ? amount : -amount;
            long payment = Math.round(value / (command.buy() ? 1 : 4) * amount);
            // Original manual transactions round the total after checking the unrounded price.
            money.amount += command.buy() ? -payment : payment;
            double change = new BigDecimal((double) amount / (1000 + state.nextInt(9000)))
                    .setScale(2, RoundingMode.HALF_UP).doubleValue();
            var prices = new LinkedHashMap<>(state.civilization.market().prices());
            prices.put(id, command.buy() ? value + change : Math.max(BASE_VALUES.get(id) / 2, value - change));
            set(state, new MarketState(prices));
            state.revision++;
        }
        Status status = Status.OK;
        if (amount < command.quantity()) {
            boolean full = command.buy() ? target.capacity - target.amount < 1 :
                    (money.capacity - money.amount) / (value / 4) < 1;
            status = full ? Status.RESOURCE_FULL : Status.UNAFFORDABLE;
        }
        return new CommandResult(status, amount, state.revision);
    }

    static List<GameSnapshot.MarketOffer> offers(EvolutionState state) {
        if (!unlocked(state)) return List.of();
        return BASE_VALUES.keySet().stream().filter(id -> state.resources.get(id).visible).map(id -> {
            double value = state.civilization.market().prices().get(id);
            return new GameSnapshot.MarketOffer(id, TextRef.of("resource_" + id + "_name"), value, value / 4,
                    state.paused ? 0 : available(state, id, true), state.paused ? 0 : available(state, id, false));
        }).toList();
    }

    static void longLoop(EvolutionState state) {
        if (!unlocked(state)) return;
        var prices = new LinkedHashMap<>(state.civilization.market().prices());
        for (var entry : BASE_VALUES.entrySet()) {
            if (!state.resources.get(entry.getKey()).visible || state.nextInt(4) != 0) continue;
            double base = entry.getValue();
            double value = prices.get(entry.getKey()) + (state.nextInt(200) - 100) / 100d;
            prices.put(entry.getKey(), value < base / 2 ? base : value > base * 3 ? base * 2 : value);
        }
        set(state, new MarketState(prices));
    }

    private static void set(EvolutionState state, MarketState market) {
        var civ = state.civilization;
        state.civilization = new CivilizationState(civ.primaryGenus(), civ.unemployed(), civ.workers(), civ.assigned(),
                civ.capacitiesInitialized(), civ.smelter(), civ.garrison(), civ.professorImpact(), market);
    }
}
