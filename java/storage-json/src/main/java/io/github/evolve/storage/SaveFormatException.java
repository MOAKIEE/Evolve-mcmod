package io.github.evolve.storage;

/** Invalid or incompatible save data. A caller must keep its current game when this is thrown. */
public final class SaveFormatException extends IllegalArgumentException {
    public SaveFormatException(String message) { super(message); }
    public SaveFormatException(String message, Throwable cause) { super(message, cause); }
}
