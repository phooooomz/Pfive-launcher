// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include <gtest/gtest.h>

#include <string>

#include "lx/archive.hpp"

using namespace lx::archive;

namespace {

PathVerdict verdict(std::string_view raw, bool fat = false) { return checkPath(raw, fat, nullptr); }

std::string clean(std::string_view raw, bool fat = false) {
    std::string out = "unset";
    EXPECT_EQ(checkPath(raw, fat, &out), PathVerdict::Ok) << raw;
    return out;
}

}  // namespace

TEST(ArchivePath, NormalisesSeparatorsAndDots) {
    EXPECT_EQ(clean("a/b/c.txt"), "a/b/c.txt");
    EXPECT_EQ(clean("a\\b\\c.txt"), "a/b/c.txt");
    EXPECT_EQ(clean("a//b///c"), "a/b/c");
    EXPECT_EQ(clean("./a/./b/."), "a/b");
    EXPECT_EQ(clean("dir/"), "dir");
    EXPECT_EQ(clean("dir\\sub\\"), "dir/sub");
    EXPECT_EQ(clean("..a/b..c/...d"), "..a/b..c/...d");
    EXPECT_EQ(clean("\xE6\x97\xA5\xE6\x9C\xAC/\xF0\x9F\x98\x80.txt"), "\xE6\x97\xA5\xE6\x9C\xAC/\xF0\x9F\x98\x80.txt");
}

TEST(ArchivePath, EveryVerdict) {
    EXPECT_EQ(verdict(""), PathVerdict::Empty);
    EXPECT_EQ(verdict("."), PathVerdict::Empty);
    EXPECT_EQ(verdict("./"), PathVerdict::Empty);
    EXPECT_EQ(verdict("/etc/passwd"), PathVerdict::Absolute);
    EXPECT_EQ(verdict("\\\\server\\share\\x"), PathVerdict::Absolute);
    EXPECT_EQ(verdict("/"), PathVerdict::Absolute);
    EXPECT_EQ(verdict("../x"), PathVerdict::DotDot);
    EXPECT_EQ(verdict("a/../../x"), PathVerdict::DotDot);
    EXPECT_EQ(verdict("a/.."), PathVerdict::DotDot);
    EXPECT_EQ(verdict("a\\..\\..\\x"), PathVerdict::DotDot);
    EXPECT_EQ(verdict("C:/Windows"), PathVerdict::Drive);
    EXPECT_EQ(verdict("c:file"), PathVerdict::Drive);
    EXPECT_EQ(verdict("C:\\x"), PathVerdict::Drive);
    EXPECT_EQ(verdict("a\nb"), PathVerdict::Control);
    EXPECT_EQ(verdict(std::string("a\0b", 3)), PathVerdict::Control);
    EXPECT_EQ(verdict("a\x7f"), PathVerdict::Control);
    EXPECT_EQ(verdict("a\x1f"), PathVerdict::Control);
    EXPECT_EQ(verdict("\xff"), PathVerdict::BadUtf8);
    EXPECT_EQ(verdict(std::string(5000, 'a')), PathVerdict::ComponentTooLong);
    std::string longPath;
    for (int i = 0; i < 20; ++i) longPath += std::string(250, 'p') + "/";
    EXPECT_EQ(verdict(longPath), PathVerdict::TooLong);
    EXPECT_EQ(verdict(std::string(256, 'c')), PathVerdict::ComponentTooLong);
    EXPECT_EQ(verdict(std::string(255, 'c')), PathVerdict::Ok);
    EXPECT_EQ(verdict("a/b:c", true), PathVerdict::Reserved);
    EXPECT_EQ(verdict("a/b:c", false), PathVerdict::Ok);
}

TEST(ArchivePath, StrictUtf8) {
    EXPECT_EQ(verdict("\xC0\xAF"), PathVerdict::BadUtf8);              // overlong "/"
    EXPECT_EQ(verdict("\xE0\x80\xAF"), PathVerdict::BadUtf8);          // overlong, 3 bytes
    EXPECT_EQ(verdict("\xF0\x80\x80\xAF"), PathVerdict::BadUtf8);      // overlong, 4 bytes
    EXPECT_EQ(verdict("\xED\xA0\x80"), PathVerdict::BadUtf8);          // surrogate U+D800
    EXPECT_EQ(verdict("\xED\xBF\xBF"), PathVerdict::BadUtf8);          // surrogate U+DFFF
    EXPECT_EQ(verdict("\xF4\x90\x80\x80"), PathVerdict::BadUtf8);      // above U+10FFFF
    EXPECT_EQ(verdict("\xF5\x80\x80\x80"), PathVerdict::BadUtf8);
    EXPECT_EQ(verdict("\x80"), PathVerdict::BadUtf8);                  // lone continuation
    EXPECT_EQ(verdict("abc\xE2\x82"), PathVerdict::BadUtf8);           // truncated
    EXPECT_EQ(verdict("\xC3\xA9\xE2\x82\xAC\xF4\x8F\xBF\xBF"), PathVerdict::Ok);
    EXPECT_EQ(verdict("\xED\x9F\xBF"), PathVerdict::Ok);               // U+D7FF
}

