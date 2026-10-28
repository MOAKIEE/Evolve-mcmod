package io.github.evolve.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Fixtures execute original JS functions at development time, not a duplicate Java implementation. */
class UpstreamParityTest {
    private static final Gson GSON = new Gson();
    private static final java.lang.reflect.Type COUNTS = new TypeToken<LinkedHashMap<String, Integer>>() {}.getType();

    @TestFactory
    Stream<DynamicTest> actionsAndProductionMatchOriginalFunctions() {
        var stream = getClass().getResourceAsStream("/upstream-evolution.json");
        assertNotNull(stream);
        var fixtures = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        return fixtures.getAsJsonArray("cases").asList().stream().flatMap(element -> {
            JsonObject test = element.getAsJsonObject();
            String name = test.get("name").getAsString();
            var tests = new java.util.ArrayList<DynamicTest>();
            tests.add(DynamicTest.dynamicTest(name + "/availability", () -> {
                var actual = engine(test).snapshot().actions();
                List<String> expected = test.getAsJsonArray("available").asList().stream()
                        .map(a -> a.getAsJsonObject().get("id").getAsString()).toList();
                assertEquals(expected.stream().sorted().toList(), actual.stream().map(GameSnapshot.ActionView::id).sorted().toList());
                for (var item : test.getAsJsonArray("available")) {
                    var source = item.getAsJsonObject();
                    var view = actual.stream().filter(a -> a.id().equals(source.get("id").getAsString())).findFirst().orElseThrow();
                    assertEquals(GSON.fromJson(source.get("costs"), new TypeToken<Map<String, Double>>() {}.getType()), view.costs());
                }
            }));
            for (var action : test.getAsJsonArray("actionResults")) {
                var entry = action.getAsJsonObject();
                String id = entry.get("id").getAsString();
                tests.add(DynamicTest.dynamicTest(name + "/" + id, () -> {
                    var engine = engine(test);
                    engine.execute(new GameCommand.Action(id));
                    compare(entry.getAsJsonObject("expected"), engine.exportSave());
                }));
            }
            for (var frame : test.getAsJsonArray("fastFrames")) {
                var entry = frame.getAsJsonObject();
                int ticks = entry.get("ticks").getAsInt();
                tests.add(DynamicTest.dynamicTest(name + "/ticks-" + ticks, () -> {
                    var engine = engine(test);
                    engine.advanceTicks(ticks);
                    compare(entry.getAsJsonObject("expected"), engine.exportSave());
                }));
            }
            return tests.stream();
        });
    }

    private static EvolutionEngine engine(JsonObject test) {
        var resources = new LinkedHashMap<String, GameSave.ResourceState>();
        resources.put("RNA", new GameSave.ResourceState(test.get("rna").getAsDouble(), test.get("rnaCap").getAsDouble(), true, 0));
        resources.put("DNA", new GameSave.ResourceState(test.get("dna").getAsDouble(), test.get("dnaCap").getAsDouble(), test.get("dnaVisible").getAsBoolean(), 0));
        return EvolutionEngine.restore(new GameSave(GameSave.SCHEMA_VERSION, GameSave.RULESET, 0, 0, 0, 0, false,
                GameSnapshot.Phase.EVOLUTION, 2, "protoplasm", "organism", test.get("progress").getAsInt(),
                GSON.fromJson(test.get("context"), EvolutionContext.class), resources,
                GSON.fromJson(test.get("structures"), COUNTS), GSON.fromJson(test.get("technologies"), COUNTS), Map.of()));
    }

    private static void compare(JsonObject expected, GameSave actual) {
        assertEquals(expected.get("species").getAsString(), actual.species(), "species");
        if (!actual.species().equals("protoplasm")) {
            assertEquals(GameSnapshot.Phase.CIVILIZATION_READY, actual.phase());
            return;
        }
        assertEquals(expected.get("rna").getAsDouble(), actual.resources().get("RNA").amount(), 1e-9, "RNA");
        assertEquals(expected.get("dna").getAsDouble(), actual.resources().get("DNA").amount(), 1e-9, "DNA");
        assertEquals(expected.get("rnaCap").getAsDouble(), actual.resources().get("RNA").capacity(), 1e-9, "RNA capacity");
        assertEquals(expected.get("dnaCap").getAsDouble(), actual.resources().get("DNA").capacity(), 1e-9, "DNA capacity");
        assertEquals(expected.get("dnaVisible").getAsBoolean(), actual.resources().get("DNA").visible());
        assertEquals(GSON.fromJson(expected.get("structures"), COUNTS), actual.structures(), "structures");
        assertEquals(GSON.fromJson(expected.get("technologies"), COUNTS), actual.technologies(), "technologies");
        assertEquals(expected.get("progress").getAsInt(), actual.progress(), "progress");
    }
}
