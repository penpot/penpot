#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <vector>

#include "common/assert.h"
#include "common/system_config.h"

namespace lbug {
namespace common {

template<typename T, uint64_t BLOCK_SIZE = DEFAULT_VECTOR_CAPACITY,
    uint64_t INDEX_SIZE = BLOCK_SIZE>
// Vector which doesn't move when resizing
// The initial size is fixed, and new elements are added in fixed sized blocks which are indexed and
// the indices are chained in a linked list. Currently only one thread can write concurrently, but
// any number of threads can read, even when the vector is being written to.
//
// Accessing elements which existed when the vector was created is as fast as possible, requiring
// just one comparison and one pointer reads, and accessing new elements is still reasonably fast,
// usually requiring reading just two pointers, with a small amount of arithmetic, or maybe more if
// an extremely large number of elements has been added
// (access cost increases every BLOCK_SIZE * INDEX_SIZE elements).
class ConcurrentVector {
public:
    explicit ConcurrentVector(uint64_t initialNumElements, uint64_t initialBlockSize)
        : numElements{initialNumElements}, initialBlock{std::make_unique<T[]>(initialBlockSize)},
          initialBlockSize{initialBlockSize}, firstIndex{nullptr} {}
    // resize never deallocates memory
    // Not thread-safe
    // It could be made to be thread-safe by storing the size atomically and doing compare and swap
    // when adding new indices and blocks
    void resize(uint64_t newSize) {
        while (newSize > initialBlockSize + blocks.size() * BLOCK_SIZE) {
            auto newBlock = std::make_unique<Block>();
            if (indices.empty()) {
                auto index = std::make_unique<BlockIndex>();
                index->blocks[0] = newBlock.get();
                index->numBlocks = 1;
                firstIndex = index.get();
                indices.push_back(std::move(index));
            } else if (indices.back()->numBlocks < INDEX_SIZE) {
                auto& index = indices.back();
                index->blocks[index->numBlocks] = newBlock.get();
                index->numBlocks++;
            } else {
                KU_ASSERT(indices.back()->numBlocks == INDEX_SIZE);
                auto index = std::make_unique<BlockIndex>();
                index->blocks[0] = newBlock.get();
                index->numBlocks = 1;
                indices.back()->nextIndex = index.get();
                indices.push_back(std::move(index));
            }
            blocks.push_back(std::move(newBlock));
        }
        numElements = newSize;
    }

    void push_back(T&& value) {
        auto index = numElements;
        resize(numElements + 1);
        (*this)[index] = std::move(value);
    }

    T& operator[](uint64_t elemPos) {
        if (elemPos < initialBlockSize) {
            KU_ASSERT(initialBlock);
            return initialBlock[elemPos];
        } else {
            auto blockNum = (elemPos - initialBlockSize) / BLOCK_SIZE;
            auto posInBlock = (elemPos - initialBlockSize) % BLOCK_SIZE;
            auto indexNum = blockNum / INDEX_SIZE;
            BlockIndex* index = firstIndex;
            KU_ASSERT(index != nullptr);
            while (indexNum > 0) {
                KU_ASSERT(index->nextIndex != nullptr);
                index = index->nextIndex;
                indexNum--;
            }
            KU_ASSERT(index->blocks[blockNum % INDEX_SIZE] != nullptr);
            return index->blocks[blockNum % INDEX_SIZE]->data[posInBlock];
        }
    }

    uint64_t size() { return numElements; }

private:
    uint64_t numElements;
    std::unique_ptr<T[]> initialBlock;
    uint64_t initialBlockSize;
    struct Block {
        std::array<T, BLOCK_SIZE> data;
    };
    struct BlockIndex {
        BlockIndex() : nextIndex{nullptr}, blocks{}, numBlocks{0} {}
        BlockIndex* nextIndex;
        std::array<Block*, INDEX_SIZE> blocks;
        uint64_t numBlocks;
    };
    BlockIndex* firstIndex;
    std::vector<std::unique_ptr<Block>> blocks;
    std::vector<std::unique_ptr<BlockIndex>> indices;
};

} // namespace common
} // namespace lbug
