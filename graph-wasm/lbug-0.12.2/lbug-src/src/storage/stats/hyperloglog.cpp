#include "storage/stats/hyperloglog.h"

#include <math.h>

#include "common/serializer/deserializer.h"
#include "common/serializer/serializer.h"

namespace lbug {
namespace storage {

common::cardinality_t HyperLogLog::count() const {
    uint32_t c[Q + 2] = {0};
    extractCounts(c);
    return static_cast<common::cardinality_t>(estimateCardinality(c));
}

void HyperLogLog::merge(const HyperLogLog& other) {
    for (auto i = 0u; i < M; ++i) {
        update(i, other.k[i]);
    }
}

void HyperLogLog::extractCounts(uint32_t* c) const {
    for (auto i = 0u; i < M; ++i) {
        c[k[i]]++;
    }
}

//! Taken from redis code
static double HLLSigma(double x) {
    if (x == 1.) {
        return std::numeric_limits<double>::infinity();
    }
    double z_prime = NAN;
    double y = 1;
    double z = x;
    do {
        x *= x;
        z_prime = z;
        z += x * y;
        y += y;
    } while (z_prime != z);
    return z;
}

//! Taken from redis code
static double HLLTau(double x) {
    if (x == 0. || x == 1.) {
        return 0.;
    }
    double z_prime = NAN;
    double y = 1.0;
    double z = 1 - x;
    do {
        x = sqrt(x);
        z_prime = z;
        y *= 0.5;
        z -= pow(1 - x, 2) * y;
    } while (z_prime != z);
    return z / 3;
}

int64_t HyperLogLog::estimateCardinality(const uint32_t* c) {
    auto z = M * HLLTau((static_cast<double>(M) - c[Q]) / static_cast<double>(M));

    for (auto k = Q; k >= 1; --k) {
        z += c[k];
        z *= 0.5;
    }

    z += M * HLLSigma(c[0] / static_cast<double>(M));

    return llroundl(ALPHA * M * M / z);
}

void HyperLogLog::serialize(common::Serializer& serializer) const {
    serializer.writeDebuggingInfo("hll_data");
    serializer.serializeArray<uint8_t, M>(k);
}

HyperLogLog HyperLogLog::deserialize(common::Deserializer& deserializer) {
    HyperLogLog result;
    std::string info;
    deserializer.validateDebuggingInfo(info, "hll_data");
    deserializer.deserializeArray<uint8_t, M>(result.k);
    return result;
}

} // namespace storage
} // namespace lbug
