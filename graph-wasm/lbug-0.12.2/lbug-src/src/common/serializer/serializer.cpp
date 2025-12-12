#include "common/serializer/serializer.h"

#include "common/assert.h"

namespace lbug {
namespace common {

template<>
void Serializer::serializeValue(const std::string& value) {
    uint64_t valueLength = value.length();
    writer->write((uint8_t*)&valueLength, sizeof(uint64_t));
    writer->write((uint8_t*)value.data(), valueLength);
}

void Serializer::writeDebuggingInfo(const std::string& value) {
#if defined(LBUG_DESER_DEBUG) && (defined(LBUG_RUNTIME_CHECKS) || !defined(NDEBUG))
    serializeValue<std::string>(value);
#endif
    // DO NOTHING
    KU_UNUSED(value);
}

} // namespace common
} // namespace lbug