TEST(ArchivePath, ControlsBeyondAscii) {
    // C1 controls, line and paragraph separators, bidi embeddings, overrides and isolates.
    for (const char* bad : {"a\xC2\x80", "a\xC2\x85" "b", "a\xC2\x9F", "invoice\xE2\x80\xAEtxt.apk", "x\xE2\x80\xAA",
                            "x\xE2\x80\xA8", "x\xE2\x80\xA9", "x\xE2\x81\xA6", "x\xE2\x81\xA9"}) {
        EXPECT_EQ(verdict(bad), PathVerdict::Control) << bad;
        EXPECT_EQ(checkName(bad, false), PathVerdict::Control) << bad;
    }
    // Neighbours that are ordinary text: NBSP, U+2027, U+202F, U+2070, and the RLM mark of RTL names.
    for (const char* ok : {"\xC2\xA0nbsp", "a\xE2\x80\xA7", "a\xE2\x80\xAF", "a\xE2\x81\xB0", "\xD7\xA9\xD7\x9D\xE2\x80\x8F.txt"})
        EXPECT_EQ(verdict(ok), PathVerdict::Ok) << ok;
}

TEST(ArchivePath, LinkTargets) {
    struct Case {
        const char* entry;
        std::string target;
        bool hard;
        PathVerdict want;
        const char* resolved;
    } cases[] = {
        {"a/link", "../x", false, PathVerdict::Ok, "x"},
        {"a/b/l", "../../c/d", false, PathVerdict::Ok, "c/d"},
        {"l", "sub/./t", false, PathVerdict::Ok, "sub/t"},
        {"a/l", "..", false, PathVerdict::Ok, ""},
        {"l", ".", false, PathVerdict::Ok, ""},
        {"a/l", "b\\c", false, PathVerdict::Ok, "a/b/c"},
        {"link", "../x", false, PathVerdict::DotDot, ""},
        {"a/l", "../..", false, PathVerdict::DotDot, ""},
        // ".." after a normal component could walk back out of a symlinked folder.
        {"l", "a/../b", false, PathVerdict::DotDot, ""},
        {"l", "/etc/passwd", false, PathVerdict::Absolute, ""},
        {"l", "\\x", false, PathVerdict::Absolute, ""},
        {"l", "C:/x", false, PathVerdict::Drive, ""},
        {"l", "", false, PathVerdict::Empty, ""},
        {"l", "a\x01", false, PathVerdict::Control, ""},
        {"l", "a\xE2\x80\xAE", false, PathVerdict::Control, ""},
        {"l", "\xff", false, PathVerdict::BadUtf8, ""},
        {"l", std::string(256, 'n'), false, PathVerdict::ComponentTooLong, ""},
        // Hard link targets name another entry from the archive root.
        {"x/h", "dir/f", true, PathVerdict::Ok, "dir/f"},
        {"h", "./a//b", true, PathVerdict::Ok, "a/b"},
        {"h", "../../outside", true, PathVerdict::DotDot, ""},
        {"h", "/abs", true, PathVerdict::Absolute, ""},
    };
    for (const Case& c : cases) {
        std::string resolved = "unset";
        EXPECT_EQ(checkLinkTarget(c.entry, c.target, c.hard, &resolved), c.want) << c.entry << " -> " << c.target;
        EXPECT_EQ(resolved, c.resolved) << c.entry << " -> " << c.target;
    }
}

TEST(ArchivePath, FatRules) {
    for (const char* bad : {"a\"b", "a*b", "x/a:b", "ab:c", "a<b", "a>b", "a?b", "a|b", "dir/name.", "dir/name ", "trail./x"})
        EXPECT_EQ(verdict(bad, true), PathVerdict::Reserved) << bad;
    for (const char* ok : {"a\"b", "a*b", "a<b", "a>b", "a?b", "a|b", "dir/name.", "dir/name "})
        EXPECT_EQ(verdict(ok, false), PathVerdict::Ok) << ok;
    EXPECT_EQ(verdict("C:x", true), PathVerdict::Drive);
    EXPECT_EQ(clean("normal name.txt", true), "normal name.txt");
}

TEST(ArchivePath, CheckName) {
    EXPECT_EQ(checkName("report.pdf", false), PathVerdict::Ok);
    EXPECT_EQ(checkName("", false), PathVerdict::Empty);
    EXPECT_EQ(checkName(".", false), PathVerdict::Reserved);
    EXPECT_EQ(checkName("..", false), PathVerdict::Reserved);
    EXPECT_EQ(checkName("a/b", false), PathVerdict::Reserved);
    EXPECT_EQ(checkName("a\\b", false), PathVerdict::Reserved);
    EXPECT_EQ(checkName("/", false), PathVerdict::Reserved);
    EXPECT_EQ(checkName("tab\there", false), PathVerdict::Control);
    EXPECT_EQ(checkName("\xC0\x80", false), PathVerdict::BadUtf8);
    EXPECT_EQ(checkName("C:", false), PathVerdict::Drive);
    EXPECT_EQ(checkName(std::string(256, 'x'), false), PathVerdict::ComponentTooLong);
    EXPECT_EQ(checkName(std::string(255, 'x'), false), PathVerdict::Ok);
    EXPECT_EQ(checkName("what?", true), PathVerdict::Reserved);
    EXPECT_EQ(checkName("what?", false), PathVerdict::Ok);
    EXPECT_EQ(checkName("ends.", true), PathVerdict::Reserved);
    EXPECT_EQ(checkName("ends ", true), PathVerdict::Reserved);
    EXPECT_EQ(checkName(".hidden", true), PathVerdict::Ok);
    EXPECT_EQ(checkName("\xE6\x96\x87\xE6\x9B\xB8.txt", true), PathVerdict::Ok);
}

