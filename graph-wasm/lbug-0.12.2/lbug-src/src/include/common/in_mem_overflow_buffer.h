#pragma once

#include <iterator>
#include <memory>
#include <vector>

#include "common/api.h"
#include "common/copy_constructors.h"

namespace lbug {
namespace storage {
class MemoryBuffer;
class MemoryManager;
} // namespace storage

namespace common {

struct LBUG_API BufferBlock {
public:
    explicit BufferBlock(std::unique_ptr<storage::MemoryBuffer> block);
    ~BufferBlock();

    uint64_t size() const;
    uint8_t* data() const;

public:
    uint64_t currentOffset;
    std::unique_ptr<storage::MemoryBuffer> block;

    void resetCurrentOffset() { currentOffset = 0; }
};

class LBUG_API InMemOverflowBuffer {

public:
    explicit InMemOverflowBuffer(storage::MemoryManager* memoryManager)
        : memoryManager{memoryManager} {};

    DEFAULT_BOTH_MOVE(InMemOverflowBuffer);

    uint8_t* allocateSpace(uint64_t size);

    void merge(InMemOverflowBuffer& other) {
        move(begin(other.blocks), end(other.blocks), back_inserter(blocks));
        // We clear the other InMemOverflowBuffer's block because when it is deconstructed,
        // InMemOverflowBuffer's deconstructed tries to free these pages by calling
        // memoryManager->freeBlock, but it should not because this InMemOverflowBuffer still
        // needs them.
        other.blocks.clear();
    }

    // Releases all memory accumulated for string overflows so far and re-initializes its state to
    // an empty buffer. If there is a large string that used point to any of these overflow buffers
    // they will error.
    void resetBuffer();

    // Manually set the underlying memory buffer to evicted to avoid double free
    void preventDestruction();

    storage::MemoryManager* getMemoryManager() { return memoryManager; }

private:
    bool requireNewBlock(uint64_t sizeToAllocate) {
        return blocks.empty() ||
               (currentBlock()->currentOffset + sizeToAllocate) > currentBlock()->size();
    }

    void allocateNewBlock(uint64_t size);

    BufferBlock* currentBlock() { return blocks.back().get(); }

private:
    std::vector<std::unique_ptr<BufferBlock>> blocks;
    storage::MemoryManager* memoryManager;
};

} // namespace common
} // namespace lbug
