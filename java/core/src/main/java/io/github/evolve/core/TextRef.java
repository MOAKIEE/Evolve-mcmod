package io.github.evolve.core;

import java.util.List;

/** Localizable, presentation-neutral text. Keys are namespaced for Minecraft lang files. */
public record TextRef(String key, List<String> arguments) {
    public TextRef {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Missing text key");
        arguments = List.copyOf(arguments);
    }

    public static TextRef of(String key, Object... arguments) {
        return new TextRef("evolve." + key,
                java.util.Arrays.stream(arguments).map(String::valueOf).toList());
    }
}
