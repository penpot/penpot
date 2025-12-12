#pragma once

#include <memory>
#include <optional>

#include "common/serializer/serializer.h"
#include "common/serializer/writer.h"
#include "storage/buffer_manager/memory_manager.h"

namespace lbug {
namespace storage {
class ChecksumWriter;

// A wrapper on top of another Writer that accumulates serialized data
// Then flushes that data (along with a computed checksum) when the data has completed serializing
class ChecksumWriter : public common::Writer {
public:
    explicit ChecksumWriter(std::shared_ptr<common::Writer> outputWriter,
        MemoryManager& memoryManager);

    void write(const uint8_t* data, uint64_t size) override;
    uint64_t getSize() const override;

    void clear() override;
    void sync() override;

    void flush() override;

    void onObjectBegin() override;
    // Calculate checksum + write the checksum + serialized contents to underlying writer
    void onObjectEnd() override;

private:
    common::Serializer outputSerializer;
    std::optional<uint64_t> currentEntrySize;
    std::unique_ptr<MemoryBuffer> entryBuffer;
};

} // namespace storage
} // namespace lbug
