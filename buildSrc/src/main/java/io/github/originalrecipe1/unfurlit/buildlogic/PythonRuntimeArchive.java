package io.github.originalrecipe1.unfurlit.buildlogic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.zip.X000A_NTFS;
import org.apache.commons.compress.archivers.zip.X5455_ExtendedTimestamp;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipLong;

/** Zips the stdlib and removes unused tooling; retained native payloads keep their metadata. */
public final class PythonRuntimeArchive {
    private PythonRuntimeArchive() {}

    private static final String STDLIB = "usr/lib/python3.12/";
    public static final String STDLIB_ZIP = "usr/lib/python312.zip";
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(1980, 1, 2, 0, 0);

    // Deliberately exact: a Python version/layout change must be reviewed.
    // A trailing slash removes one audited package, including its non-Python data.
    // See docs/python-runtime-storage.md for import and native dependency evidence.
    public static final Set<String> REMOVED_PATHS = Set.of(
        "usr/lib/quickjs/libquickjs.a",
        "usr/lib/python3.12/lib-dynload/_testbuffer.cpython-312.so",
        "usr/lib/python3.12/lib-dynload/_testcapi.cpython-312.so",
        "usr/lib/python3.12/lib-dynload/_testsinglephase.cpython-312.so",
        "usr/lib/python3.12/lib-dynload/_testimportmultiple.cpython-312.so",
        "usr/lib/python3.12/lib-dynload/_testclinic.cpython-312.so",
        "usr/lib/python3.12/lib-dynload/_testinternalcapi.cpython-312.so",
        "usr/lib/python3.12/lib-dynload/_testmultiphase.cpython-312.so",
        "usr/lib/python3.12/curses/",
        "usr/lib/python3.12/lib2to3/",
        "usr/lib/python3.12/venv/",
        "usr/lib/python3.12/xmlrpc/",
        "usr/lib/python3.12/lib-dynload/_curses.cpython-312.so",
        "usr/lib/python3.12/lib-dynload/_curses_panel.cpython-312.so",
        "usr/lib/python3.12/lib-dynload/readline.cpython-312.so",
        "usr/lib/libcurses.so",
        "usr/lib/libcurses.so.6",
        "usr/lib/libcurses.so.6.5",
        "usr/lib/libform.so",
        "usr/lib/libform.so.6",
        "usr/lib/libform.so.6.5",
        "usr/lib/libformw.so",
        "usr/lib/libformw.so.6",
        "usr/lib/libformw.so.6.5",
        "usr/lib/libhistory.so",
        "usr/lib/libhistory.so.8",
        "usr/lib/libhistory.so.8.3",
        "usr/lib/libmenu.so",
        "usr/lib/libmenu.so.6",
        "usr/lib/libmenu.so.6.5",
        "usr/lib/libmenuw.so",
        "usr/lib/libmenuw.so.6",
        "usr/lib/libmenuw.so.6.5",
        "usr/lib/libncurses.so",
        "usr/lib/libncurses.so.6",
        "usr/lib/libncurses.so.6.5",
        "usr/lib/libncursesw.so",
        "usr/lib/libncursesw.so.6",
        "usr/lib/libncursesw.so.6.5",
        "usr/lib/libpanel.so",
        "usr/lib/libpanel.so.6",
        "usr/lib/libpanel.so.6.5",
        "usr/lib/libpanelw.so",
        "usr/lib/libpanelw.so.6",
        "usr/lib/libpanelw.so.6.5",
        "usr/lib/libreadline.so",
        "usr/lib/libreadline.so.8",
        "usr/lib/libreadline.so.8.3",
        "usr/lib/libtermcap.so",
        "usr/lib/libtermcap.so.6",
        "usr/lib/libtermcap.so.6.5",
        "usr/lib/libtic.so",
        "usr/lib/libtic.so.6",
        "usr/lib/libtic.so.6.5",
        "usr/lib/libtinfo.so",
        "usr/lib/libtinfo.so.6",
        "usr/lib/libtinfo.so.6.5"
    );

