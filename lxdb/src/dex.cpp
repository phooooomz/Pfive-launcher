// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include "dex.hpp"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <zlib.h>

#include <algorithm>
#include <cstring>

namespace lxdb {

namespace {

constexpr size_t kHeaderSize = 0x70;
constexpr uint32_t kEndianTag = 0x12345678;
constexpr uint32_t kEndOfCentralDir = 0x06054b50;
constexpr uint32_t kCentralEntry = 0x02014b50;
constexpr uint32_t kLocalEntry = 0x04034b50;
constexpr uint16_t kStored = 0;
constexpr uint16_t kDeflated = 8;
constexpr size_t kMaxDex = 512u * 1024 * 1024;
constexpr uint8_t kValueArray = 0x1c;
constexpr uint8_t kValueAnnotation = 0x1d;
constexpr uint8_t kValueNull = 0x1e;
constexpr uint8_t kValueBoolean = 0x1f;
constexpr uint8_t kValueInt = 0x04;
constexpr uint8_t kValueByte = 0x00;
constexpr uint8_t kValueShort = 0x02;
constexpr uint8_t kValueChar = 0x03;

uint16_t read16(const uint8_t* p) {
    uint16_t v = 0;
    std::memcpy(&v, p, 2);
    return v;
}

uint32_t read32(const uint8_t* p) {
    uint32_t v = 0;
    std::memcpy(&v, p, 4);
    return v;
}

bool readUleb(const uint8_t** p, const uint8_t* end, uint32_t* out) {
    uint32_t v = 0;
    for (int shift = 0; shift < 35; shift += 7) {
        if (*p >= end) return false;
        const uint8_t b = *(*p)++;
        v |= static_cast<uint32_t>(b & 0x7f) << shift;
        if ((b & 0x80) == 0) {
            *out = v;
            return true;
        }
    }
    return false;
}

bool skipEncodedValue(const uint8_t** p, const uint8_t* end);

bool skipEncodedArrayItems(const uint8_t** p, const uint8_t* end, uint32_t count) {
    for (uint32_t i = 0; i < count; i++) {
        if (!skipEncodedValue(p, end)) return false;
    }
    return true;
}

bool skipEncodedValue(const uint8_t** p, const uint8_t* end) {
    if (*p >= end) return false;
    const uint8_t head = *(*p)++;
    const uint8_t type = head & 0x1f;
    const uint8_t arg = head >> 5;
    uint32_t n = 0;
    switch (type) {
        case kValueNull:
        case kValueBoolean:
            return true;
        case kValueArray:
            return readUleb(p, end, &n) && skipEncodedArrayItems(p, end, n);
        case kValueAnnotation: {
            uint32_t ignored = 0;
            if (!readUleb(p, end, &ignored) || !readUleb(p, end, &n)) return false;
            for (uint32_t i = 0; i < n; i++) {
                if (!readUleb(p, end, &ignored) || !skipEncodedValue(p, end)) return false;
            }
            return true;
        }
        default:
            if (static_cast<size_t>(end - *p) < static_cast<size_t>(arg) + 1) return false;
            *p += arg + 1;
            return true;
    }
}

}  // namespace

DexFile::DexFile(const uint8_t* data, size_t size) : data_(data), size_(size) {
    if (size < kHeaderSize || std::memcmp(data, "dex\n", 4) != 0) return;
    if (read32(data + 0x28) != kEndianTag) return;
    stringCount_ = read32(data + 0x38);
    stringOff_ = read32(data + 0x3c);
    typeCount_ = read32(data + 0x40);
    typeOff_ = read32(data + 0x44);
    protoCount_ = read32(data + 0x48);
    protoOff_ = read32(data + 0x4c);
    fieldCount_ = read32(data + 0x50);
    fieldOff_ = read32(data + 0x54);
    methodCount_ = read32(data + 0x58);
    methodOff_ = read32(data + 0x5c);
    classCount_ = read32(data + 0x60);
    classOff_ = read32(data + 0x64);
    const auto fits = [size](uint32_t offset, uint32_t count, size_t item) {
        return offset <= size && static_cast<uint64_t>(count) * item <= size - offset;
    };
    valid_ = fits(stringOff_, stringCount_, 4) && fits(typeOff_, typeCount_, 4) && fits(protoOff_, protoCount_, 12) &&
             fits(fieldOff_, fieldCount_, 8) && fits(methodOff_, methodCount_, 8) && fits(classOff_, classCount_, 32);
}

template <typename T> const T* DexFile::at(size_t offset) const {
    return offset <= size_ && size_ - offset >= sizeof(T) ? reinterpret_cast<const T*>(data_ + offset) : nullptr;
}

std::string_view DexFile::stringAt(uint32_t index) const {
    if (index >= stringCount_) return {};
    const uint32_t off = read32(data_ + stringOff_ + 4 * index);
    if (off >= size_) return {};
    const uint8_t* p = data_ + off;
    const uint8_t* end = data_ + size_;
    uint32_t units = 0;
    if (!readUleb(&p, end, &units)) return {};
    const auto* nul = static_cast<const uint8_t*>(std::memchr(p, 0, static_cast<size_t>(end - p)));
    if (nul == nullptr) return {};
    return {reinterpret_cast<const char*>(p), static_cast<size_t>(nul - p)};
}

std::string_view DexFile::typeAt(uint32_t index) const {
    if (index >= typeCount_) return {};
    return stringAt(read32(data_ + typeOff_ + 4 * index));
}

std::string DexFile::shapeOf(uint32_t protoIndex) const {
    if (protoIndex >= protoCount_) return {};
    const uint8_t* proto = data_ + protoOff_ + 12 * static_cast<size_t>(protoIndex);
    const uint32_t returns = read32(proto + 4);
    const uint32_t params = read32(proto + 8);
    std::string shape = "(";
    if (params != 0 && params <= size_ - 4) {
        const uint32_t count = read32(data_ + params);
        for (uint32_t i = 0; i < count && params + 4 + 2 * static_cast<size_t>(i) + 2 <= size_; i++) {
            shape += typeAt(read16(data_ + params + 4 + 2 * i));
        }
    }
    shape += ')';
    shape += typeAt(returns);
    return shape;
}

int32_t DexFile::findClass(std::string_view descriptor) const {
    if (!valid_) return -1;
    // string_ids and type_ids are sorted, and class_defs are not.
    uint32_t low = 0;
    uint32_t high = stringCount_;
    while (low < high) {
        const uint32_t mid = low + (high - low) / 2;
        if (stringAt(mid) < descriptor) low = mid + 1;
        else high = mid;
    }
    if (low >= stringCount_ || stringAt(low) != descriptor) return -1;
    const uint32_t stringIndex = low;
    low = 0;
    high = typeCount_;
    while (low < high) {
        const uint32_t mid = low + (high - low) / 2;
        if (read32(data_ + typeOff_ + 4 * mid) < stringIndex) low = mid + 1;
        else high = mid;
    }
    if (low >= typeCount_ || read32(data_ + typeOff_ + 4 * low) != stringIndex) return -1;
    for (uint32_t i = 0; i < classCount_; i++) {
        if (read32(data_ + classOff_ + 32 * static_cast<size_t>(i)) == low) return static_cast<int32_t>(i);
    }
    return -1;
}

bool DexFile::readClass(int32_t classDef, uint32_t* staticFields, uint32_t* instanceFields, uint32_t* direct,
                        uint32_t* virtuals, const uint8_t** cursor) const {
    if (classDef < 0 || static_cast<uint32_t>(classDef) >= classCount_) return false;
    const uint32_t dataOff = read32(data_ + classOff_ + 32 * static_cast<size_t>(classDef) + 24);
    if (dataOff == 0 || dataOff >= size_) return false;
    const uint8_t* p = data_ + dataOff;
    const uint8_t* end = data_ + size_;
    if (!readUleb(&p, end, staticFields) || !readUleb(&p, end, instanceFields) || !readUleb(&p, end, direct) ||
        !readUleb(&p, end, virtuals)) {
        return false;
    }
    *cursor = p;
    return true;
}

std::optional<int32_t> DexFile::staticInt(int32_t classDef, std::string_view field) const {
    uint32_t statics = 0, instances = 0, direct = 0, virtuals = 0;
    const uint8_t* p = nullptr;
    if (!readClass(classDef, &statics, &instances, &direct, &virtuals, &p)) return std::nullopt;
    const uint8_t* end = data_ + size_;
    uint32_t index = 0;
    uint32_t place = statics;
    for (uint32_t i = 0; i < statics; i++) {
        uint32_t delta = 0, flags = 0;
        if (!readUleb(&p, end, &delta) || !readUleb(&p, end, &flags)) return std::nullopt;
        index += delta;
        if (index < fieldCount_ && stringAt(read32(data_ + fieldOff_ + 8 * static_cast<size_t>(index) + 4)) == field) {
            place = i;
            break;
        }
    }
    if (place == statics) return std::nullopt;
    const uint32_t valuesOff = read32(data_ + classOff_ + 32 * static_cast<size_t>(classDef) + 28);
    // A value past the end of the list is the field's default.
    if (valuesOff == 0 || valuesOff >= size_) return 0;
    const uint8_t* v = data_ + valuesOff;
    uint32_t count = 0;
    if (!readUleb(&v, end, &count)) return std::nullopt;
    if (place >= count) return 0;
    if (!skipEncodedArrayItems(&v, end, place) || v >= end) return std::nullopt;
    const uint8_t head = *v++;
    const uint8_t type = head & 0x1f;
    if (type != kValueInt && type != kValueShort && type != kValueChar && type != kValueByte) return std::nullopt;
    const size_t bytes = (head >> 5) + 1u;
    if (bytes > 4 || static_cast<size_t>(end - v) < bytes) return std::nullopt;
    uint32_t value = 0;
    for (size_t i = 0; i < bytes; i++) value |= static_cast<uint32_t>(v[i]) << (8 * i);
    // Values are stored without their leading sign bytes.
    if (type != kValueChar && bytes < 4 && (v[bytes - 1] & 0x80) != 0) value |= ~uint32_t{0} << (8 * bytes);
    return static_cast<int32_t>(value);
}

std::vector<std::string> DexFile::methodShapes(int32_t classDef, std::string_view name) const {
    std::vector<std::string> shapes;
    uint32_t statics = 0, instances = 0, direct = 0, virtuals = 0;
    const uint8_t* p = nullptr;
    if (!readClass(classDef, &statics, &instances, &direct, &virtuals, &p)) return shapes;
    const uint8_t* end = data_ + size_;
    uint32_t ignored = 0;
    for (uint32_t i = 0; i < (statics + instances) * 2; i++) {
        if (!readUleb(&p, end, &ignored)) return shapes;
    }
    const auto walk = [&](uint32_t count) {
        uint32_t index = 0;
        for (uint32_t i = 0; i < count; i++) {
            uint32_t delta = 0, flags = 0, code = 0;
            if (!readUleb(&p, end, &delta) || !readUleb(&p, end, &flags) || !readUleb(&p, end, &code)) return false;
            index += delta;
            if (index >= methodCount_) continue;
            const uint8_t* method = data_ + methodOff_ + 8 * static_cast<size_t>(index);
            if (stringAt(read32(method + 4)) == name) shapes.push_back(shapeOf(read16(method + 2)));
        }
        return true;
    };
    if (walk(direct)) walk(virtuals);
    return shapes;
}

bool DexFile::hasField(int32_t classDef, std::string_view name) const {
    uint32_t statics = 0, instances = 0, direct = 0, virtuals = 0;
    const uint8_t* p = nullptr;
    if (!readClass(classDef, &statics, &instances, &direct, &virtuals, &p)) return false;
    const uint8_t* end = data_ + size_;
    for (uint32_t i = 0, count = statics; i < 2; i++, count = instances) {
        uint32_t index = 0;
        for (uint32_t k = 0; k < count; k++) {
            uint32_t delta = 0, flags = 0;
            if (!readUleb(&p, end, &delta) || !readUleb(&p, end, &flags)) return false;
            index += delta;
            if (index < fieldCount_ && stringAt(read32(data_ + fieldOff_ + 8 * static_cast<size_t>(index) + 4)) == name) return true;
        }
    }
    return false;
}

// ---- the jar ----

Jar::~Jar() { close(); }

void Jar::close() {
    dex_.clear();
    dex_.shrink_to_fit();
    inflated_.clear();
    inflated_.shrink_to_fit();
    if (map_ != nullptr) munmap(const_cast<uint8_t*>(map_), mapSize_);
    map_ = nullptr;
    mapSize_ = 0;
}

bool Jar::open(const std::string& path, std::string* why) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        *why = "cannot read " + path;
        return false;
    }
    struct stat st {};
    const bool sized = fstat(fd, &st) == 0 && st.st_size > 0;
    void* map = sized ? mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ, MAP_PRIVATE, fd, 0) : MAP_FAILED;
    ::close(fd);
    if (map == MAP_FAILED) {
        *why = "cannot map " + path;
        return false;
    }
    map_ = static_cast<const uint8_t*>(map);
    mapSize_ = static_cast<size_t>(st.st_size);
    return openMemory(map_, mapSize_, why);
}

