package io.github.originalrecipe1.unfurlit.buildlogic;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.TimeZone;
import java.util.zip.ZipInputStream;
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
            assertEquals(5, Collections.list(after.getEntries()).size());
            for (String path : PythonRuntimeArchive.REMOVED_PATHS) assertNull(after.getEntry(path));
            for (String path : new String[]{"usr/lib/libpython.so", "usr/lib/libpython.so.1",
                "usr/lib/python3.12/site-packages/retained/__init__.py",
                "usr/lib/python3.12/lib-dynload/_sqlite3.cpython-312.so"}) {
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

    @Test public void zippedStdlibPreservesSourcesAndPackageDataWithoutNativeModules() throws Exception {
        Path input = fixture(true);
        Path output = folder.newFile().toPath();
        PythonRuntimeArchive.trim(input, output);
        try (ZipFile archive = ZipFile.builder().setPath(output).get()) {
            assertNull(archive.getEntry("usr/lib/python3.12/os.py"));
            assertNull(archive.getEntry("usr/lib/python3.12/xmlrpc/unused.py"));
            var nested = archive.getEntry(PythonRuntimeArchive.STDLIB_ZIP);
            assertEquals(ZipArchiveEntry.STORED, nested.getMethod());
            var contents = new TreeMap<String, String>();
            var order = new ArrayList<String>();
            try (var zip = new ZipInputStream(archive.getInputStream(nested))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    order.add(entry.getName());
                    contents.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            assertEquals(new ArrayList<>(contents.keySet()), order);
            assertEquals("configuration bytes", contents.get("_sysconfigdata__linux_.py"));
            assertEquals("encoding bytes", contents.get("encodings/__init__.py"));
            assertEquals("package data", contents.get("email/architecture.rst"));
            assertEquals("keep adjacent names", contents.get("xmlrpclib.py"));
            assertEquals("import sys\nsys.dont_write_bytecode = True\n", contents.get("sitecustomize.py"));
            assertFalse(contents.keySet().stream().anyMatch(name -> name.startsWith("site-packages/")
                || name.startsWith("lib-dynload/") || name.startsWith("xmlrpc/")));
        }
    }

    @Test public void upstreamStartupCustomizationRequiresReview() throws Exception {
        IOException error = assertThrows(IOException.class, () -> PythonRuntimeArchive.trim(
            fixture(true, "sitecustomize.py"), folder.newFile().toPath()));
        assertTrue(error.getMessage().contains("sitecustomize.py"));
    }

    @Test public void unexpectedNativeModuleInsideAStdlibPackageRequiresReview() throws Exception {
        IOException error = assertThrows(IOException.class, () -> PythonRuntimeArchive.trim(
            fixture(true, "somepackage/native.so"), folder.newFile().toPath()));
        assertTrue(error.getMessage().contains("Unexpected stdlib native file"));
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
            assertUtcDosTimestamp(utc);
            for (String zone : new String[]{"Europe/Berlin", "Pacific/Kiritimati"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                Path output = folder.newFile().toPath();
                PythonRuntimeArchive.trim(input, output);
                assertArrayEquals(zone, Files.readAllBytes(utc), Files.readAllBytes(output));
                assertUtcDosTimestamp(output);
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private static void assertUtcDosTimestamp(Path output) throws IOException {
        // Read the actual DOS fields: ZIP readers may prefer the absolute timestamp extras.
        ByteBuffer zip = ByteBuffer.wrap(Files.readAllBytes(output)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x04034b50, zip.getInt(0));
        // This small fixture has no archive comment or ZIP64 end records.
        int endOffset = zip.limit() - 22;
        assertEquals(0x06054b50, zip.getInt(endOffset));
        int centralOffset = zip.getInt(endOffset + 16);
        assertEquals(0x02014b50, zip.getInt(centralOffset));
        LocalDateTime expected = LocalDateTime.of(2025, 3, 30, 2, 30, 0);
        assertEquals("local header DOS timestamp", expected, readDosTimestamp(zip, 10));
        assertEquals("central header DOS timestamp", expected, readDosTimestamp(zip, centralOffset + 12));
    }

    private static LocalDateTime readDosTimestamp(ByteBuffer zip, int offset) {
        int time = Short.toUnsignedInt(zip.getShort(offset));
        int date = Short.toUnsignedInt(zip.getShort(offset + 2));
        return LocalDateTime.of(1980 + (date >> 9), (date >> 5) & 15, date & 31,
            time >> 11, (time >> 5) & 63, (time & 31) * 2);
    }

    @Test public void changedUpstreamLayoutRequiresReview() throws Exception {
        IOException error = assertThrows(IOException.class, () -> PythonRuntimeArchive.trim(
            fixture(false), folder.getRoot().toPath().resolve("trimmed.zip")));
        assertTrue(error.getMessage().contains("runtime layout changed"));
    }

    private Path fixture(boolean includeRemovals, String... extraFiles) throws IOException {
        Path input = folder.newFile().toPath();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(input)) {
            if (includeRemovals) {
                for (String name : PythonRuntimeArchive.REMOVED_PATHS) {
                    entry(zip, name.endsWith("/") ? name + "unused.py" : name, 0100644, "build/test bytes");
                }
            }
            entry(zip, "usr/lib/libpython.so", 0120777, "libpython.so.1");
            entry(zip, "usr/lib/libpython.so.1", 0100755, "runtime bytes");
            entry(zip, "usr/lib/python3.12/site-packages/retained/__init__.py", 0100644, "package bytes");
            entry(zip, "usr/lib/python3.12/lib-dynload/_sqlite3.cpython-312.so", 0100755, "native module bytes");
            entry(zip, "usr/lib/python3.12/_sysconfigdata__linux_.py", 0100644, "configuration bytes");
            entry(zip, "usr/lib/python3.12/os.py", 0100644, "stdlib bytes");
            entry(zip, "usr/lib/python3.12/site.py", 0100644, "site bytes");
            entry(zip, "usr/lib/python3.12/encodings/__init__.py", 0100644, "encoding bytes");
            entry(zip, "usr/lib/python3.12/email/architecture.rst", 0100644, "package data");
            entry(zip, "usr/lib/python3.12/xmlrpclib.py", 0100644, "keep adjacent names");
            for (String name : extraFiles) entry(zip, "usr/lib/python3.12/" + name, 0100644, "upstream change");
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