TEST(ArchivePath, NumberedName) {
    EXPECT_EQ(numberedName("report.pdf", 2), "report (2).pdf");
    EXPECT_EQ(numberedName("report.pdf", 1), "report.pdf");
    EXPECT_EQ(numberedName("report.pdf", 0), "report.pdf");
    EXPECT_EQ(numberedName("report.pdf", -3), "report.pdf");
    EXPECT_EQ(numberedName("backup.tar.gz", 3), "backup (3).tar.gz");
    EXPECT_EQ(numberedName("backup.TAR.GZ", 2), "backup (2).TAR.GZ");
    EXPECT_EQ(numberedName("src.tar.bz2", 2), "src (2).tar.bz2");
    EXPECT_EQ(numberedName("src.tar.xz", 2), "src (2).tar.xz");
    EXPECT_EQ(numberedName("src.tar.zst", 10), "src (10).tar.zst");
    EXPECT_EQ(numberedName(".profile", 2), ".profile (2)");
    EXPECT_EQ(numberedName(".config.json", 2), ".config (2).json");
    EXPECT_EQ(numberedName("README", 2), "README (2)");
    EXPECT_EQ(numberedName("Mr. Smith notes", 2), "Mr. Smith notes (2)");
    EXPECT_EQ(numberedName("ends with dot.", 2), "ends with dot. (2)");
    EXPECT_EQ(numberedName("photo.verylongextensionname", 2), "photo.verylongextensionname (2)");
    EXPECT_EQ(numberedName("\xE6\x96\x87\xE6\x9B\xB8.txt", 2), "\xE6\x96\x87\xE6\x9B\xB8 (2).txt");
    // Long names are shortened on a character boundary to stay a valid name.
    std::string longName;
    for (int i = 0; i < 100; ++i) longName += "\xC3\xA9";  // 200 bytes of "é"
    longName += std::string(51, 'z') + ".txt";             // 255 bytes in all
    const std::string n = numberedName(longName, 12);
    EXPECT_LE(n.size(), kMaxComponentBytes);
    EXPECT_EQ(checkName(n, false), PathVerdict::Ok);
    EXPECT_EQ(n.substr(n.size() - 9), " (12).txt");
}

TEST(ArchiveText, Cp437) {
    EXPECT_EQ(cp437ToUtf8("caf\x82"), "caf\xC3\xA9");
    EXPECT_EQ(cp437ToUtf8("\x9a\x81"), "\xC3\x9C\xC3\xBC");
    EXPECT_EQ(cp437ToUtf8("\xff"), "\xC2\xA0");
    EXPECT_EQ(cp437ToUtf8("\xb0\xe0"), "\xE2\x96\x91\xCE\xB1");
    EXPECT_TRUE(isValidUtf8(cp437ToUtf8(std::string("\x80\x90\xa0\xb0\xc0\xd0\xe0\xf0\xfe"))));
}

TEST(ArchiveText, Utf16RoundTrip) {
    const std::string s = "a\xC3\xA9\xE2\x82\xAC\xF0\x9F\x98\x80";
    const std::u16string u = utf8ToUtf16(s);
    EXPECT_EQ(u, std::u16string(u"a\u00e9\u20ac\U0001F600"));
    EXPECT_EQ(utf16ToUtf8(u), s);
    // Lone surrogates become U+FFFD instead of CESU or modified UTF-8 bytes.
    EXPECT_EQ(utf16ToUtf8(std::u16string{u'x', static_cast<char16_t>(0xD800), u'y'}), "x\xEF\xBF\xBDy");
    EXPECT_EQ(utf16ToUtf8(std::u16string{static_cast<char16_t>(0xDC00)}), "\xEF\xBF\xBD");
    EXPECT_EQ(utf16ToUtf8(std::u16string{static_cast<char16_t>(0xD83D)}), "\xEF\xBF\xBD");
    // NUL stays one byte, not the C0 80 of modified UTF-8.
    EXPECT_EQ(utf16ToUtf8(std::u16string(1, u'\0')), std::string(1, '\0'));
    // Invalid UTF-8 bytes each become U+FFFD.
    EXPECT_EQ(utf8ToUtf16("a\xFF\xC0\x80" "b"), std::u16string(u"a\uFFFD\uFFFD\uFFFDb"));
    EXPECT_EQ(utf8ToUtf16("\xED\xA0\x80"), std::u16string(u"\uFFFD\uFFFD\uFFFD"));
}