bool Jar::openMemory(const uint8_t* data, size_t size, std::string* why) {
    constexpr size_t kEndSize = 22;
    constexpr size_t kMaxComment = 0xffff;
    if (size < kEndSize) {
        *why = "not a zip file";
        return false;
    }
    size_t end = size - kEndSize;
    const size_t stop = end > kMaxComment ? end - kMaxComment : 0;
    while (read32(data + end) != kEndOfCentralDir) {
        if (end == stop) {
            *why = "not a zip file";
            return false;
        }
        end--;
    }
    const uint16_t entries = read16(data + end + 10);
    size_t at = read32(data + end + 16);
    std::vector<std::pair<std::string, DexFile>> found;
    for (uint32_t i = 0; i < entries; i++) {
        constexpr size_t kCentralSize = 46;
        if (at > size || size - at < kCentralSize || read32(data + at) != kCentralEntry) break;
        const uint16_t method = read16(data + at + 10);
        const uint32_t packed = read32(data + at + 20);
        const uint32_t unpacked = read32(data + at + 24);
        const uint16_t nameLength = read16(data + at + 28);
        const uint16_t extra = read16(data + at + 30);
        const uint16_t comment = read16(data + at + 32);
        const uint32_t local = read32(data + at + 42);
        if (size - at - kCentralSize < static_cast<size_t>(nameLength) + extra + comment) break;
        const std::string_view name(reinterpret_cast<const char*>(data + at + kCentralSize), nameLength);
        at += kCentralSize + nameLength + extra + comment;
        const bool isDex = name.size() > 11 && name.starts_with("classes") && name.ends_with(".dex");
        if (!isDex && name != "classes.dex") continue;
        constexpr size_t kLocalSize = 30;
        if (local > size || size - local < kLocalSize || read32(data + local) != kLocalEntry) continue;
        const size_t start = local + kLocalSize + read16(data + local + 26) + read16(data + local + 28);
        if (start > size || size - start < packed || unpacked > kMaxDex) continue;
        if (method == kStored) {
            found.emplace_back(std::string(name), DexFile(data + start, packed));
        } else if (method == kDeflated) {
            auto bytes = std::make_unique<std::vector<uint8_t>>(unpacked);
            z_stream z{};
            z.next_in = const_cast<Bytef*>(data + start);
            z.avail_in = packed;
            z.next_out = bytes->data();
            z.avail_out = unpacked;
            const bool inflated = inflateInit2(&z, -MAX_WBITS) == Z_OK && inflate(&z, Z_FINISH) == Z_STREAM_END;
            inflateEnd(&z);
            if (!inflated) continue;
            found.emplace_back(std::string(name), DexFile(bytes->data(), bytes->size()));
            inflated_.push_back(std::move(bytes));
        }
    }
    // classes.dex, classes2.dex, ...: what the class loader would find first stays first.
    std::stable_sort(found.begin(), found.end(), [](const auto& a, const auto& b) {
        return a.first.size() != b.first.size() ? a.first.size() < b.first.size() : a.first < b.first;
    });
    for (auto& entry : found) {
        if (entry.second.valid()) dex_.push_back(entry.second);
    }
    if (dex_.empty()) {
        *why = "no dex in the jar";
        return false;
    }
    return true;
}

