package io.github.evolve.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CivilizationParityTest {
    private static final Gson GSON = new Gson();

    @TestFactory
    Stream<DynamicTest> originalSentienceActionsAndProduction() {
        var stream = getClass().getResourceAsStream("/upstream-civilization.json");
        assertNotNull(stream);
        var fixtures = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        var tests = new ArrayList<DynamicTest>();
        for (var item : fixtures.getAsJsonArray("initialization")) {
            var test = item.getAsJsonObject();
            boolean primary = test.get("primary").getAsBoolean();
            tests.add(DynamicTest.dynamicTest("sentience-primary-" + primary, () -> {
                var game = CivilizationEngineTest.handoff("human", EvolutionContext.fresh(), primary);
                assertEquals(CommandResult.Status.OK, game.execute(new GameCommand.Action("civilization-start")).status());
                compare(test.getAsJsonObject("expected"), game);
            }));
        }
        for (var item : fixtures.getAsJsonArray("cases")) {
            var test = item.getAsJsonObject();
            String name = test.get("name").getAsString();
            tests.add(DynamicTest.dynamicTest(name + "/availability-and-costs", () -> {
                var game = engine(test);
                compare(test.getAsJsonObject("input"), game);
                availability(test.getAsJsonArray("available"), game);
            }));
            for (var action : test.getAsJsonArray("actions")) {
                var entry = action.getAsJsonObject();
                String id = entry.get("id").getAsString();
                tests.add(DynamicTest.dynamicTest(name + "/" + id, () -> {
                    var game = engine(test);
                    assertEquals(CommandResult.Status.OK, game.execute(new GameCommand.Action(id)).status());
                    compare(entry.getAsJsonObject("expected"), game);
                }));
            }
        }
        for (var item : fixtures.getAsJsonArray("production")) {
            var test = item.getAsJsonObject();
            for (var frame : test.getAsJsonArray("frames")) {
                var entry = frame.getAsJsonObject();
                int ticks = entry.get("ticks").getAsInt();
                tests.add(DynamicTest.dynamicTest(test.get("name").getAsString() + "/ticks-" + ticks, () -> {
                    var game = engine(test);
                    game.advanceTicks(ticks);
                    compare(entry.getAsJsonObject("expected"), game);
                    assertEquals(game.exportSave(), EvolutionEngine.restore(game.exportSave()).exportSave());
                }));
            }
        }
        for (var item : fixtures.getAsJsonArray("assignments")) {
            var test = item.getAsJsonObject();
            tests.add(DynamicTest.dynamicTest("job/" + test.get("name").getAsString(), () -> {
                var game = engine(test);
                int delta = test.get("delta").getAsInt();
                var result = game.execute(new GameCommand.AssignJob(test.get("job").getAsString(), delta));
                assertEquals(test.get("applied").getAsInt(), result.applied());
                assertEquals(result.applied() == Math.abs(delta) ? CommandResult.Status.OK : CommandResult.Status.NO_AVAILABLE_WORKERS, result.status());
                compare(test.getAsJsonObject("expected"), game);
            }));
        }
        return tests.stream();
    }

    private static EvolutionEngine engine(JsonObject test) { return EvolutionEngine.restore(GSON.fromJson(test.get("input"), GameSave.class)); }

    private static void compare(JsonObject expected, EvolutionEngine game) {
        var save = game.exportSave();
        assertEquals(GameSnapshot.Phase.PRIMITIVE, save.phase());
        for (var entry : expected.getAsJsonObject("resources").entrySet()) {
            var value = entry.getValue().getAsJsonObject();
            var actual = save.resources().get(entry.getKey());
            assertEquals(value.get("amount").getAsDouble(), actual.amount(), 1e-9, entry.getKey() + " amount");
            assertEquals(value.get("capacity").getAsDouble(), actual.capacity(), 1e-9, entry.getKey() + " capacity");
            assertEquals(value.get("visible").getAsBoolean(), actual.visible(), entry.getKey() + " visibility");
        }
        assertEquals(GSON.fromJson(expected.get("structures"), new TypeToken<Map<String, Integer>>() {}.getType()), save.structures());
        assertEquals(GSON.fromJson(expected.get("technologies"), new TypeToken<Map<String, Integer>>() {}.getType()), save.technologies());
        assertEquals(GSON.fromJson(expected.get("traits"), new TypeToken<Map<String, Double>>() {}.getType()), save.traits());
        assertEquals(expected.has("seed") ? expected.get("seed").getAsInt() : expected.get("randomSeed").getAsInt(), save.randomSeed());
        var civilization = expected.has("civilization") ? expected.getAsJsonObject("civilization") : expected;
        assertEquals(civilization.get("unemployed").getAsInt(), save.civilization().unemployed());
        assertEquals(GSON.fromJson(civilization.get("workers"), new TypeToken<Map<String, Integer>>() {}.getType()), save.civilization().workers());
        assertEquals(civilization.get("capacitiesInitialized").getAsBoolean(), save.civilization().capacitiesInitialized());
        if (expected.has("available")) availability(expected.getAsJsonArray("available"), game);
    }

    private static void availability(com.google.gson.JsonArray expected, EvolutionEngine game) {
        var actual = new LinkedHashMap<String, Map<String, Double>>();
        game.snapshot().actions().forEach(action -> actual.put(action.id(), action.costs()));
        var available = new LinkedHashMap<String, Map<String, Double>>();
        expected.forEach(item -> {
            var entry = item.getAsJsonObject();
            available.put(entry.get("id").getAsString(), GSON.fromJson(entry.get("costs"), new TypeToken<Map<String, Double>>() {}.getType()));
        });
        assertEquals(available, actual);
    }
}
