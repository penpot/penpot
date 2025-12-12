#include "common/types/value/node.h"

#include "common/constants.h"
#include "common/string_format.h"
#include "common/types/types.h"
#include "common/types/value/value.h"

namespace lbug {
namespace common {

std::vector<std::pair<std::string, std::unique_ptr<Value>>> NodeVal::getProperties(
    const Value* val) {
    throwIfNotNode(val);
    std::vector<std::pair<std::string, std::unique_ptr<Value>>> properties;
    auto fieldNames = StructType::getFieldNames(val->dataType);
    for (auto i = 0u; i < val->childrenSize; ++i) {
        auto currKey = fieldNames[i];
        if (currKey == InternalKeyword::ID || currKey == InternalKeyword::LABEL) {
            continue;
        }
        properties.emplace_back(currKey, val->children[i]->copy());
    }
    return properties;
}

uint64_t NodeVal::getNumProperties(const Value* val) {
    throwIfNotNode(val);
    auto fieldNames = StructType::getFieldNames(val->dataType);
    return fieldNames.size() - OFFSET;
}

std::string NodeVal::getPropertyName(const Value* val, uint64_t index) {
    throwIfNotNode(val);
    auto fieldNames = StructType::getFieldNames(val->dataType);
    if (index >= fieldNames.size() - OFFSET) {
        return "";
    }
    return fieldNames[index + OFFSET];
}

Value* NodeVal::getPropertyVal(const Value* val, uint64_t index) {
    throwIfNotNode(val);
    auto fieldNames = StructType::getFieldNames(val->dataType);
    if (index >= fieldNames.size() - OFFSET) {
        return nullptr;
    }
    return val->children[index + OFFSET].get();
}

Value* NodeVal::getNodeIDVal(const Value* val) {
    throwIfNotNode(val);
    auto fieldIdx = StructType::getFieldIdx(val->dataType, InternalKeyword::ID);
    return val->children[fieldIdx].get();
}

Value* NodeVal::getLabelVal(const Value* val) {
    throwIfNotNode(val);
    auto fieldIdx = StructType::getFieldIdx(val->dataType, InternalKeyword::LABEL);
    return val->children[fieldIdx].get();
}

std::string NodeVal::toString(const Value* val) {
    throwIfNotNode(val);
    return val->toString();
}

void NodeVal::throwIfNotNode(const Value* val) {
    // LCOV_EXCL_START
    if (val->dataType.getLogicalTypeID() != LogicalTypeID::NODE) {
        throw Exception(
            stringFormat("Expected NODE type, but got {} type", val->dataType.toString()));
    }
    // LCOV_EXCL_STOP
}

} // namespace common
} // namespace lbug
