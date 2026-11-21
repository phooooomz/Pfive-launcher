// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// The wire format of an Android Parcel, as libbinder and Parcel.java write it on a 64-bit process. Nothing here
// touches the driver, so the host tests run all of it.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace lxdb {

constexpr uint32_t kTypeBinder = 0x73622a85;  // BINDER_TYPE_BINDER, "sb*"
constexpr uint32_t kTypeHandle = 0x73682a85;  // BINDER_TYPE_HANDLE, "sh*"
constexpr size_t kObjectSize = 24;
/** internal::Stability::SYSTEM, written after every binder. A receiver rejects a live binder of unknown stability. */
constexpr int32_t kStabilitySystem = 0x0c;

/** flat_binder_object. [handle] holds the binder pointer for kTypeBinder and the handle for kTypeHandle. */
struct FlatObject {
    uint32_t type = 0;
    uint32_t flags = 0;
    uint64_t handle = 0;
    uint64_t cookie = 0;

    bool isNull() const { return type == kTypeBinder && handle == 0; }
};

class Parcel;

/** Reads what a transaction carried. Any read past the end fails the whole reader, and later reads return zero. */
class ParcelReader {
public:
    ParcelReader() = default;
    ParcelReader(const uint8_t* data, size_t size, const uint64_t* offsets, size_t objects)
        : data_(data), size_(size), offsets_(offsets), objects_(objects) {}

    bool ok() const { return ok_; }
    size_t position() const { return pos_; }
    size_t size() const { return size_; }
    void seek(size_t at);

    int32_t readInt32();
    int64_t readInt64();
    /** Null reads as empty; [isNull] tells the two apart. */
    std::string readString16(bool* isNull = nullptr);
    std::vector<uint8_t> readByteArray();
    /** Past the interface header; false unless the descriptor is [descriptor]. */
    bool readInterfaceToken(std::string_view descriptor);
    /** A binder and its stability word. Only offsets the kernel listed count: plain bytes never become a handle. */
    FlatObject readObject();
    /** The [index]th binder of the transaction, wherever it sits, without moving the read position. */
    FlatObject objectAt(size_t index) const;
    size_t objectCount() const { return objects_; }

    /** Skips the header of a reply. 0, or the code of the exception the reply carries. */
    int32_t readStatus();

private:
    friend class Parcel;
    bool take(size_t bytes, size_t* at);

    const uint8_t* data_ = nullptr;
    size_t size_ = 0;
    const uint64_t* offsets_ = nullptr;
    size_t objects_ = 0;
    size_t pos_ = 0;
    bool ok_ = true;
};

class Parcel {
public:
    void writeInt32(int32_t v);
    void writeInt64(int64_t v);
    void writeString16(std::string_view utf8);
    void writeNullString16();
    void writeBytes(const void* data, size_t size);  // pads to 4 bytes
    void writeByteArray(const std::vector<uint8_t>& bytes);
    void writeInterfaceToken(std::string_view descriptor);
    void writeNullBinder();
    /** A binder of this process; [node] is what the kernel hands back with each transaction for it. */
    void writeLocalBinder(const void* node);
    void writeHandle(uint32_t handle);
    /** Copies [begin, end) of [from], binders included, so a parcel made elsewhere passes through unread. */
    void splice(const ParcelReader& from, size_t begin, size_t end);
    /** Overwrites a word written earlier. */
    void patchInt32(size_t at, int32_t v);

    const uint8_t* data() const { return data_.data(); }
    size_t size() const { return data_.size(); }
    const uint64_t* offsets() const { return objects_.data(); }
    size_t objectCount() const { return objects_.size(); }

private:
    void writeObject(const FlatObject& o);

    std::vector<uint8_t> data_;
    std::vector<uint64_t> objects_;
};

/** One entry of a bundle this process writes. */
struct BundleItem {
    std::string key;
    std::string text;       // a String value, unless [binder] is set
    const void* binder = nullptr;
};

/**
 * A Bundle as Parcel.writeBundle writes it. The magic says "written by native code", which spares the keys the sort
 * by hash that a Java bundle promises.
 */
void writeBundle(Parcel& p, const std::vector<BundleItem>& items);

std::u16string toUtf16(std::string_view utf8);
std::string toUtf8(std::u16string_view utf16);

}  // namespace lxdb
