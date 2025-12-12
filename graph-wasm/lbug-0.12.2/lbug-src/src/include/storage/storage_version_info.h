#pragma once

#include <cstdint>
#include <string>
#include <unordered_map>

#include "common/api.h"

namespace lbug {
namespace storage {

using storage_version_t = uint64_t;

struct StorageVersionInfo {
    static std::unordered_map<std::string, storage_version_t> getStorageVersionInfo() {
        return {{"0.11.1", 39}, {"0.11.0", 39}, {"0.10.0", 38}, {"0.9.0", 37}, {"0.8.0", 36},
            {"0.7.1.1", 35}, {"0.7.0", 34}, {"0.6.0.6", 33}, {"0.6.0.5", 32}, {"0.6.0.2", 31},
            {"0.6.0.1", 31}, {"0.6.0", 28}, {"0.5.0", 28}, {"0.4.2", 27}, {"0.4.1", 27},
            {"0.4.0", 27}, {"0.3.2", 26}, {"0.3.1", 26}, {"0.3.0", 26}, {"0.2.1", 25},
            {"0.2.0", 25}, {"0.1.0", 24}, {"0.0.12.3", 24}, {"0.0.12.2", 24}, {"0.0.12.1", 24},
            {"0.0.12", 23}, {"0.0.11", 23}, {"0.0.10", 23}, {"0.0.9", 23}, {"0.0.8", 17},
            {"0.0.7", 15}, {"0.0.6", 9}, {"0.0.5", 8}, {"0.0.4", 7}, {"0.0.3", 1}};
    }

    static LBUG_API storage_version_t getStorageVersion();

    static constexpr const char* MAGIC_BYTES = "LBUG";
};

} // namespace storage
} // namespace lbug
