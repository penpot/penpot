#pragma once

#include <mutex>

#include "common/api.h"
#include "pcg_random.hpp"

namespace lbug {

namespace main {
class ClientContext;
}

namespace common {

struct RandomState {
    pcg32 pcg;

    RandomState() {}
};

class LBUG_API RandomEngine {
public:
    RandomEngine();
    RandomEngine(uint64_t seed, uint64_t stream);

    void setSeed(uint64_t seed);

    uint32_t nextRandomInteger();
    uint32_t nextRandomInteger(uint32_t upper);

    static RandomEngine* Get(const main::ClientContext& context);

private:
    std::mutex mtx;
    RandomState randomState;
};

} // namespace common
} // namespace lbug
