#pragma once

#include <cstdint>

namespace lbug {
namespace common {

struct ArrowResultConfig {
    int64_t chunkSize;

    ArrowResultConfig() : chunkSize(DEFAULT_CHUNK_SIZE) {}
    explicit ArrowResultConfig(int64_t chunkSize) : chunkSize(chunkSize) {}

private:
    static constexpr int64_t DEFAULT_CHUNK_SIZE = 1000;
};

} // namespace common
} // namespace lbug
