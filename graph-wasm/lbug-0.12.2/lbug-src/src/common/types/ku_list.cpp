#include "common/types/ku_list.h"

#include <cstring>

#include "storage/storage_utils.h"

namespace lbug {
namespace common {

void ku_list_t::set(const uint8_t* values, const LogicalType& dataType) const {
    memcpy(reinterpret_cast<uint8_t*>(overflowPtr), values,
        size * storage::StorageUtils::getDataTypeSize(ListType::getChildType(dataType)));
}

void ku_list_t::set(const std::vector<uint8_t*>& parameters, LogicalTypeID childTypeId) {
    this->size = parameters.size();
    auto numBytesOfListElement = storage::StorageUtils::getDataTypeSize(LogicalType{childTypeId});
    for (auto i = 0u; i < parameters.size(); i++) {
        memcpy(reinterpret_cast<uint8_t*>(this->overflowPtr) + (i * numBytesOfListElement),
            parameters[i], numBytesOfListElement);
    }
}

} // namespace common
} // namespace lbug
