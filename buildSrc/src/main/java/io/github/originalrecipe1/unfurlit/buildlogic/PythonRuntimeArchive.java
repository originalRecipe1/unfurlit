package io.github.originalrecipe1.unfurlit.buildlogic;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.compress.archivers.zip.X000A_NTFS;
import org.apache.commons.compress.archivers.zip.X5455_ExtendedTimestamp;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipLong;

/** Removes build/test files while preserving compressed payloads and Unix metadata. */
public final class PythonRuntimeArchive {
    private PythonRuntimeArchive() {}

    // Deliberately exact: a Python version/layout change must be reviewed.
    public static final Set<String> REMOVED_PATHS = Set.of(
        "usr/lib/quickjs/libquickjs.a",
        "usr/lib/python3.12/lib-dynload/_testbuffer.cpython-312.so",
        "usr/lib/python3.12/lib-dynload/_testcapi.cpython-312.so",
        "usr/lib/python3.12/lib-dynload/_testsinglephase.cpython-312.so",
        "usr/lib/python3.12/lib-dynload/_testimportmultiple.cpython-312.so",
        "usr/lib/python3.12/lib-dynload/_testclinic.cpython-312.so",
        "usr/lib/python3.12/lib-dynload/_testinternalcapi.cpython-312.so",
        "usr/lib/python3.12/lib-dynload/_testmultiphase.cpython-312.so"
    );

    public static void trim(Path input, Path output) throws IOException {
        Set<String> missing = new HashSet<>(REMOVED_PATHS);
        List<TimestampPatch> timestamps = new ArrayList<>();
        long centralDirectoryOffset;
        try (ZipFile source = ZipFile.builder().setPath(input).get();
             ZipArchiveOutputStream destination = new ZipArchiveOutputStream(output)) {
            var entries = source.getEntriesInPhysicalOrder();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (REMOVED_PATHS.contains(entry.getName())) {
                    missing.remove(entry.getName());
                } else {
                    timestamps.add(new TimestampPatch(destination.getBytesWritten(), utcDosTime(entry)));
                    copyRaw(source, entry, destination);
                }
            }
            centralDirectoryOffset = destination.getBytesWritten();
        }
        if (!missing.isEmpty()) {
            throw new IOException("Python runtime layout changed; review the trim list. Missing: " + missing);
        }
        normalizeDosTimes(output, centralDirectoryOffset, timestamps);
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
