package io.github.originalrecipe1.unfurlit.buildlogic;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PythonZipAppTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void packagesSourcesDataAndLicensesInSortedOrderWithFixedDosTimes() throws Exception {
        Path sources = sources("a", "pkg/mod.py", "pkg/__init__.py", "licenses/pkg/LICENSE", "certifi/cacert.pem");
        Path output = assemble("out.zip", sources);
        try (ZipFile zip = ZipFile.builder().setPath(output).get()) {
            List<String> names = new ArrayList<>();
            for (ZipArchiveEntry entry : Collections.list(zip.getEntries())) {
                names.add(entry.getName());
                assertEquals(LocalDateTime.of(1980, 1, 2, 0, 0), entry.getTimeLocal());
            }
            assertEquals(List.of("__main__.py", "certifi/cacert.pem", "licenses/pkg/LICENSE", "pkg/__init__.py", "pkg/mod.py"), names);
            try (var main = zip.getInputStream(zip.getEntry("__main__.py"))) {
                assertEquals("print('hi')\n", new String(main.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    @Test public void outputIgnoresSourceCreationOrderAndTimestamps() throws Exception {
        Path a = sources("a", "pkg/__init__.py", "pkg/z.py");
        Path b = sources("b", "pkg/z.py", "pkg/__init__.py");
        Files.setLastModifiedTime(b.resolve("pkg/z.py"), FileTime.fromMillis(0));
        assertArrayEquals(Files.readAllBytes(assemble("first.zip", a)), Files.readAllBytes(assemble("second.zip", b)));
    }

    @Test public void outputIsReproducibleAcrossDefaultTimeZones() throws Exception {
        Path input = sources("input", "pkg/__init__.py");
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            Path utc = assemble("utc.zip", input);
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            Path kiritimati = assemble("kiritimati.zip", input);
            assertArrayEquals(Files.readAllBytes(utc), Files.readAllBytes(kiritimati));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test public void rejectsEntryPointOverridesAndSourceSymlinks() throws Exception {
        Path override = sources("override", "__main__.py");
        assertThrows(IOException.class, () -> assemble("override.zip", override));
        Path linked = sources("linked", "pkg/__init__.py");
        Files.createSymbolicLink(linked.resolve("escape.py"), override.resolve("__main__.py"));
        assertThrows(IOException.class, () -> assemble("linked.zip", linked));
    }

    private Path assemble(String name, Path sources) throws IOException {
        Path entryPoint = folder.getRoot().toPath().resolve("entry.py");
        Files.writeString(entryPoint, "print('hi')\n");
        Path output = folder.getRoot().toPath().resolve(name);
        PythonZipApp.assemble(sources, entryPoint, output);
        return output;
    }

    private Path sources(String name, String... paths) throws IOException {
        Path directory = folder.newFolder(name).toPath();
        for (String path : paths) {
            Path file = directory.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "# " + path + "\n");
        }
        return directory;
    }
}