const DexFile* Jar::find(std::string_view descriptor, int32_t* classDef) const {
    for (const DexFile& dex : dex_) {
        const int32_t index = dex.findClass(descriptor);
        if (index >= 0) {
            *classDef = index;
            return &dex;
        }
    }
    return nullptr;
}

std::optional<int32_t> Jar::staticInt(std::string_view descriptor, std::string_view field) const {
    int32_t classDef = -1;
    const DexFile* dex = find(descriptor, &classDef);
    return dex != nullptr ? dex->staticInt(classDef, field) : std::nullopt;
}

std::vector<std::string> Jar::methodShapes(std::string_view descriptor, std::string_view name) const {
    int32_t classDef = -1;
    const DexFile* dex = find(descriptor, &classDef);
    return dex != nullptr ? dex->methodShapes(classDef, name) : std::vector<std::string>{};
}

bool Jar::hasField(std::string_view descriptor, std::string_view name) const {
    int32_t classDef = -1;
    const DexFile* dex = find(descriptor, &classDef);
    return dex != nullptr && dex->hasField(classDef, name);
}

std::string dexDescriptor(std::string_view dotted, std::string_view suffix) {
    std::string out = "L";
    out += dotted;
    std::replace(out.begin(), out.end(), '.', '/');
    out += suffix;
    out += ';';
    return out;
}

}  // namespace lxdb
