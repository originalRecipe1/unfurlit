package io.github.originalrecipe1.unfurlit.buildlogic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;

/**
 * Builds a Python zip application from pure-Python wheels and an entry point, e.g.
 * the bundled gallery-dl engine. The output is byte-for-byte reproducible: entries
 * are sorted and carry a fixed timestamp, and install-time wheel metadata is dropped.
 */
public final class PythonZipApp {
    private PythonZipApp() {}

    /** 1980-01-02T00:00:00Z: the earliest date every zip reader agrees on. */
    static final long ENTRY_TIME = 315_619_200_000L;

    public static void assemble(List<Path> wheels, Path entryPoint, Path output) throws IOException {
        // Sorted by path so the result does not depend on wheel or entry order.
        TreeMap<String, byte[]> files = new TreeMap<>();
        files.put("__main__.py", Files.readAllBytes(entryPoint));
        for (Path wheel : wheels) {
            try (ZipFile source = ZipFile.builder().setPath(wheel).get()) {
                for (ZipArchiveEntry entry : java.util.Collections.list(source.getEntries())) {
                    String name = entry.getName();
                    if (entry.isDirectory() || isInstallMetadata(name)) continue;
                    requireSafePath(name, wheel);
                    byte[] content;
                    try (InputStream input = source.getInputStream(entry)) {
                        content = input.readAllBytes();
                    }
                    if (files.putIfAbsent(name, content) != null) {
                        throw new IOException("Duplicate path " + name + " in " + wheel.getFileName());
                    }
                }
            }
        }
        try (ZipArchiveOutputStream destination = new ZipArchiveOutputStream(output)) {
            for (var file : files.entrySet()) {
                ZipArchiveEntry entry = new ZipArchiveEntry(file.getKey());
                entry.setTime(ENTRY_TIME);
                entry.setMethod(ZipArchiveEntry.DEFLATED);
                destination.putArchiveEntry(entry);
                destination.write(file.getValue());
                destination.closeArchiveEntry();
            }
        }
    }

    /** Files an installer writes or consumes; they are not needed at runtime. */
    static boolean isInstallMetadata(String name) {
        List<String> parts = new ArrayList<>(List.of(name.split("/")));
        if (parts.isEmpty()) return false;
        String top = parts.get(0);
        if (top.endsWith(".data")) return true; // scripts and headers for installation
        if (!top.endsWith(".dist-info") || parts.size() != 2) return false;
        return switch (parts.get(1)) {
            case "RECORD", "INSTALLER", "REQUESTED", "direct_url.json" -> true;
            default -> false;
        };
    }

    private static void requireSafePath(String name, Path wheel) throws IOException {
        boolean unsafe = name.isEmpty() || name.startsWith("/") || name.contains("\\") ||
            List.of(name.split("/")).contains("..") || name.equals("__main__.py");
        if (unsafe) throw new IOException("Unsafe path " + name + " in " + wheel.getFileName());
    }
}
