#include "common/serializer/buffer_writer.h"

#include <cstring>

namespace lbug {
namespace common {

BufferWriter::BufferWriter(uint64_t maximumSize) : maximumSize(maximumSize) {
    blob.data = std::make_unique<uint8_t[]>(maximumSize);
    blob.size = 0;
    data = blob.data.get();
}

void BufferWriter::write(const uint8_t* buffer, uint64_t len) {
    if (blob.size + len >= maximumSize) {
        do {
            maximumSize *= 2;
        } while (blob.size + len > maximumSize);
        auto new_data = std::make_unique<uint8_t[]>(maximumSize);
        memcpy(new_data.get(), data, blob.size);
        data = new_data.get();
        blob.data = std::move(new_data);
    }

    memcpy(data + blob.size, buffer, len);
    blob.size += len;
}

} // namespace common
} // namespace lbug
