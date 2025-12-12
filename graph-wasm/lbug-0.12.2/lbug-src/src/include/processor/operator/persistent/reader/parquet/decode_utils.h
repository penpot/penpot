#pragma once

#include "common/exception/copy.h"
#include "common/string_format.h"
#include "resizable_buffer.h"

namespace lbug {
namespace processor {
class ParquetDecodeUtils {

public:
    template<class T>
    static T ZigzagToInt(const T n) {
        return (n >> 1) ^ -(n & 1);
    }

    static const uint64_t BITPACK_MASKS[];
    static const uint64_t BITPACK_MASKS_SIZE;
    static const uint8_t BITPACK_DLEN;

    template<typename T>
    static uint32_t BitUnpack(ByteBuffer& buffer, uint8_t& bitpack_pos, T* dest, uint32_t count,
        uint8_t width) {
        if (width >= ParquetDecodeUtils::BITPACK_MASKS_SIZE) {
            throw common::CopyException(common::stringFormat(
                "The width ({}) of the bitpacked data exceeds the supported max width ({}), "
                "the file might be corrupted.",
                width, ParquetDecodeUtils::BITPACK_MASKS_SIZE));
        }
        auto mask = BITPACK_MASKS[width];

        for (uint32_t i = 0; i < count; i++) {
            T val = (buffer.get<uint8_t>() >> bitpack_pos) & mask;
            bitpack_pos += width;
            while (bitpack_pos > BITPACK_DLEN) {
                buffer.inc(1);
                val |= (T(buffer.get<uint8_t>()) << T(BITPACK_DLEN - (bitpack_pos - width))) & mask;
                bitpack_pos -= BITPACK_DLEN;
            }
            dest[i] = val;
        }
        return count;
    }

    template<class T>
    static T VarintDecode(ByteBuffer& buf) {
        T result = 0;
        uint8_t shift = 0;
        while (true) {
            auto byte = buf.read<uint8_t>();
            result |= T(byte & 127) << shift;
            if ((byte & 128) == 0) {
                break;
            }
            shift += 7;
            if (shift > sizeof(T) * 8) {
                throw std::runtime_error("Varint-decoding found too large number");
            }
        }
        return result;
    }
};

} // namespace processor
} // namespace lbug
