// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include <gtest/gtest.h>

#include <cstring>

#include "parcel.hpp"

using namespace lxdb;

namespace {

ParcelReader readerOf(const Parcel& p) { return {p.data(), p.size(), p.offsets(), p.objectCount()}; }

constexpr int32_t kHeaderSystem = 0x53595354;

}  // namespace

TEST(LxdbParcel, IntegersRoundTrip) {
    Parcel p;
    p.writeInt32(-7);
    p.writeInt64(0x1122334455667788LL);
    ParcelReader r = readerOf(p);
    EXPECT_EQ(r.readInt32(), -7);
    EXPECT_EQ(r.readInt64(), 0x1122334455667788LL);
    EXPECT_TRUE(r.ok());
}

TEST(LxdbParcel, StringsAreUtf16WithATerminatorAndPadding) {
    Parcel p;
    p.writeString16("abc");
    // Length, three chars and a NUL make 12 bytes; one char more would need padding.
    ASSERT_EQ(p.size(), 12u);
    int32_t length = 0;
    std::memcpy(&length, p.data(), 4);
    EXPECT_EQ(length, 3);
    EXPECT_EQ(p.data()[4], 'a');
    EXPECT_EQ(p.data()[10], 0);
    Parcel odd;
    odd.writeString16("abcd");
    EXPECT_EQ(odd.size() % 4, 0u);
    EXPECT_EQ(readerOf(odd).readString16(), "abcd");
}

TEST(LxdbParcel, TextOutsideAsciiSurvives) {
    const std::string text = "Kerja \xf0\x9f\x9a\x80 \xc3\xa9 \xe6\x97\xa5";  // rocket, e-acute, a CJK sign
    Parcel p;
    p.writeString16(text);
    EXPECT_EQ(readerOf(p).readString16(), text);
    EXPECT_EQ(toUtf16("\xf0\x9f\x9a\x80").size(), 2u);
}

TEST(LxdbParcel, CutOffUtf8DoesNotReadPastTheEnd) {
    EXPECT_EQ(toUtf16("ab\xe6\x97"), u"ab\xfffd");
    EXPECT_EQ(toUtf16("\xf0"), u"\xfffd");
}

TEST(LxdbParcel, NullStringIsToldFromEmpty) {
    Parcel p;
    p.writeNullString16();
    p.writeString16("");
    ParcelReader r = readerOf(p);
    bool isNull = false;
    EXPECT_EQ(r.readString16(&isNull), "");
    EXPECT_TRUE(isNull);
    EXPECT_EQ(r.readString16(&isNull), "");
    EXPECT_FALSE(isNull);
}

TEST(LxdbParcel, InterfaceTokenHasLibbindersHeader) {
    Parcel p;
    p.writeInterfaceToken("android.os.IThing");
    int32_t words[3];
    std::memcpy(words, p.data(), sizeof(words));
    EXPECT_EQ(words[0], 0);   // strict mode policy
    EXPECT_EQ(words[1], -1);  // no work source
    EXPECT_EQ(words[2], kHeaderSystem);
    ParcelReader r = readerOf(p);
    EXPECT_TRUE(r.readInterfaceToken("android.os.IThing"));
    ParcelReader wrong = readerOf(p);
    EXPECT_FALSE(wrong.readInterfaceToken("android.os.IOther"));
}

TEST(LxdbParcel, NullBinderIsNotListedForTheKernel) {
    Parcel p;
    p.writeNullBinder();
    // A listed null would make the kernel create a node for pointer 0.
    EXPECT_EQ(p.objectCount(), 0u);
    EXPECT_EQ(p.size(), kObjectSize + 4);
    ParcelReader r = readerOf(p);
    EXPECT_TRUE(r.readObject().isNull());
    EXPECT_TRUE(r.ok());
}

TEST(LxdbParcel, LocalBinderIsListedWithSystemStability) {
    const int node = 0;
    Parcel p;
    p.writeInt32(9);
    p.writeLocalBinder(&node);
    ASSERT_EQ(p.objectCount(), 1u);
    EXPECT_EQ(p.offsets()[0], 4u);
    ParcelReader r = readerOf(p);
    r.readInt32();
    const FlatObject o = r.readObject();
    EXPECT_EQ(o.type, kTypeBinder);
    EXPECT_EQ(o.handle, reinterpret_cast<uintptr_t>(&node));
    EXPECT_EQ(o.cookie, reinterpret_cast<uintptr_t>(&node));
    int32_t stability = 0;
    std::memcpy(&stability, p.data() + 4 + kObjectSize, 4);
    EXPECT_EQ(stability, kStabilitySystem);
}

