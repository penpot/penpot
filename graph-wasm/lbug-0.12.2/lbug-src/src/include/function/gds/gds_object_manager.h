#pragma once

#include <atomic>
#include <vector>

#include "storage/buffer_manager/memory_manager.h"
#include "storage/buffer_manager/mm_allocator.h"

namespace lbug {
namespace function {

// ObjectBlock represents a pre-allocated amount of memory that can hold up to maxElements objects
// ObjectBlock should be accessed by a single thread.
template<typename T>
class ObjectBlock {
public:
    ObjectBlock(std::unique_ptr<storage::MemoryBuffer> block, uint64_t sizeInBytes)
        : block{std::move(block)} {
        maxElements.store(sizeInBytes / (sizeof(T)), std::memory_order_relaxed);
        nextPosToWrite.store(0, std::memory_order_relaxed);
    }

    T* reserveNext() { return getData() + nextPosToWrite.fetch_add(1, std::memory_order_relaxed); }
    void revertLast() { nextPosToWrite.fetch_sub(1, std::memory_order_relaxed); }

    bool hasSpace() const {
        return nextPosToWrite.load(std::memory_order_relaxed) <
               maxElements.load(std::memory_order_relaxed);
    }

private:
    T* getData() const { return reinterpret_cast<T*>(block->getData()); }

private:
    std::unique_ptr<storage::MemoryBuffer> block;
    std::atomic<uint64_t> maxElements;
    std::atomic<uint64_t> nextPosToWrite;
};

// Pre-allocated array of objects.
template<typename T>
class ObjectArray {
public:
    ObjectArray() : size{0} {}
    ObjectArray(const common::offset_t size, storage::MemoryManager* mm,
        bool initializeToZero = false)
        : size{size}, mm{mm} {
        allocate(size, mm, initializeToZero);
    }

    void allocate(const common::offset_t size, storage::MemoryManager* mm, bool initializeToZero) {
        allocation = mm->allocateBuffer(initializeToZero, size * sizeof(T));
        data = std::span<T>(reinterpret_cast<T*>(allocation->getData()), size);
        this->size = size;
    }

    common::offset_t getSize() const { return size; }

    void reallocate(const common::offset_t newSize, storage::MemoryManager* mm) {
        if (newSize > size) {
            allocate(newSize, mm, false /* initializeToZero */);
        }
    }

    void set(const common::offset_t pos, const T value) {
        KU_ASSERT_UNCONDITIONAL(pos < size);
        data[pos] = value;
    }

    const T& get(const common::offset_t pos) const {
        KU_ASSERT_UNCONDITIONAL(pos < size);
        return data[pos];
    }

    T& getUnsafe(const common::offset_t pos) {
        KU_ASSERT_UNCONDITIONAL(pos < size);
        return data[pos];
    }

private:
    template<typename U>
    friend class AtomicObjectArray;
    common::offset_t size;
    std::span<T> data;
    std::unique_ptr<storage::MemoryBuffer> allocation;
    storage::MemoryManager* mm = nullptr;
};

// Pre-allocated array of atomic objects.
template<typename T>
class AtomicObjectArray {
public:
    AtomicObjectArray() = default;
    AtomicObjectArray(const common::offset_t size, storage::MemoryManager* mm,
        bool initializeToZero = false)
        : array{ObjectArray<std::atomic<T>>(size, mm, initializeToZero)} {}

    common::offset_t getSize() const { return array.size; }

    void reallocate(const common::offset_t newSize, storage::MemoryManager* mm) {
        array.reallocate(newSize, mm);
    }

    void set(common::offset_t pos, const T& value,
        std::memory_order order = std::memory_order_seq_cst) {
        KU_ASSERT_UNCONDITIONAL(pos < array.size);
        array.data[pos].store(value, order);
    }

    T get(const common::offset_t pos, std::memory_order order = std::memory_order_seq_cst) {
        KU_ASSERT_UNCONDITIONAL(pos < array.size);
        return array.data[pos].load(order);
    }

    void fetchAdd(common::offset_t pos, const T& value,
        std::memory_order order = std::memory_order_seq_cst) {
        KU_ASSERT_UNCONDITIONAL(pos < array.size);
        array.data[pos].fetch_add(value, order);
    }

