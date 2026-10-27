package oss.relay.timing.timingpoint.io.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public final class FileRecordStoreBackup {
    private static final String CLEAR_MARKER_SUFFIX = ".clear-on-restart";
    private static final String BACKUP_PREFIX = ".backup-";
    private static final DateTimeFormatter BACKUP_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                    .withZone(ZoneOffset.UTC);

    private FileRecordStoreBackup() {
    }

    public static Path backup(Path file) throws IOException {
        requireFile(file);
        Path backup = backupPath(file, newToken());
        copySnapshot(file, backup);
        return backup;
    }

    public static Path requestBackupAndClearOnRestart(Path file) throws IOException {
        requireFile(file);
        ensureParent(file);

        Path marker = marker(file);
        if (Files.exists(marker)) {
            return backupPath(file, readToken(marker));
        }

        String token = newToken();
        byte[] bytes = (token + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                marker,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (FileAlreadyExistsException ex) {
            return backupPath(file, readToken(marker));
        }
        return backupPath(file, token);
    }

    public static Path applyPendingBackupAndClear(Path file) throws IOException {
        requireFile(file);
        Path marker = marker(file);
        if (!Files.exists(marker)) {
            return null;
        }

        String token = readToken(marker);
        Path backup = backupPath(file, token);
        copySnapshot(file, backup);

        Files.deleteIfExists(file);
        Files.delete(marker);
        return backup;
    }

    public static boolean hasPendingBackupAndClear(Path file) {
        requireFile(file);
        return Files.exists(marker(file));
    }

    private static void copySnapshot(
            Path source,
            Path backup) throws IOException {
        ensureParent(source);

        if (Files.exists(source)) {
            Files.copy(
                    source,
                    backup,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
        } else if (!Files.exists(backup)) {
            Files.createFile(backup);
        }

        try (FileChannel channel = FileChannel.open(
                backup,
                StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static Path marker(Path file) {
        return file.resolveSibling(
                file.getFileName().toString()
                        + CLEAR_MARKER_SUFFIX);
    }

    private static Path backupPath(
            Path file,
            String token) {
        validateToken(token);
        return file.resolveSibling(
                file.getFileName().toString()
                        + BACKUP_PREFIX
                        + token);
    }

    private static String readToken(Path marker)
            throws IOException {
        String token =
                new String(
                        Files.readAllBytes(marker),
                        StandardCharsets.UTF_8)
                        .trim();
        validateToken(token);
        return token;
    }

    private static String newToken() {
        return BACKUP_TIME.format(Instant.now())
                + "-"
                + UUID.randomUUID()
                        .toString()
                        .substring(0, 8);
    }

    private static void validateToken(String token) {
        if (token == null
                || !token.matches(
                        "[0-9]{8}-[0-9]{6}-[0-9]{3}-[0-9a-f]{8}")) {
            throw new IllegalArgumentException(
                    "Invalid backup token");
        }
    }

    private static void ensureParent(Path file)
            throws IOException {
        Path parent =
                file.toAbsolutePath()
                        .normalize()
                        .getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static void requireFile(Path file) {
        if (file == null || file.getFileName() == null) {
            throw new IllegalArgumentException(
                    "file must have a filename");
        }
    }
}
