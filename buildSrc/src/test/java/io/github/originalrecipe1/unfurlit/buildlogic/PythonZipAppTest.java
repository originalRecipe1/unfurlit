package io.github.originalrecipe1.unfurlit.buildlogic;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PythonZipAppTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void combinesWheelsWithEntryPointAndDropsInstallMetadata() throws Exception {
        Path output = assemble("out.zip", wheel("a.whl",
            "pkg/__init__.py", "pkg/mod.py", "pkg-1.0.dist-info/METADATA",
            "pkg-1.0.dist-info/RECORD", "pkg-1.0.dist-info/INSTALLER",
            "pkg-1.0.dist-info/licenses/LICENSE", "pkg-1.0.data/scripts/pkg"),
            wheel("b.whl", "dep/__init__.py"));
        try (ZipFile zip = ZipFile.builder().setPath(output).get()) {
            List<String> names = new ArrayList<>();
            for (ZipArchiveEntry entry : Collections.list(zip.getEntries())) {
                names.add(entry.getName());
                assertEquals(PythonZipApp.ENTRY_TIME, entry.getTime());
            }
            assertEquals(List.of("__main__.py", "dep/__init__.py", "pkg-1.0.dist-info/METADATA",
                "pkg-1.0.dist-info/licenses/LICENSE", "pkg/__init__.py", "pkg/mod.py"), names);
            try (var main = zip.getInputStream(zip.getEntry("__main__.py"))) {
                assertEquals("print('hi')\n", new String(main.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    @Test public void outputIsReproducibleRegardlessOfWheelOrder() throws Exception {
        Path a = wheel("a.whl", "pkg/__init__.py", "pkg/z.py");
        Path b = wheel("b.whl", "dep/__init__.py");
        Path first = assemble("first.zip", a, b);
        Path second = assemble("second.zip", b, a);
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }

    @Test public void rejectsDuplicatePaths() throws Exception {
        assertAssemblyFails("Duplicate path", wheel("a.whl", "pkg/x.py"), wheel("b.whl", "pkg/x.py"));
    }

    @Test public void rejectsUnsafePathsAndEntryPointOverrides() throws Exception {
        assertAssemblyFails("Unsafe path", wheel("a.whl", "../escape.py"));
        assertAssemblyFails("Unsafe path", wheel("b.whl", "__main__.py"));
    }

    @Test public void recognizesInstallMetadata() {
        assertTrue(PythonZipApp.isInstallMetadata("x-1.dist-info/RECORD"));
        assertTrue(PythonZipApp.isInstallMetadata("x-1.data/scripts/x"));
        assertFalse(PythonZipApp.isInstallMetadata("x-1.dist-info/licenses/RECORD"));
        assertFalse(PythonZipApp.isInstallMetadata("x/RECORD"));
    }

    private void assertAssemblyFails(String message, Path... wheels) throws Exception {
        try {
            assemble("failed-" + wheels[0].getFileName() + ".zip", wheels);
            fail("Expected assembly to fail");
        } catch (IOException error) {
            assertTrue(error.getMessage(), error.getMessage().startsWith(message));
        }
    }

    private Path assemble(String name, Path... wheels) throws IOException {
        Path entryPoint = folder.getRoot().toPath().resolve("entry.py");
        Files.writeString(entryPoint, "print('hi')\n");
        Path output = folder.getRoot().toPath().resolve(name);
        PythonZipApp.assemble(List.of(wheels), entryPoint, output);
        return output;
    }

    private Path wheel(String name, String... paths) throws IOException {
        Path wheel = folder.getRoot().toPath().resolve(name);
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(wheel)) {
            for (String path : paths) {
                zip.putArchiveEntry(new ZipArchiveEntry(path));
                zip.write(("# " + path + "\n").getBytes(StandardCharsets.UTF_8));
                zip.closeArchiveEntry();
            }
        }
        return wheel;
    }
}