TEST(LxdbParcel, PlainBytesNeverBecomeAHandle) {
    // A caller could write these bytes as data; only the kernel's list makes them a binder.
    FlatObject forged;
    forged.type = kTypeHandle;
    forged.handle = 5;
    Parcel p;
    p.writeBytes(&forged, sizeof(forged));
    p.writeInt32(kStabilitySystem);
    ParcelReader r = readerOf(p);
    EXPECT_EQ(r.readObject().type, 0u);
    EXPECT_FALSE(r.ok());
}

TEST(LxdbParcel, SpliceCarriesBytesAndMovesBinders) {
    const int node = 0;
    Parcel source;
    source.writeInt32(1);
    source.writeInt32(2);
    source.writeHandle(42);
    source.writeLocalBinder(&node);
    ParcelReader from = readerOf(source);
    from.readInt32();

    Parcel out;
    out.writeInt32(100);
    out.writeInt32(200);
    out.writeInt32(300);
    out.splice(from, from.position(), from.size());
    ASSERT_EQ(out.objectCount(), 2u);
    EXPECT_EQ(out.offsets()[0], 12u + 4u);
    ParcelReader r = readerOf(out);
    r.seek(12);
    EXPECT_EQ(r.readInt32(), 2);
    const FlatObject handle = r.readObject();
    EXPECT_EQ(handle.type, kTypeHandle);
    EXPECT_EQ(handle.handle, 42u);
    EXPECT_EQ(r.readObject().type, kTypeBinder);
    EXPECT_TRUE(r.ok());
}

TEST(LxdbParcel, ReadsPastTheEndFailOnce) {
    Parcel p;
    p.writeInt32(1);
    ParcelReader r = readerOf(p);
    r.readInt64();
    EXPECT_FALSE(r.ok());
    EXPECT_EQ(r.readInt32(), 0);
    EXPECT_FALSE(r.ok());
}

TEST(LxdbParcel, HostileLengthsStayInside) {
    for (const int32_t length : {0x7fffffff, 0x40000000, 1000}) {
        Parcel p;
        p.writeInt32(length);
        ParcelReader text = readerOf(p);
        EXPECT_EQ(text.readString16(), "");
        EXPECT_FALSE(text.ok());
        ParcelReader bytes = readerOf(p);
        EXPECT_TRUE(bytes.readByteArray().empty());
        EXPECT_FALSE(bytes.ok());
    }
}

TEST(LxdbParcel, StatusSkipsReplyHeaders) {
    Parcel clean;
    clean.writeInt32(0);
    clean.writeInt32(77);
    ParcelReader a = readerOf(clean);
    EXPECT_EQ(a.readStatus(), 0);
    EXPECT_EQ(a.readInt32(), 77);

    Parcel noted;
    noted.writeInt32(-127);
    noted.writeInt32(4);
    noted.writeInt32(0);
    noted.writeInt32(0);
    noted.writeInt32(77);
    ParcelReader b = readerOf(noted);
    EXPECT_EQ(b.readStatus(), 0);
    EXPECT_EQ(b.readInt32(), 77);

    Parcel strict;
    strict.writeInt32(-128);
    strict.writeInt32(4);
    strict.writeInt32(0);
    strict.writeInt32(77);
    ParcelReader c = readerOf(strict);
    EXPECT_EQ(c.readStatus(), 0);
    EXPECT_EQ(c.readInt32(), 77);

    Parcel failed;
    failed.writeInt32(-1);
    EXPECT_EQ(readerOf(failed).readStatus(), -1);
}

TEST(LxdbParcel, BundleHasTheWireShapeJavaReads) {
    const int node = 0;
    Parcel p;
    writeBundle(p, {{"token", "abc", nullptr}, {"service", {}, &node}});
    ParcelReader r = readerOf(p);
    const int32_t length = r.readInt32();
    EXPECT_EQ(length % 4, 0);
    EXPECT_EQ(r.readInt32(), 0x4c444e44);  // BaseBundle.BUNDLE_MAGIC_NATIVE
    const size_t start = r.position();
    EXPECT_EQ(r.readInt32(), 2);
    EXPECT_EQ(r.readString16(), "token");
    EXPECT_EQ(r.readInt32(), 0);  // Parcel.VAL_STRING
    EXPECT_EQ(r.readString16(), "abc");
    EXPECT_EQ(r.readString16(), "service");
    EXPECT_EQ(r.readInt32(), 15);  // Parcel.VAL_IBINDER
    EXPECT_EQ(r.readObject().type, kTypeBinder);
    EXPECT_TRUE(r.ok());
    EXPECT_EQ(r.position() - start, static_cast<size_t>(length));
    EXPECT_EQ(p.objectCount(), 1u);
}
