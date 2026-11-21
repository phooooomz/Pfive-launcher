// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include "parcel.hpp"

#include <cstring>

namespace lxdb {

namespace {

constexpr int32_t kWorkSourceUnset = -1;
constexpr int32_t kHeaderSystem = 0x53595354;  // Parcel.cpp's kHeader: "SYST"
constexpr int32_t kBundleMagicNative = 0x4c444e44;  // "BNDL" for Java, "DNDL" for native
constexpr int32_t kValString = 0;
constexpr int32_t kValBinder = 15;
constexpr int32_t kExHasNotedAppOps = -127;
constexpr int32_t kExHasReplyHeader = -128;
/** What FLAT_BINDER_FLAG_ACCEPTS_FDS and the lowest thread priority come to, as libbinder writes for a local binder. */
constexpr uint32_t kLocalBinderFlags = 0x100 | 19;

size_t padded(size_t n) { return (n + 3) & ~size_t{3}; }

}  // namespace

std::u16string toUtf16(std::string_view s) {
    std::u16string out;
    out.reserve(s.size());
    for (size_t i = 0; i < s.size();) {
        const auto c = static_cast<uint8_t>(s[i]);
        uint32_t cp = c;
        size_t extra = 0;
        if (c >= 0xf0) { cp = c & 0x07; extra = 3; }
        else if (c >= 0xe0) { cp = c & 0x0f; extra = 2; }
        else if (c >= 0xc0) { cp = c & 0x1f; extra = 1; }
        else if (c >= 0x80) { cp = 0xfffd; }
        if (extra > 0 && i + extra >= s.size()) {
            out.push_back(u'\xfffd');
            break;
        }
        for (size_t k = 1; k <= extra; k++) cp = (cp << 6) | (static_cast<uint8_t>(s[i + k]) & 0x3f);
        i += extra + 1;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back(static_cast<char16_t>(0xd800 + (cp >> 10)));
            out.push_back(static_cast<char16_t>(0xdc00 + (cp & 0x3ff)));
        } else {
            out.push_back(static_cast<char16_t>(cp));
        }
    }
    return out;
}

std::string toUtf8(std::u16string_view s) {
    std::string out;
    out.reserve(s.size());
    for (size_t i = 0; i < s.size(); i++) {
        uint32_t cp = s[i];
        if (cp >= 0xd800 && cp < 0xdc00 && i + 1 < s.size() && s[i + 1] >= 0xdc00 && s[i + 1] < 0xe000) {
            cp = 0x10000 + ((cp - 0xd800) << 10) + (s[++i] - 0xdc00);
        }
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xc0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xe0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
        } else {
            out.push_back(static_cast<char>(0xf0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3f)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
        }
    }
    return out;
}

// ---- reading ----

bool ParcelReader::take(size_t bytes, size_t* at) {
    if (!ok_ || bytes > size_ || pos_ > size_ - bytes) {
        ok_ = false;
        return false;
    }
    *at = pos_;
    pos_ += bytes;
    return true;
}

void ParcelReader::seek(size_t at) {
    if (at > size_) ok_ = false;
    else pos_ = at;
}

int32_t ParcelReader::readInt32() {
    size_t at = 0;
    if (!take(4, &at)) return 0;
    int32_t v = 0;
    std::memcpy(&v, data_ + at, 4);
    return v;
}

int64_t ParcelReader::readInt64() {
    size_t at = 0;
    if (!take(8, &at)) return 0;
    int64_t v = 0;
    std::memcpy(&v, data_ + at, 8);
    return v;
}

std::string ParcelReader::readString16(bool* isNull) {
    const int32_t chars = readInt32();
    if (isNull) *isNull = chars < 0;
    if (chars < 0 || !ok_) return {};
    size_t at = 0;
    const size_t bytes = (static_cast<size_t>(chars) + 1) * 2;
    if (!take(padded(bytes), &at)) return {};
    std::u16string s(static_cast<size_t>(chars), u'\0');
    std::memcpy(s.data(), data_ + at, static_cast<size_t>(chars) * 2);
    return toUtf8(s);
}

std::vector<uint8_t> ParcelReader::readByteArray() {
    const int32_t length = readInt32();
    if (length < 0 || !ok_) return {};
    size_t at = 0;
    if (!take(padded(static_cast<size_t>(length)), &at)) return {};
    return {data_ + at, data_ + at + length};
}

bool ParcelReader::readInterfaceToken(std::string_view descriptor) {
    readInt32();  // strict mode policy
    readInt32();  // work source
    if (readInt32() != kHeaderSystem) return false;
    return readString16() == descriptor && ok_;
}

