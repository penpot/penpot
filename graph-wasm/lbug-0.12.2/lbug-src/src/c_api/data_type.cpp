#include "c_api/lbug.h"
#include "common/types/types.h"

using namespace lbug::common;

namespace lbug::common {
struct CAPIHelper {
    static inline LogicalType* createLogicalType(LogicalTypeID typeID,
        std::unique_ptr<ExtraTypeInfo> extraTypeInfo) {
        return new LogicalType(typeID, std::move(extraTypeInfo));
    }
};
} // namespace lbug::common

void lbug_data_type_create(lbug_data_type_id id, lbug_logical_type* child_type,
    uint64_t num_elements_in_array, lbug_logical_type* out_data_type) {
    uint8_t data_type_id_u8 = id;
    LogicalType* data_type = nullptr;
    auto logicalTypeID = static_cast<LogicalTypeID>(data_type_id_u8);
    if (child_type == nullptr) {
        data_type = new LogicalType(logicalTypeID);
    } else {
        auto child_type_pty = static_cast<LogicalType*>(child_type->_data_type)->copy();
        auto extraTypeInfo =
            num_elements_in_array > 0 ?
                std::make_unique<ArrayTypeInfo>(std::move(child_type_pty), num_elements_in_array) :
                std::make_unique<ListTypeInfo>(std::move(child_type_pty));
        data_type = CAPIHelper::createLogicalType(logicalTypeID, std::move(extraTypeInfo));
    }
    out_data_type->_data_type = data_type;
}

void lbug_data_type_clone(lbug_logical_type* data_type, lbug_logical_type* out_data_type) {
    out_data_type->_data_type =
        new LogicalType(static_cast<LogicalType*>(data_type->_data_type)->copy());
}

void lbug_data_type_destroy(lbug_logical_type* data_type) {
    if (data_type == nullptr) {
        return;
    }
    if (data_type->_data_type != nullptr) {
        delete static_cast<LogicalType*>(data_type->_data_type);
    }
}

bool lbug_data_type_equals(lbug_logical_type* data_type1, lbug_logical_type* data_type2) {
    return *static_cast<LogicalType*>(data_type1->_data_type) ==
           *static_cast<LogicalType*>(data_type2->_data_type);
}

lbug_data_type_id lbug_data_type_get_id(lbug_logical_type* data_type) {
    auto data_type_id_u8 =
        static_cast<uint8_t>(static_cast<LogicalType*>(data_type->_data_type)->getLogicalTypeID());
    return static_cast<lbug_data_type_id>(data_type_id_u8);
}

lbug_state lbug_data_type_get_num_elements_in_array(lbug_logical_type* data_type,
    uint64_t* out_result) {
    auto parent_type = static_cast<LogicalType*>(data_type->_data_type);
    if (parent_type->getLogicalTypeID() != LogicalTypeID::ARRAY) {
        return LbugError;
    }
    try {
        *out_result = ArrayType::getNumElements(*parent_type);
    } catch (Exception& e) {
        return LbugError;
    }
    return LbugSuccess;
}
