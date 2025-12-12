#include "common/types/value/rel.h"

#include "common/constants.h"
#include "common/string_format.h"
#include "common/types/value/value.h"

namespace lbug {
namespace common {

std::vector<std::pair<std::string, std::unique_ptr<Value>>> RelVal::getProperties(
    const Value* val) {
    throwIfNotRel(val);
    std::vector<std::pair<std::string, std::unique_ptr<Value>>> properties;
    auto fieldNames = StructType::getFieldNames(val->dataType);
    for (auto i = 0u; i < val->childrenSize; ++i) {
        auto currKey = fieldNames[i];
        if (currKey == InternalKeyword::ID || currKey == InternalKeyword::LABEL ||
            currKey == InternalKeyword::SRC || currKey == InternalKeyword::DST) {
            continue;
        }
        auto currVal = val->children[i]->copy();
        properties.emplace_back(currKey, std::move(currVal));
    }
    return properties;
}

uint64_t RelVal::getNumProperties(const Value* val) {
    throwIfNotRel(val);
    auto fieldNames = StructType::getFieldNames(val->dataType);
    return fieldNames.size() - OFFSET;
}

std::string RelVal::getPropertyName(const Value* val, uint64_t index) {
    throwIfNotRel(val);
    auto fieldNames = StructType::getFieldNames(val->dataType);
    if (index >= fieldNames.size() - OFFSET) {
        return "";
    }
    return fieldNames[index + OFFSET];
}

Value* RelVal::getPropertyVal(const Value* val, uint64_t index) {
    throwIfNotRel(val);
    auto fieldNames = StructType::getFieldNames(val->dataType);
    if (index >= fieldNames.size() - OFFSET) {
        return nullptr;
    }
    return val->children[index + OFFSET].get();
}

Value* RelVal::getIDVal(const Value* val) {
    auto fieldIdx = StructType::getFieldIdx(val->dataType, InternalKeyword::ID);
    return val->children[fieldIdx].get();
}

Value* RelVal::getSrcNodeIDVal(const Value* val) {
    auto fieldIdx = StructType::getFieldIdx(val->dataType, InternalKeyword::SRC);
    return val->children[fieldIdx].get();
}

Value* RelVal::getDstNodeIDVal(const Value* val) {
    auto fieldIdx = StructType::getFieldIdx(val->dataType, InternalKeyword::DST);
    return val->children[fieldIdx].get();
}

Value* RelVal::getLabelVal(const Value* val) {
    auto fieldIdx = StructType::getFieldIdx(val->dataType, InternalKeyword::LABEL);
    return val->children[fieldIdx].get();
}

std::string RelVal::toString(const Value* val) {
    throwIfNotRel(val);
    return val->toString();
}

void RelVal::throwIfNotRel(const Value* val) {
    // LCOV_EXCL_START
    if (val->dataType.getLogicalTypeID() != LogicalTypeID::REL) {
        throw Exception(
            stringFormat("Expected REL type, but got {} type", val->dataType.toString()));
    }
    // LCOV_EXCL_STOP
}

} // namespace common
} // namespace lbug
