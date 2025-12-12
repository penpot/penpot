#pragma once

#include <memory>
#include <string>

#include "common/api.h"
#include "common/serializer/writer.h"

namespace lbug {
namespace common {

static constexpr uint64_t SERIALIZER_DEFAULT_SIZE = 1024;

struct BinaryData {
    std::unique_ptr<uint8_t[]> data;
    uint64_t size = 0;
};

class LBUG_API BufferWriter : public Writer {
public:
    // Serializes to a buffer allocated by the serializer, will expand when
    // writing past the initial threshold.
    explicit BufferWriter(uint64_t maximumSize = SERIALIZER_DEFAULT_SIZE);

    // Retrieves the data after the writing has been completed.
    BinaryData getData() { return std::move(blob); }

    uint64_t getSize() const override { return blob.size; }

    uint8_t* getBlobData() const { return blob.data.get(); }

    void clear() override { blob.size = 0; }
    void flush() override {
        // DO NOTHING: BufferedWriter does not need to flush.
    }
    void sync() override {
        // DO NOTHING: BufferedWriter does not need to sync.
    }

    template<class T>
    void write(T element) {
        static_assert(std::is_trivially_destructible<T>(),
            "Write element must be trivially destructible");
        write(reinterpret_cast<const uint8_t*>(&element), sizeof(T));
    }

    void write(const uint8_t* buffer, uint64_t len) final;

    void writeBufferData(const std::string& str) {
        write(reinterpret_cast<const uint8_t*>(str.c_str()), str.size());
    }

    void writeBufferData(const char& ch) {
        write(reinterpret_cast<const uint8_t*>(&ch), sizeof(char));
    }

private:
    uint64_t maximumSize;
    uint8_t* data;

    BinaryData blob;
};

} // namespace common
} // namespace lbug
