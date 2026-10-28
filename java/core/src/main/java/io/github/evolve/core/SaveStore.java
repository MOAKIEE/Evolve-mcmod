package io.github.evolve.core;

import java.io.IOException;
import java.util.Optional;

/** Each store is already bound to one authorized session by its host. */
public interface SaveStore {
    Optional<GameSave> load() throws IOException;
    void save(GameSave save) throws IOException;
}