FlatObject ParcelReader::readObject() {
    FlatObject o;
    bool listed = false;
    for (size_t i = 0; i < objects_ && !listed; i++) listed = offsets_[i] == pos_;
    size_t at = 0;
    if (!take(kObjectSize, &at)) return o;
    std::memcpy(&o, data_ + at, kObjectSize);
    readInt32();  // stability
    // A null binder is the one object libbinder leaves out of the kernel's list.
    if (!listed && !o.isNull()) {
        ok_ = false;
        o = FlatObject{};
    }
    return o;
}

FlatObject ParcelReader::objectAt(size_t index) const {
    FlatObject o;
    if (index >= objects_ || offsets_[index] > size_ || size_ - offsets_[index] < kObjectSize) return o;
    std::memcpy(&o, data_ + offsets_[index], kObjectSize);
    return o;
}

int32_t ParcelReader::readStatus() {
    int32_t code = readInt32();
    if (code == kExHasNotedAppOps) {
        const int32_t skip = readInt32();
        seek(pos_ + static_cast<size_t>(skip > 0 ? skip : 0));
        code = readInt32();
    }
    if (code == kExHasReplyHeader) {
        const int32_t header = readInt32();
        seek(pos_ + static_cast<size_t>(header > 0 ? header : 0));
        return 0;
    }
    return code;
}

// ---- writing ----

void Parcel::writeBytes(const void* data, size_t size) {
    const size_t at = data_.size();
    data_.resize(at + padded(size));
    if (size > 0) std::memcpy(data_.data() + at, data, size);
}

void Parcel::writeInt32(int32_t v) { writeBytes(&v, 4); }

void Parcel::writeInt64(int64_t v) { writeBytes(&v, 8); }

void Parcel::writeString16(std::string_view utf8) {
    const std::u16string s = toUtf16(utf8);
    writeInt32(static_cast<int32_t>(s.size()));
    writeBytes(s.c_str(), (s.size() + 1) * 2);
}

void Parcel::writeNullString16() { writeInt32(-1); }

void Parcel::writeByteArray(const std::vector<uint8_t>& bytes) {
    writeInt32(static_cast<int32_t>(bytes.size()));
    writeBytes(bytes.data(), bytes.size());
}

void Parcel::writeInterfaceToken(std::string_view descriptor) {
    writeInt32(0);  // strict mode policy: asking for no penalties keeps replies free of a strict mode header
    writeInt32(kWorkSourceUnset);
    writeInt32(kHeaderSystem);
    writeString16(descriptor);
}

void Parcel::writeObject(const FlatObject& o) {
    objects_.push_back(data_.size());
    writeBytes(&o, kObjectSize);
    writeInt32(kStabilitySystem);
}

void Parcel::writeNullBinder() {
    // Listed, it would make the kernel create a node for pointer 0.
    FlatObject o;
    o.type = kTypeBinder;
    writeBytes(&o, kObjectSize);
    writeInt32(0);
}

void Parcel::writeLocalBinder(const void* node) {
    FlatObject o;
    o.type = kTypeBinder;
    o.flags = kLocalBinderFlags;
    o.handle = reinterpret_cast<uintptr_t>(node);
    o.cookie = reinterpret_cast<uintptr_t>(node);
    writeObject(o);
}

void Parcel::writeHandle(uint32_t handle) {
    FlatObject o;
    o.type = kTypeHandle;
    o.handle = handle;
    writeObject(o);
}

void Parcel::splice(const ParcelReader& from, size_t begin, size_t end) {
    if (begin >= end || end > from.size_) return;
    const size_t shift = data_.size() - begin;
    writeBytes(from.data_ + begin, end - begin);
    for (size_t i = 0; i < from.objects_; i++) {
        const uint64_t at = from.offsets_[i];
        if (at >= begin && at < end) objects_.push_back(at + shift);
    }
}

void Parcel::patchInt32(size_t at, int32_t v) {
    if (at + 4 <= data_.size()) std::memcpy(data_.data() + at, &v, 4);
}

void writeBundle(Parcel& p, const std::vector<BundleItem>& items) {
    const size_t lengthAt = p.size();
    p.writeInt32(-1);
    p.writeInt32(kBundleMagicNative);
    const size_t start = p.size();
    p.writeInt32(static_cast<int32_t>(items.size()));
    for (const BundleItem& item : items) {
        p.writeString16(item.key);
        if (item.binder != nullptr) {
            p.writeInt32(kValBinder);
            p.writeLocalBinder(item.binder);
        } else {
            p.writeInt32(kValString);
            p.writeString16(item.text);
        }
    }
    p.patchInt32(lengthAt, static_cast<int32_t>(p.size() - start));
}

}  // namespace lxdb
