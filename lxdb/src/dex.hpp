// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// Reads framework.jar. An AIDL method's transaction number is its place in the file, and a ROM that adds a method moves
// every number after it, so lxdb asks the phone's own framework instead of carrying a table: XOS's IActivityManager has
// 357 methods. The method's parameter list says whether it still takes what lxdb sends.
#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace lxdb {

/** One classes.dex held in memory that outlives this object. Every offset is checked against its size. */
class DexFile {
public:
    DexFile(const uint8_t* data, size_t size);

    bool valid() const { return valid_; }
    /** The class_def index of "Lpackage/Class;", or -1. */
    int32_t findClass(std::string_view descriptor) const;
    std::optional<int32_t> staticInt(int32_t classDef, std::string_view field) const;
    /** Each parameter list "(Ljava/lang/String;I)V" of the class's methods called [name]; an overload gives several. */
    std::vector<std::string> methodShapes(int32_t classDef, std::string_view name) const;
    bool hasField(int32_t classDef, std::string_view name) const;

private:
    template <typename T> const T* at(size_t offset) const;
    std::string_view stringAt(uint32_t index) const;
    std::string_view typeAt(uint32_t index) const;
    std::string shapeOf(uint32_t protoIndex) const;
    bool readClass(int32_t classDef, uint32_t* staticFields, uint32_t* instanceFields, uint32_t* direct, uint32_t* virtuals,
                   const uint8_t** cursor) const;

    const uint8_t* data_;
    size_t size_;
    bool valid_ = false;
    uint32_t stringCount_ = 0, stringOff_ = 0;
    uint32_t typeCount_ = 0, typeOff_ = 0;
    uint32_t protoCount_ = 0, protoOff_ = 0;
    uint32_t fieldCount_ = 0, fieldOff_ = 0;
    uint32_t methodCount_ = 0, methodOff_ = 0;
    uint32_t classCount_ = 0, classOff_ = 0;
};

/** Every classes*.dex of a jar. */
class Jar {
public:
    Jar() = default;
    ~Jar();
    Jar(const Jar&) = delete;
    Jar& operator=(const Jar&) = delete;

    bool open(const std::string& path, std::string* why);
    /** The same as open, for a jar already in memory. The memory must outlive the jar. */
    bool openMemory(const uint8_t* data, size_t size, std::string* why);

    /** Where the class is. Null when no dex has it. */
    const DexFile* find(std::string_view descriptor, int32_t* classDef) const;
    std::optional<int32_t> staticInt(std::string_view descriptor, std::string_view field) const;
    std::vector<std::string> methodShapes(std::string_view descriptor, std::string_view name) const;
    bool hasField(std::string_view descriptor, std::string_view name) const;
    /** Lets go of the dex files: the inflated copies on the heap and the mapped jar. Lookups then find nothing. */
    void close();

private:
    const uint8_t* map_ = nullptr;
    size_t mapSize_ = 0;
    std::vector<std::unique_ptr<std::vector<uint8_t>>> inflated_;
    std::vector<DexFile> dex_;
};

/** "android.os.IPowerManager" as a dex type: "Landroid/os/IPowerManager;", or with [suffix] "$Stub" inside the semicolon. */
std::string dexDescriptor(std::string_view dotted, std::string_view suffix = {});

}  // namespace lxdb
