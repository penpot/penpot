#pragma once
#include <cstdint>

#include "common/api.h"
namespace lbug {
namespace main {

struct Version {
public:
    /**
     * @brief Get the version of the Lbug library.
     * @return const char* The version of the Lbug library.
     */
    LBUG_API static const char* getVersion();

    /**
     * @brief Get the storage version of the Lbug library.
     * @return uint64_t The storage version of the Lbug library.
     */
    LBUG_API static uint64_t getStorageVersion();
};
} // namespace main
} // namespace lbug