    public static void trim(Path input, Path output) throws IOException {
        Set<String> missing = new HashSet<>(REMOVED_PATHS);
        List<TimestampPatch> timestamps = new ArrayList<>();
        long centralDirectoryOffset;
        try (ZipFile source = ZipFile.builder().setPath(input).get();
             ZipArchiveOutputStream destination = new ZipArchiveOutputStream(output)) {
            TreeMap<String, byte[]> stdlib = new TreeMap<>();
            List<ZipArchiveEntry> retained = new ArrayList<>();
            Set<String> retainedDirectories = new HashSet<>();
            var entries = source.getEntriesInPhysicalOrder();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                String name = entry.getName();
                String removal = removalFor(name);
                if (removal != null) {
                    missing.remove(removal);
                } else if (!entry.isDirectory() && isStdlibFile(name)) {
                    if (entry.isUnixSymlink() || name.endsWith(".so") || name.contains(".so.")) {
                        throw new IOException("Unexpected stdlib native file or symlink: " + name);
                    }
                    try (InputStream contents = source.getInputStream(entry)) {
                        stdlib.put(name.substring(STDLIB.length()), contents.readAllBytes());
                    }
                } else {
                    if (name.equals(STDLIB_ZIP)) throw new IOException("Upstream already supplies " + STDLIB_ZIP);
                    retained.add(entry);
                    if (!entry.isDirectory()) {
                        for (int slash = name.lastIndexOf('/'); slash >= 0; slash = name.lastIndexOf('/', slash - 1)) {
                            retainedDirectories.add(name.substring(0, slash + 1));
                        }
                    }
                }
            }
            if (!missing.isEmpty() || !stdlib.keySet().containsAll(Set.of("os.py", "site.py", "encodings/__init__.py"))) {
                throw new IOException("Python runtime layout changed; review the trim list and stdlib. Missing: " + missing);
            }
            // yt-dlp starts Python without -B. site runs this before importing engines or
            // site-packages. gallery-dl uses -S and already sets PYTHONDONTWRITEBYTECODE.
            if (stdlib.putIfAbsent("sitecustomize.py", "import sys\nsys.dont_write_bytecode = True\n"
                    .getBytes(StandardCharsets.UTF_8)) != null) {
                throw new IOException("Upstream supplies sitecustomize.py; review its startup behavior");
            }
            for (ZipArchiveEntry entry : retained) {
                // Do not recreate empty package directories whose contents now live in the zip.
                if (entry.isDirectory() && entry.getName().startsWith(STDLIB)
                        && !retainedDirectories.contains(entry.getName())) continue;
                timestamps.add(new TimestampPatch(destination.getBytesWritten(), utcDosTime(entry)));
                copyRaw(source, entry, destination);
            }
            byte[] zippedStdlib = zipStdlib(stdlib);
            ZipArchiveEntry nested = deterministicEntry(STDLIB_ZIP);
            // The stdlib is compressed once, so initialization just copies this zip.
            nested.setMethod(ZipArchiveEntry.STORED);
            nested.setSize(zippedStdlib.length);
            CRC32 crc = new CRC32();
            crc.update(zippedStdlib);
            nested.setCrc(crc.getValue());
            timestamps.add(new TimestampPatch(destination.getBytesWritten(), null));
            destination.putArchiveEntry(nested);
            destination.write(zippedStdlib);
            destination.closeArchiveEntry();
            centralDirectoryOffset = destination.getBytesWritten();
        }
        normalizeDosTimes(output, centralDirectoryOffset, timestamps);
    }

    private static String removalFor(String name) {
        for (String path : REMOVED_PATHS) {
            if (name.equals(path) || path.endsWith("/") && name.startsWith(path)) return path;
        }
        return null;
    }

    private static boolean isStdlibFile(String name) {
        return name.startsWith(STDLIB) && !name.startsWith(STDLIB + "lib-dynload/")
            && !name.startsWith(STDLIB + "site-packages/");
    }

    private static ZipArchiveEntry deterministicEntry(String name) {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setTime(ENTRY_TIME.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        entry.setUnixMode(0100644);
        entry.setMethod(ZipArchiveEntry.DEFLATED);
        return entry;
    }

    private static byte[] zipStdlib(TreeMap<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(bytes)) {
            for (var file : files.entrySet()) {
                zip.putArchiveEntry(deterministicEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private record TimestampPatch(long localHeaderOffset, byte[] dosTime) {}

    private static byte[] utcDosTime(ZipArchiveEntry entry) {
        var extended = entry.getExtraField(X5455_ExtendedTimestamp.HEADER_ID);
        var ntfs = entry.getExtraField(X000A_NTFS.HEADER_ID);
        boolean absolute = extended instanceof X5455_ExtendedTimestamp unixTime && unixTime.isBit0_modifyTimePresent()
            || ntfs instanceof X000A_NTFS ntfsTime && ntfsTime.getModifyFileTime() != null;
        // DOS-only entries already round-trip their local fields unchanged.
        if (!absolute) return null;
        LocalDateTime time = LocalDateTime.ofInstant(entry.getLastModifiedTime().toInstant(), ZoneOffset.UTC);
        // Encode the fields directly: converting through a local zone could hit a DST gap.
        long dosTime = time.getYear() < 1980 ? 0x00210000L
            : ((long) (time.getYear() - 1980) << 25) | (time.getMonthValue() << 21)
                | (time.getDayOfMonth() << 16) | (time.getHour() << 11)
                | (time.getMinute() << 5) | (time.getSecond() >> 1);
        return ZipLong.getBytes(dosTime);
    }

    private static void normalizeDosTimes(Path output, long centralOffset,
                                          List<TimestampPatch> timestamps) throws IOException {
        // Commons Compress derives DOS fields from the default timezone even for raw copies.
        // setTime would also rewrite/remove extended timestamp extras. Change only the four
        // DOS bytes in each local/central header, preserving the existing UTC output exactly.
        try (RandomAccessFile archive = new RandomAccessFile(output.toFile(), "rw")) {
            for (TimestampPatch timestamp : timestamps) {
                requireHeader(archive, centralOffset, ZipLong.CFH_SIG.getValue());
                archive.seek(centralOffset + 28); // name, extra-field and comment lengths
                int nameLength = readUnsignedShortLE(archive);
                int extraLength = readUnsignedShortLE(archive);
                int commentLength = readUnsignedShortLE(archive);
                if (timestamp.dosTime() != null) {
                    requireHeader(archive, timestamp.localHeaderOffset(), ZipLong.LFH_SIG.getValue());
                    archive.seek(timestamp.localHeaderOffset() + 10);
                    archive.write(timestamp.dosTime());
                    archive.seek(centralOffset + 12);
                    archive.write(timestamp.dosTime());
                }
                centralOffset += 46L + nameLength + extraLength + commentLength;
            }
        }
    }

    private static void requireHeader(RandomAccessFile archive, long offset, long signature) throws IOException {
        archive.seek(offset);
        if (Integer.toUnsignedLong(Integer.reverseBytes(archive.readInt())) != signature) {
            throw new IOException("Unexpected ZIP header at offset " + offset);
        }
    }

    private static int readUnsignedShortLE(RandomAccessFile archive) throws IOException {
        return Short.toUnsignedInt(Short.reverseBytes(archive.readShort()));
    }

    static void copyRaw(ZipFile source, ZipArchiveEntry entry,
                        ZipArchiveOutputStream destination) throws IOException {
        try (InputStream compressed = source.getRawInputStream(entry)) {
            destination.addRawArchiveEntry(new ZipArchiveEntry(entry), compressed);
        }
    }
}
