#include "common/in_mem_overflow_buffer.h"

#include "common/system_config.h"
#include "storage/buffer_manager/memory_manager.h"
#include <bit>

using namespace lbug::storage;

namespace lbug {
namespace common {

BufferBlock::BufferBlock(std::unique_ptr<storage::MemoryBuffer> block)
    : currentOffset{0}, block{std::move(block)} {}

BufferBlock::~BufferBlock() = default;

uint64_t BufferBlock::size() const {
    return block->getBuffer().size();
}

uint8_t* BufferBlock::data() const {
    return block->getBuffer().data();
}

uint8_t* InMemOverflowBuffer::allocateSpace(uint64_t size) {
    if (requireNewBlock(size)) {
        if (!blocks.empty() && currentBlock()->currentOffset == 0) {
            blocks.pop_back();
        }
        allocateNewBlock(size);
    }
    auto data = currentBlock()->data() + currentBlock()->currentOffset;
    currentBlock()->currentOffset += size;
    return data;
}

void InMemOverflowBuffer::resetBuffer() {
    if (!blocks.empty()) {
        // Last block is usually the largest
        auto lastBlock = std::move(blocks.back());
        blocks.clear();
        lastBlock->resetCurrentOffset();
        blocks.push_back(std::move(lastBlock));
    }
}

void InMemOverflowBuffer::preventDestruction() {
    for (auto& block : blocks) {
        block->block->preventDestruction();
    }
}

void InMemOverflowBuffer::allocateNewBlock(uint64_t size) {
    std::unique_ptr<BufferBlock> newBlock;
    if (blocks.empty()) {
        newBlock = make_unique<BufferBlock>(
            memoryManager->allocateBuffer(false /* do not initialize to zero */, size));
    } else {
        // Use the doubling strategy so that the initial allocations are small, but if we need many
        // allocations they approach the TEMP_PAGE_SIZE quickly
        auto min = std::min(TEMP_PAGE_SIZE, std::bit_ceil(currentBlock()->size() * 2));
        newBlock = make_unique<BufferBlock>(memoryManager->allocateBuffer(
            false /* do not initialize to zero */, std::max(min, size)));
    }
    blocks.push_back(std::move(newBlock));
}

} // namespace common
} // namespace lbug
