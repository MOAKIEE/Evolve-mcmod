package io.github.evolve.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.evolve.core.EvolutionEngine;
import io.github.evolve.core.GameSave;
import io.github.evolve.core.SaveCodec;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/** Strict, bounded and versioned JSON. Browser LZString saves are deliberately a different format. */
public final class JsonSaveCodec implements SaveCodec<String> {
    public static final int MAX_CHARACTERS = 1_048_576;
    public static final int MAX_BYTES = MAX_CHARACTERS * 3;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    @Override
    public String encode(GameSave save) {
        EvolutionEngine.validateSave(save);
        return GSON.toJson(save) + "\n";
    }

    @Override
    public GameSave decode(String encoded) {
        if (encoded == null || encoded.length() > MAX_CHARACTERS) throw new SaveFormatException("Save is missing or too large");
        try (var reader = new JsonReader(new StringReader(encoded))) {
            reader.setLenient(false);
            JsonElement tree = readStrict(reader, 0, new int[] { 0 });
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new SaveFormatException("Trailing data after save");
            validateShape(tree);
            GameSave save = GSON.fromJson(tree, GameSave.class);
            EvolutionEngine.validateSave(save);
            return save;
        } catch (SaveFormatException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new SaveFormatException("Invalid or incompatible Evolve Java save", e);
        }
    }

    private static JsonElement readStrict(JsonReader reader, int depth, int[] nodes) throws IOException {
        if (depth > 16 || ++nodes[0] > 4096) throw new SaveFormatException("Save structure is too large");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                var object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (name.length() > 128 || object.has(name)) throw new SaveFormatException("Invalid or duplicate save field");
                    object.add(name, readStrict(reader, depth + 1, nodes));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                var array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(readStrict(reader, depth + 1, nodes));
                reader.endArray();
                yield array;
            }
            case STRING -> {
                String value = reader.nextString();
                if (value.length() > 512) throw new SaveFormatException("Save string is too long");
                yield new JsonPrimitive(value);
            }
            case NUMBER -> {
                String value = reader.nextString();
                if (value.length() > 64) throw new SaveFormatException("Save number is too long");
                yield new JsonPrimitive(new BigDecimal(value));
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            default -> throw new SaveFormatException("Unexpected JSON token in save");
        };
    }

    private static void validateShape(JsonElement tree) {
        var root = fields(tree, "schemaVersion", "ruleset", "elapsedTicks", "revision", "tickRemainder", "loopCycle",
                "paused", "phase", "randomSeed", "species", "genus", "progress", "context", "resources", "structures", "technologies", "traits", "civilization");
        integers(root, "schemaVersion", "tickRemainder", "loopCycle", "randomSeed", "progress");
        integer(root.get("elapsedTicks"));
        integer(root.get("revision"));
        strings(root, "ruleset", "phase", "species", "genus");
        booleans(root, "paused");
        var context = fields(root.get("context"), "biome", "universe", "seeded", "rapidMutation", "massExtinction",
                "novice", "master", "grandmaster", "unbound", "obsolete", "nightmare", "extinctSpecies", "productionMultiplier");
        strings(context, "biome", "universe");
        booleans(context, "seeded", "rapidMutation", "nightmare");
        integers(context, "massExtinction", "novice", "master", "grandmaster", "unbound", "obsolete");
        number(context.get("productionMultiplier"));
        if (!context.get("extinctSpecies").isJsonArray()) throw new SaveFormatException("Invalid extinctSpecies");
        var species = new HashSet<String>();
        for (var entry : context.getAsJsonArray("extinctSpecies")) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString() || !species.add(entry.getAsString()))
                throw new SaveFormatException("Invalid or duplicate extinct species");
        }
        var civilization = fields(root.get("civilization"), "primaryGenus", "unemployed", "workers", "assigned", "capacitiesInitialized", "smelter");
        strings(civilization, "primaryGenus");
        integers(civilization, "unemployed");
        booleans(civilization, "capacitiesInitialized");
        var smelter = fields(civilization.get("smelter"), "wood", "iron");
        integers(smelter, "wood", "iron");
        for (String section : Set.of("workers", "assigned")) {
            var workers = fields(civilization.get(section), root.get("phase").getAsString().equals("PRIMITIVE") ?
                    new String[] { "farmer", "lumberjack", "quarry_worker", "miner", "craft_Plywood", "craft_Brick", "craft_Wrought_Iron", "cement_worker", "professor", "banker" } : new String[] {});
            workers.entrySet().forEach(entry -> int32(entry.getValue()));
        }
        if (!root.get("resources").isJsonObject()) throw new SaveFormatException("Invalid resources");
        var resources = root.getAsJsonObject("resources");
        for (var resource : resources.entrySet()) {
            var value = fields(resource.getValue(), "amount", "capacity", "visible", "changePerSecond");
            for (String name : Set.of("amount", "capacity", "changePerSecond")) number(value.get(name));
            booleans(value, "visible");
        }
        for (String section : Set.of("structures", "technologies", "traits")) {
            if (!root.get(section).isJsonObject()) throw new SaveFormatException("Invalid " + section);
            for (var entry : root.getAsJsonObject(section).entrySet()) {
                if (section.equals("traits")) number(entry.getValue());
                else int32(entry.getValue());
            }
        }
    }

    private static JsonObject fields(JsonElement element, String... names) {
        if (!element.isJsonObject() || !element.getAsJsonObject().keySet().equals(Set.of(names)))
            throw new SaveFormatException("Missing or unknown save fields");
        return element.getAsJsonObject();
    }

    private static void integers(JsonObject object, String... names) {
        for (String name : names) int32(object.get(name));
    }

    private static void int32(JsonElement element) {
        integer(element);
        try { element.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException e) { throw new SaveFormatException("Save counter is outside the integer range", e); }
    }

    private static void integer(JsonElement element) {
        number(element);
        try { element.getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException e) { throw new SaveFormatException("Save counter must be an exact integer", e); }
    }

    private static void number(JsonElement element) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber() || !Double.isFinite(element.getAsDouble()))
            throw new SaveFormatException("Invalid numeric save value");
    }

    private static void strings(JsonObject object, String... names) {
        for (String name : names) if (!object.get(name).isJsonPrimitive() || !object.getAsJsonPrimitive(name).isString())
            throw new SaveFormatException("Invalid string save value");
    }

    private static void booleans(JsonObject object, String... names) {
        for (String name : names) if (!object.get(name).isJsonPrimitive() || !object.getAsJsonPrimitive(name).isBoolean())
            throw new SaveFormatException("Invalid boolean save value");
    }
}
