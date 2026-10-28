package io.github.evolve.storage;

import io.github.evolve.core.GameSave;
import io.github.evolve.core.SaveCodec;
import io.github.evolve.core.SaveStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

/** One store per session. Saves use a temporary sibling, fsync, replacement and a previous-save backup. */
public final class AtomicFileSaveStore implements SaveStore {
    private final Path path;
    private final SaveCodec<String> codec;

    public AtomicFileSaveStore(Path path, SaveCodec<String> codec) {
        this.path = path.toAbsolutePath().normalize();
        if (this.path.getFileName() == null) throw new IllegalArgumentException("Save must be a file");
        this.codec = Objects.requireNonNull(codec);
    }

    public Path path() { return path; }
    public Path backupPath() { return path.resolveSibling(path.getFileName() + ".bak"); }

    @Override
    public synchronized Optional<GameSave> load() throws IOException { return read(path); }

    public synchronized Optional<GameSave> loadBackup() throws IOException { return read(backupPath()); }

    private Optional<GameSave> read(Path file) throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        if (Files.size(file) > JsonSaveCodec.MAX_BYTES) throw new IOException("Save is too large: " + file.getFileName());
        try { return Optional.of(codec.decode(Files.readString(file, StandardCharsets.UTF_8))); }
        catch (IllegalArgumentException e) { throw new IOException("Cannot read Evolve save: " + file.getFileName(), e); }
    }

    @Override
    public synchronized void save(GameSave save) throws IOException {
        byte[] bytes = codec.encode(save).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > JsonSaveCodec.MAX_BYTES) throw new IOException("Save is too large");
        Files.createDirectories(path.getParent());
        // Never replace a damaged primary, or its good backup, merely by opening the application.
        boolean hasPrevious = read(path).isPresent();
        Path temporary = Files.createTempFile(path.getParent(), path.getFileName() + ".", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            if (hasPrevious) {
                Path backupTemporary = Files.createTempFile(path.getParent(), path.getFileName() + ".backup.", ".tmp");
                try {
                    Files.copy(path, backupTemporary, StandardCopyOption.REPLACE_EXISTING);
                    replace(backupTemporary, backupPath());
                } finally { Files.deleteIfExists(backupTemporary); }
            }
            replace(temporary, path);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void replace(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }
}
