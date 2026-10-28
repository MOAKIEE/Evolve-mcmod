package io.github.evolve.core;

/** Replaceable serialization boundary; a Minecraft adapter may map GameSave directly to NBT. */
public interface SaveCodec<T> {
    T encode(GameSave save);
    GameSave decode(T encoded);
}
