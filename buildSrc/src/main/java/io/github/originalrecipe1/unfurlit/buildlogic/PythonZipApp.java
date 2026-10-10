package io.github.originalrecipe1.unfurlit.buildlogic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.TreeMap;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

/**
 * Builds a Python zip application from staged sources and an entry point, e.g.
 * the bundled gallery-dl engine. The output is byte-for-byte reproducible: entries
 * are sorted and carry a fixed timestamp, and source timestamps and permissions are ignored.
 */
public final class PythonZipApp {
    private PythonZipApp() {}

    /** 1980-01-02 00:00 in local DOS time; ZIP timestamps have no timezone. */
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(1980, 1, 2, 0, 0);

    public static void assemble(Path sources, Path entryPoint, Path output) throws IOException {
        TreeMap<String, byte[]> files = new TreeMap<>();
        files.put("__main__.py", Files.readAllBytes(entryPoint));
        try (var paths = Files.walk(sources)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Unsafe source symlink: " + path);
                if (!Files.isRegularFile(path)) continue;
                String name = sources.relativize(path).toString().replace('\\', '/');
                requireSafePath(name);
                files.put(name, Files.readAllBytes(path));
            }
        }
        // setTime converts an instant to DOS fields in the JVM's default zone.
        long entryTime = ENTRY_TIME.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        try (ZipArchiveOutputStream destination = new ZipArchiveOutputStream(output)) {
            for (var file : files.entrySet()) {
                ZipArchiveEntry entry = new ZipArchiveEntry(file.getKey());
                entry.setTime(entryTime);
                entry.setMethod(ZipArchiveEntry.DEFLATED);
                destination.putArchiveEntry(entry);
                destination.write(file.getValue());
                destination.closeArchiveEntry();
            }
        }
    }

    private static void requireSafePath(String name) throws IOException {
        boolean unsafe = name.isEmpty() || name.startsWith("/") || name.contains("\\") ||
            List.of(name.split("/")).contains("..") || name.equals("__main__.py");
        if (unsafe) throw new IOException("Unsafe path " + name);
    }
}
