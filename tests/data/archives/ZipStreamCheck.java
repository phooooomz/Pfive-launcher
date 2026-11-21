// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// Lists a ZIP with java.util.zip.ZipInputStream, the streaming reader many
// Android apps use, in the format of verify_interop.py. It cannot read a
// method-0 entry that has a data descriptor, and it sizes a descriptor from
// the real byte counts, so it catches writer output that only spec-complete
// readers accept. A damaged entry throws and the exit status is non-zero.
//
// Run as a source file (Java 11 or later): java ZipStreamCheck.java ARCHIVE
import java.io.BufferedInputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ZipStreamCheck {
    public static void main(String[] args) throws IOException {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        byte[] buf = new byte[1 << 16];
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(args[0])), StandardCharsets.UTF_8)) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                String name = e.getName();
                if (e.isDirectory()) {
                    out.println("d 0 00000000 " + name.substring(0, name.length() - 1));
                    continue;
                }
                CRC32 crc = new CRC32();
                long size = 0;
                for (int n = zip.read(buf); n > 0; n = zip.read(buf)) {
                    crc.update(buf, 0, n);
                    size += n;
                }
                out.printf("f %d %08x %s%n", size, crc.getValue(), name);
            }
        }
    }
}
