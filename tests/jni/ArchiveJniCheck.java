// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// Drives the archive JNI glue (app/src/main/cpp/archive_jni.cpp, built for the
// host) from Java, for the ArchiveJni gtest, which runs it as a source file:
//   java -Xcheck:jni --add-opens java.base/java.io=ALL-UNNAMED ArchiveJniCheck.java LIB DIR
// Prints PASS or FAIL per check and "ALL PASS" at the end; the gtest also
// fails on any CheckJNI warning in the output.
package com.lxlauncher.files;

import java.io.FileDescriptor;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

public class ArchiveJniCheck {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }

    static int fd(FileDescriptor d) throws Exception {
        Field f = FileDescriptor.class.getDeclaredField("fd");
        f.setAccessible(true);
        return f.getInt(d);
    }

    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        String dir = args[1];
        NativeArchive n = new NativeArchive();
        byte[] content = new byte[3 * 1024 * 1024 + 5];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i * 31 + (i >> 9));
        Files.write(Paths.get(dir, "src.bin"), content);
        String emoji = "dir/😀 café.txt";
        long[] noLimits = new long[5];

        for (int format = 0; format <= 2; format++) {
            String archive = dir + "/out" + format;
            try (RandomAccessFile out = new RandomAccessFile(archive, "rw")) {
                out.setLength(0);
                long w = n.openWriter(fd(out.getFD()), format, 6);
                check(w != 0, "openWriter " + format);
                check(n.addDirectory(w, "dir", 1700000000000L, 0750) == 0, "addDirectory");
                long[] calls = {0, 0};
                try (RandomAccessFile in = new RandomAccessFile(dir + "/src.bin", "r")) {
                    NativeArchive.Progress p = b -> { calls[0]++; calls[1] = b; return true; };
                    check(n.addFile(w, emoji, fd(in.getFD()), content.length, 1700000000000L, 0644, p) == 0,
                          "addFile emoji " + n.lastError(w));
                }
                check(calls[0] >= 3 && calls[1] == content.length, "writer progress calls=" + calls[0]);
                // An object without onBytes(J)Z is what R8 leaves when the keep rule is missing.
                try (RandomAccessFile in = new RandomAccessFile(dir + "/src.bin", "r")) {
                    int s = n.addFile(w, "x.bin", fd(in.getFD()), content.length, 0, 0, new Object());
                    check(s == -1 && n.lastError(w).contains("onBytes"), "addFile without onBytes: " + s + " " + n.lastError(w));
                }
                try (RandomAccessFile in = new RandomAccessFile(dir + "/src.bin", "r")) {
                    check(n.addFile(w, "lone\uD800.txt", fd(in.getFD()), content.length, 0, 0, null) == 0,
                          "the writer still works; lone surrogate name");
                }
                check(n.finish(w) == 0, "finish " + format + " " + n.lastError(0));
            }
            try (RandomAccessFile f = new RandomAccessFile(archive, "r")) {
                check(n.detect(fd(f.getFD())) == format, "detect " + format);
                check(f.getFilePointer() == 0, "detect leaves the offset");
                for (int pass = 1; pass <= 2; pass++) {
                    long r = n.openReader(fd(f.getFD()), -1, noLimits);
                    check(r != 0, "openReader pass " + pass);
                    long[] info = new long[7];
                    String[] names = new String[3];
                    check(n.next(r, info, names) == 0 && info[0] == 1 && "dir".equals(names[1]), "dir entry " + names[1]);
                    check(info[4] == 0750, "dir mode " + Long.toOctalString(info[4]));
                    check(n.next(r, info, names) == 0 && emoji.equals(names[1]) && emoji.equals(names[0]),
                          "emoji name " + names[1]);
                    check(info[1] == content.length && info[3] == 1700000000000L && info[5] == 0 && info[6] == 0,
                          "info fields");
                    check(names[2] == null, "no link");
                    try (RandomAccessFile x = new RandomAccessFile(dir + "/x.bin", "rw")) {
                        x.setLength(0);
                        check(n.extract(r, fd(x.getFD()), new Object()) == -1 && n.lastError(r).contains("onBytes"),
                              "extract without onBytes is an error, not a cancel: " + n.lastError(r));
                        NativeArchive.Progress boom = b -> { throw new IllegalStateException("boom"); };
                        int s = n.extract(r, fd(x.getFD()), boom);
                        check(s == -6, "exception in progress cancels: " + s);
                    }
                    check(n.next(r, info, names) == 0 && "lone�.txt".equals(names[1]),
                          "lone surrogate became U+FFFD: " + names[1]);
                    try (RandomAccessFile x = new RandomAccessFile(dir + "/x.bin", "rw")) {
                        x.setLength(0);
                        long[] last = {0};
                        NativeArchive.Progress p = b -> { last[0] = b; return true; };
                        int s = n.extract(r, fd(x.getFD()), p);
                        check(s == 0 && last[0] == content.length, "extract " + s + " " + last[0]);
                        check(Arrays.equals(Files.readAllBytes(Paths.get(dir, "x.bin")), content), "extracted bytes match");
                    }
                    check(n.next(r, info, names) == 1, "END");
                    check(n.next(r, new long[2], names) == -1 && n.lastError(r).contains("too short"), "short info array");
                    n.closeReader(r);
                    // Readers use pread, so the caller's offset stays and the second pass sees everything.
                    check(f.getFilePointer() == 0, "reader leaves the caller's offset, pass " + pass);
                }
            }
        }

        // A names array that cannot hold strings: the store throws, and no JNI
        // call may follow while that exception is pending.
        try (RandomAccessFile f = new RandomAccessFile(dir + "/out0", "r")) {
            long r = n.openReader(fd(f.getFD()), 0, noLimits);
            int s = n.next(r, new long[7], new Integer[3]);
            check(s == -1 && n.lastError(r).contains("result arrays"), "names of the wrong type: " + s + " " + n.lastError(r));
            check(n.next(r, new long[7], new String[3]) == 0, "next goes on afterwards");
            n.closeReader(r);
        }
        check(n.checkPath("a/b", false, new Integer[1]) == 0, "checkPath with a wrong output array keeps its verdict");

        check(n.openReader(-1, 0, noLimits) == 0, "bad fd fails");
        check(n.lastError(0).length() > 0, "lastError(0) = " + n.lastError(0));
        check(n.openReader(0, 0, new long[] {1}) == 0 && n.lastError(0).contains("limits"), "short limits");
        try (RandomAccessFile f = new RandomAccessFile(dir + "/src.bin", "r")) {
            check(n.openReader(fd(f.getFD()), 0, noLimits) == 0, "not a zip: " + n.lastError(0));
        }
        String[] norm = new String[1];
        check(n.checkPath("a\\b\\..\\c", false, norm) == 3 && norm[0] == null, "dotdot");
        check(n.checkPath("./a//b/", false, norm) == 0 && "a/b".equals(norm[0]), "normalized " + norm[0]);
        check(n.checkPath("x/😀", true, norm) == 0 && "x/😀".equals(norm[0]), "emoji path");
        check(n.checkName("a\u0000b", false) == 5, "NUL is a control character, not modified UTF-8");
        check(n.checkName("evil\u202Etxt.apk", false) == 5, "bidi override is a control character");
        check(n.checkName("what?", true) == 9 && n.checkName("what?", false) == 0, "fat names");
        check("backup (2).tar.gz".equals(n.numberedName("backup.tar.gz", 2)), "numberedName " + n.numberedName("backup.tar.gz", 2));
        check("😀 (3).txt".equals(n.numberedName("😀.txt", 3)), "numberedName emoji");
        n.closeReader(0);
        n.abort(0);
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }
}

// The JVM names and signatures of the Kotlin object (its external funs are
// instance methods). JNI binds by name only, so a few parameters are widened
// to Object here to pass what a broken release build or a bad caller would.
class NativeArchive {
    interface Progress {
        boolean onBytes(long bytes);
    }

    native int detect(int fd);
    native long openReader(int fd, int format, long[] limits);
    native int next(long handle, long[] info, Object[] names);
    native int extract(long handle, int outFd, Object progress);
    native void closeReader(long handle);
    native long openWriter(int fd, int format, int level);
    native int addDirectory(long handle, String path, long mtimeMs, int mode);
    native int addFile(long handle, String path, int inFd, long size, long mtimeMs, int mode, Object progress);
    native int finish(long handle);
    native void abort(long handle);
    native String lastError(long handle);
    native int checkPath(String raw, boolean fat, Object[] normalized);
    native int checkName(String name, boolean fat);
    native String numberedName(String name, int n);
}
