#pragma once

#include <memory>
#include <optional>

#include "common/serializer/deserializer.h"
#include "common/serializer/reader.h"
#include "storage/buffer_manager/memory_manager.h"

namespace lbug {
namespace storage {
class ChecksumReader : public common::Reader {
public:
    explicit ChecksumReader(common::FileInfo& fileInfo, MemoryManager& memoryManager,
        std::string_view checksumMismatchMessage);

    void read(uint8_t* data, uint64_t size) override;
    bool finished() override;

    void onObjectBegin() override;
    // Reads the stored checksum
    // Also computes + verifies the checksum for the entry that has just been read against the
    // stored value
    void onObjectEnd() override;

    uint64_t getReadOffset() const;

private:
    common::Deserializer deserializer;

    std::optional<uint64_t> currentEntrySize;
    std::unique_ptr<MemoryBuffer> entryBuffer;

    std::string_view checksumMismatchMessage;
};
} // namespace storage
} // namespace lbug