    bool compareExchangeMax(const common::offset_t src, const common::offset_t dest,
        std::memory_order order = std::memory_order_seq_cst) {
        auto srcValue = get(src, order);
        auto dstValue = get(dest, order);
        // From https://en.cppreference.com/w/cpp/std::atomic/std::atomic/compare_exchange:
        // When a compare-and-exchange is in a loop, the weak version will yield better performance
        // on some platforms.
        while (dstValue < srcValue) {
            if (array.data[dest].compare_exchange_weak(dstValue, srcValue)) {
                return true;
            }
        }
        return false;
    }

private:
    ObjectArray<std::atomic<T>> array;
};

template<typename T>
class ku_vector_t {
public:
    explicit ku_vector_t(storage::MemoryManager* mm) : vec(storage::MmAllocator<T>(mm)) {}
    ku_vector_t(storage::MemoryManager* mm, std::size_t size)
        : vec(size, storage::MmAllocator<T>(mm)) {}

    void reserve(std::size_t size) { vec.reserve(size); }

    void resize(std::size_t size) { vec.resize(size); }

    void push_back(const T& value) { vec.push_back(value); }

    void push_back(T&& value) { vec.push_back(std::move(value)); }

    bool empty() { return vec.empty(); }

    auto begin() { return vec.begin(); }

    auto end() { return vec.end(); }

    auto begin() const { return vec.begin(); }

    auto end() const { return vec.end(); }

    template<typename... Args>
    void emplace_back(Args&&... args) {
        vec.emplace_back(std::forward<Args>(args)...);
    }

    void pop_back() { vec.pop_back(); }

    void clear() { vec.clear(); }

    std::size_t size() const { return vec.size(); }

    T& operator[](std::size_t index) { return vec[index]; }
    const T& operator[](std::size_t index) const { return vec[index]; }

    T& at(std::size_t index) { return vec.at(index); }
    const T& at(std::size_t index) const { return vec.at(index); }

private:
    std::vector<T, storage::MmAllocator<T>> vec;
};

// ObjectArraysMap represents a pre-allocated amount of object per tableID.
template<typename T>
class GDSDenseObjectManager {
public:
    void allocate(common::table_id_t tableID, common::offset_t maxOffset,
        storage::MemoryManager* mm) {
        auto buffer = mm->allocateBuffer(false, maxOffset * sizeof(T));
        bufferPerTable.insert({tableID, std::move(buffer)});
    }

    T* getData(common::table_id_t tableID) const {
        KU_ASSERT(bufferPerTable.contains(tableID));
        return reinterpret_cast<T*>(bufferPerTable.at(tableID)->getData());
    }

private:
    common::table_id_map_t<std::unique_ptr<storage::MemoryBuffer>> bufferPerTable;
};

template<typename T>
class GDSSpareObjectManager {
public:
    explicit GDSSpareObjectManager(
        const common::table_id_map_t<common::offset_t>& nodeMaxOffsetMap) {
        for (auto& [tableID, _] : nodeMaxOffsetMap) {
            allocate(tableID);
        }
    }

    void allocate(common::table_id_t tableID) {
        KU_ASSERT(!mapPerTable.contains(tableID));
        mapPerTable.insert({tableID, {}});
    }

    const common::table_id_map_t<std::unordered_map<common::offset_t, T>>& getData() {
        return mapPerTable;
    }

    std::unordered_map<common::offset_t, T>* getMap(common::table_id_t tableID) {
        KU_ASSERT(mapPerTable.contains(tableID));
        return &mapPerTable.at(tableID);
    }

    std::unordered_map<common::offset_t, T>* getData(common::table_id_t tableID) {
        if (!mapPerTable.contains(tableID)) {
            mapPerTable.insert({tableID, {}});
        }
        KU_ASSERT(mapPerTable.contains(tableID));
        return &mapPerTable.at(tableID);
    }

    uint64_t size() const {
        uint64_t result = 0;
        for (auto [_, map] : mapPerTable) {
            result += map.size();
        }
        return result;
    }

private:
    common::table_id_map_t<std::unordered_map<common::offset_t, T>> mapPerTable;
};

} // namespace function
} // namespace lbug
