#pragma once

#include "decode_utils.h"
#include "resizable_buffer.h"

namespace lbug {
namespace processor {

class RleBpDecoder {
public:
    /// Create a decoder object. buffer/buffer_len is the decoded data.
    /// bit_width is the width of each value (before encoding).
    RleBpDecoder(uint8_t* buffer, uint32_t buffer_len, uint32_t bit_width)
        : buffer_(buffer, buffer_len), bit_width_(bit_width), current_value_(0), repeat_count_(0),
          literal_count_(0) {
        if (bit_width >= 64) {
            throw std::runtime_error("Decode bit width too large");
        }
        byte_encoded_len = ((bit_width_ + 7) / 8);
        max_val = (uint64_t(1) << bit_width_) - 1;
    }

    template<typename T>
    void GetBatch(uint8_t* values_target_ptr, uint32_t batch_size) {
        auto* values = reinterpret_cast<T*>(values_target_ptr);
        uint32_t values_read = 0;

        while (values_read < batch_size) {
            if (repeat_count_ > 0) {
                int repeat_batch = std::min<uint32_t>(batch_size - values_read,
                    static_cast<uint32_t>(repeat_count_));
                std::fill(values + values_read, values + values_read + repeat_batch,
                    static_cast<T>(current_value_));
                repeat_count_ -= repeat_batch;
                values_read += repeat_batch;
            } else if (literal_count_ > 0) {
                uint32_t literal_batch = std::min<uint32_t>(batch_size - values_read,
                    static_cast<uint32_t>(literal_count_));
                uint32_t actual_read = ParquetDecodeUtils::BitUnpack<T>(buffer_, bitpack_pos,
                    values + values_read, literal_batch, bit_width_);
                if (literal_batch != actual_read) {
                    throw std::runtime_error("Did not find enough values");
                }
                literal_count_ -= literal_batch;
                values_read += literal_batch;
            } else {
                if (!NextCounts<T>()) {
                    if (values_read != batch_size) {
                        throw std::runtime_error("RLE decode did not find enough values");
                    }
                    return;
                }
            }
        }
        if (values_read != batch_size) {
            throw std::runtime_error("RLE decode did not find enough values");
        }
    }

    static uint8_t ComputeBitWidth(uint64_t val) {
        if (val == 0) {
            return 0;
        }
        uint8_t ret = 1;
        while (((uint64_t)(1u << ret) - 1) < val) {
            ret++;
        }
        return ret;
    }

private:
    ByteBuffer buffer_;

    /// Number of bits needed to encode the value. Must be between 0 and 64.
    uint32_t bit_width_;
    uint64_t current_value_;
    uint32_t repeat_count_;
    uint32_t literal_count_;
    uint8_t byte_encoded_len;
    uint64_t max_val;

    uint8_t bitpack_pos = 0;

    /// Fills literal_count_ and repeat_count_ with next values. Returns false if there
    /// are no more.
    template<typename T>
    bool NextCounts() {
        // Read the next run's indicator int, it could be a literal or repeated run.
        // The int is encoded as a vlq-encoded value.
        if (bitpack_pos != 0) {
            buffer_.inc(1);
            bitpack_pos = 0;
        }
        auto indicator_value = ParquetDecodeUtils::VarintDecode<uint32_t>(buffer_);

        // lsb indicates if it is a literal run or repeated run
        bool is_literal = indicator_value & 1;
        if (is_literal) {
            literal_count_ = (indicator_value >> 1) * 8;
        } else {
            repeat_count_ = indicator_value >> 1;
            // (ARROW-4018) this is not big-endian compatible, lol
            current_value_ = 0;
            for (auto i = 0; i < byte_encoded_len; i++) {
                current_value_ |= (buffer_.read<uint8_t>() << (i * 8));
            }
            // sanity check
            if (repeat_count_ > 0 && current_value_ > max_val) {
                throw std::runtime_error("Payload value bigger than allowed. Corrupted file?");
            }
        }
        // TODO complain if we run out of buffer
        return true;
    }
};

} // namespace processor
} // namespace lbug
