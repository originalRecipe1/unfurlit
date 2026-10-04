package io.github.originalrecipe1.unfurlit.buildlogic;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Collections;
import java.util.TimeZone;
import org.apache.commons.compress.archivers.zip.X000A_NTFS;
import org.apache.commons.compress.archivers.zip.X5455_ExtendedTimestamp;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipShort;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PythonRuntimeArchiveTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void preservesRetainedBytesPermissionsSymlinksAndTimestamps() throws Exception {
        Path input = fixture(true);
        Path output = folder.getRoot().toPath().resolve("trimmed.zip");
        PythonRuntimeArchive.trim(input, output);
        try (ZipFile before = ZipFile.builder().setPath(input).get();
             ZipFile after = ZipFile.builder().setPath(output).get()) {
            assertEquals(3, Collections.list(after.getEntries()).size());
            for (String path : PythonRuntimeArchive.REMOVED_PATHS) assertNull(after.getEntry(path));
            for (String path : new String[]{"usr/lib/libpython.so", "usr/lib/libpython.so.1", "usr/lib/python3.12/_sysconfigdata__linux_.py"}) {
                ZipArchiveEntry a = before.getEntry(path);
                ZipArchiveEntry b = after.getEntry(path);
                assertEquals(a.getUnixMode(), b.getUnixMode());
                assertEquals(a.getTime(), b.getTime());
                assertEquals(a.getMethod(), b.getMethod());
                assertEquals(a.getCrc(), b.getCrc());
                for (var id : new ZipShort[]{
                    X5455_ExtendedTimestamp.HEADER_ID, X000A_NTFS.HEADER_ID
                }) {
                    assertArrayEquals(a.getExtraField(id).getLocalFileDataData(),
                        b.getExtraField(id).getLocalFileDataData());
                    assertArrayEquals(a.getExtraField(id).getCentralDirectoryData(),
                        b.getExtraField(id).getCentralDirectoryData());
                }
                try (var original = before.getRawInputStream(a); var kept = after.getRawInputStream(b)) {
                    assertArrayEquals(original.readAllBytes(), kept.readAllBytes());
                }
            }
            assertTrue(after.getEntry("usr/lib/libpython.so").isUnixSymlink());
            try (var target = after.getInputStream(after.getEntry("usr/lib/libpython.so"))) {
                assertEquals("libpython.so.1", new String(target.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    @Test public void outputIsReproducible() throws Exception {
        Path input = fixture(true);
        Path first = folder.getRoot().toPath().resolve("first.zip");
        Path second = folder.getRoot().toPath().resolve("second.zip");
        PythonRuntimeArchive.trim(input, first);
        PythonRuntimeArchive.trim(input, second);
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }

    @Test public void outputIsReproducibleAcrossDefaultTimeZones() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            Path input = fixture(true);
            Path utc = folder.getRoot().toPath().resolve("utc.zip");
            PythonRuntimeArchive.trim(input, utc);
            for (String zone : new String[]{"Europe/Berlin", "Pacific/Kiritimati"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                Path output = folder.newFile().toPath();
                PythonRuntimeArchive.trim(input, output);
                assertArrayEquals(zone, Files.readAllBytes(utc), Files.readAllBytes(output));
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test public void changedUpstreamLayoutRequiresReview() throws Exception {
        IOException error = assertThrows(IOException.class, () -> PythonRuntimeArchive.trim(
            fixture(false), folder.getRoot().toPath().resolve("trimmed.zip")));
        assertTrue(error.getMessage().contains("runtime layout changed"));
    }

    private Path fixture(boolean includeRemovals) throws IOException {
        Path input = folder.newFile().toPath();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(input)) {
            if (includeRemovals) {
                for (String name : PythonRuntimeArchive.REMOVED_PATHS) {
                    entry(zip, name, 0100644, "build/test bytes");
                }
            }
            entry(zip, "usr/lib/libpython.so", 0120777, "libpython.so.1");
            entry(zip, "usr/lib/libpython.so.1", 0100755, "runtime bytes");
            entry(zip, "usr/lib/python3.12/_sysconfigdata__linux_.py", 0100644, "configuration bytes");
        }
        return input;
    }

    private void entry(ZipArchiveOutputStream zip, String name, int mode, String data) throws IOException {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        // Include absolute extended timestamps, as the upstream Python ZIP does.
        // These UTC fields fall inside Berlin's DST gap, so a local-time round trip is unsafe.
        entry.setLastModifiedTime(FileTime.from(Instant.parse("2025-03-30T02:30:00Z")));
        entry.setUnixMode(mode);
        zip.putArchiveEntry(entry);
        zip.write(data.getBytes(StandardCharsets.UTF_8));
        zip.closeArchiveEntry();
    }
}
