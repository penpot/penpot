#include "common/utils.h"

namespace lbug {
namespace common {

uint64_t nextPowerOfTwo(uint64_t v) {
    v--;
    v |= v >> 1;
    v |= v >> 2;
    v |= v >> 4;
    v |= v >> 8;
    v |= v >> 16;
    v |= v >> 32;
    v++;
    return v;
}

uint64_t prevPowerOfTwo(uint64_t v) {
    return nextPowerOfTwo((v / 2) + 1);
}

bool isLittleEndian() {
    // Little endian arch stores the least significant value in the lower bytes.
    int testNumber = 1;
    return *(uint8_t*)&testNumber == 1;
}

} // namespace common
} // namespace lbug
