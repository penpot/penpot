#include "function/cast/vector_cast_functions.h"

#include "binder/expression/expression_util.h"
#include "binder/expression/literal_expression.h"
#include "catalog/catalog.h"
#include "common/exception/binder.h"
#include "common/exception/conversion.h"
#include "function/built_in_function_utils.h"
#include "function/cast/cast_union_bind_data.h"
#include "function/cast/functions/cast_array.h"
#include "function/cast/functions/cast_decimal.h"
#include "function/cast/functions/cast_from_string_functions.h"
#include "function/cast/functions/cast_functions.h"
#include "transaction/transaction.h"

using namespace lbug::common;
using namespace lbug::binder;

namespace lbug {
namespace function {

struct CastChildFunctionExecutor {
    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC, typename OP_WRAPPER>
    static void executeSwitch(common::ValueVector& operand, common::SelectionVector*,
        common::ValueVector& result, common::SelectionVector*, void* dataPtr) {
        auto& bindData = *reinterpret_cast<CastFunctionBindData*>(dataPtr);
        for (auto i = 0u; i < bindData.numOfEntries; i++) {
            result.setNull(i, operand.isNull(i));
            if (!result.isNull(i)) {
                OP_WRAPPER::template operation<OPERAND_TYPE, RESULT_TYPE, FUNC>((void*)(&operand),
                    i, (void*)(&result), i, dataPtr);
            }
        }
    }
};

static union_field_idx_t findUnionMinCostTag(const LogicalType&, const LogicalType&);

static void resolveNestedVector(std::shared_ptr<ValueVector> inputVector, ValueVector* resultVector,
    uint64_t numOfEntries, CastFunctionBindData* dataPtr) {
    const auto* inputType = &inputVector->dataType;
    const auto* resultType = &resultVector->dataType;
    while (true) {
        if ((inputType->getPhysicalType() == PhysicalTypeID::LIST ||
                inputType->getPhysicalType() == PhysicalTypeID::ARRAY) &&
            (resultType->getPhysicalType() == PhysicalTypeID::LIST ||
                resultType->getPhysicalType() == PhysicalTypeID::ARRAY)) {
            // copy data and nullmask from input
            memcpy(resultVector->getData(), inputVector->getData(),
                numOfEntries * resultVector->getNumBytesPerValue());
            resultVector->setNullFromBits(inputVector->getNullMask().getData(), 0, 0, numOfEntries);

            numOfEntries = ListVector::getDataVectorSize(inputVector.get());
            ListVector::resizeDataVector(resultVector, numOfEntries);

            inputVector = ListVector::getSharedDataVector(inputVector.get());
            resultVector = ListVector::getDataVector(resultVector);
            inputType = &inputVector->dataType;
            resultType = &resultVector->dataType;
        } else if ((inputType->getLogicalTypeID() == LogicalTypeID::STRUCT &&
                       resultType->getLogicalTypeID() == LogicalTypeID::STRUCT) ||
                   CastArrayHelper::isUnionSpecialCast(*inputType, *resultType)) {
            // Check if struct type can be cast
            auto errorMsg = stringFormat("Unsupported casting function from {} to {}.",
                inputType->toString(), resultType->toString());
            // Check if two structs have the same number of fields
            if (StructType::getNumFields(*inputType) != StructType::getNumFields(*resultType)) {
                throw ConversionException{errorMsg};
            }

            // Check if two structs have the same field names
            auto inputTypeNames = StructType::getFieldNames(*inputType);
            auto resultTypeNames = StructType::getFieldNames(*resultType);

            for (auto i = 0u; i < inputTypeNames.size(); i++) {
                if (StringUtils::caseInsensitiveEquals(inputTypeNames[i], resultTypeNames[i])) {
                    continue;
                }
                throw ConversionException{errorMsg};
            }

            // copy data and nullmask from input
            memcpy(resultVector->getData(), inputVector->getData(),
                numOfEntries * resultVector->getNumBytesPerValue());
            resultVector->setNullFromBits(inputVector->getNullMask().getData(), 0, 0, numOfEntries);

            auto inputFieldVectors = StructVector::getFieldVectors(inputVector.get());
            auto resultFieldVectors = StructVector::getFieldVectors(resultVector);
            for (auto i = 0u; i < inputFieldVectors.size(); i++) {
                resolveNestedVector(inputFieldVectors[i], resultFieldVectors[i].get(), numOfEntries,
                    dataPtr);
            }
            return;
        } else if (resultType->getLogicalTypeID() == LogicalTypeID::UNION) {
            if (inputType->getLogicalTypeID() == LogicalTypeID::UNION) {
                auto numFieldsSrc = UnionType::getNumFields(*inputType);
                std::vector<union_field_idx_t> tagMap(numFieldsSrc);
                for (auto i = 0u; i < numFieldsSrc; ++i) {
                    const auto& fieldName = UnionType::getFieldName(*inputType, i);
                    if (!UnionType::hasField(*resultType, fieldName)) {
                        throw ConversionException{stringFormat(
                            "Cannot cast from {} to {}, target type is missing field '{}'.",
                            inputType->toString(), resultType->toString(), fieldName)};
                    }
                    const auto& fieldTypeSrc = UnionType::getFieldType(*inputType, i);
                    const auto& fieldTypeDst = UnionType::getFieldType(*resultType, fieldName);
                    if (!CastFunction::hasImplicitCast(fieldTypeSrc, fieldTypeDst)) {
                        throw ConversionException{
                            stringFormat("Unsupported casting function from {} to {}.",
                                fieldTypeSrc.toString(), fieldTypeDst.toString())};
                    }
                    auto dstTag = UnionType::getFieldIdx(*resultType, fieldName);
                    tagMap[i] = dstTag;
                    auto srcValVector = UnionVector::getSharedValVector(inputVector.get(), i);
                    auto resValVector = UnionVector::getValVector(resultVector, dstTag);
                    resolveNestedVector(srcValVector, resValVector, numOfEntries, dataPtr);
                }
                auto srcTagVector = UnionVector::getTagVector(inputVector.get());
                auto resTagVector = UnionVector::getTagVector(resultVector);
                for (auto i = 0u; i < numOfEntries; ++i) {
                    auto srcTag = srcTagVector->getValue<union_field_idx_t>(i);
                    resTagVector->setValue(i, tagMap[srcTag]);
                }
                return;
            } else {
                auto minCostTag = findUnionMinCostTag(*inputType, *resultType);
                auto tagVector = UnionVector::getTagVector(resultVector);
                for (auto i = 0u; i < numOfEntries; ++i) {
                    tagVector->setValue(i, minCostTag);
                }
                resultVector = UnionVector::getValVector(resultVector, minCostTag);
                resultType = &UnionType::getFieldType(*resultType, minCostTag);
            }
        } else {
            break;
        }
    }
    // non-nested types
    if (inputType->getLogicalTypeID() != resultType->getLogicalTypeID()) {
        auto func = CastFunction::bindCastFunction<CastChildFunctionExecutor>("CAST", *inputType,
            *resultType)
                        ->execFunc;
        std::vector<std::shared_ptr<ValueVector>> childParams{inputVector};
        dataPtr->numOfEntries = numOfEntries;
        func(childParams, SelectionVector::fromValueVectors(childParams), *resultVector,
            resultVector->getSelVectorPtr(), (void*)dataPtr);
    } else {
        for (auto i = 0u; i < numOfEntries; i++) {
            resultVector->copyFromVectorData(i, inputVector.get(), i);
        }
    }
}

static void nestedTypesCastExecFunction(
    const std::vector<std::shared_ptr<common::ValueVector>>& params,
    const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
    common::SelectionVector* resultSelVector, void*) {
    KU_ASSERT(params.size() == 1);
    result.resetAuxiliaryBuffer();
    const auto& inputVector = params[0];
    const auto* inputVectorSelVector = paramSelVectors[0];

    // check if all selected list entries have the required fixed list size
    if (CastArrayHelper::containsListToArray(inputVector->dataType, result.dataType)) {
        for (auto i = 0u; i < inputVectorSelVector->getSelSize(); i++) {
            auto pos = (*inputVectorSelVector)[i];
            CastArrayHelper::validateListEntry(inputVector.get(), result.dataType, pos);
        }
    };

    auto& selVector = *inputVectorSelVector;
    auto bindData = CastFunctionBindData(result.dataType.copy());
    bindData.numOfEntries = selVector[selVector.getSelSize() - 1] + 1;
    resolveNestedVector(inputVector, &result, bindData.numOfEntries, &bindData);
    if (inputVector->state->isFlat()) {
        resultSelVector->setToFiltered();
        (*resultSelVector)[0] = (*inputVectorSelVector)[0];
    }
}

static bool hasImplicitCastList(const LogicalType& srcType, const LogicalType& dstType) {
    return CastFunction::hasImplicitCast(ListType::getChildType(srcType),
        ListType::getChildType(dstType));
}

static bool hasImplicitCastArray(const LogicalType& srcType, const LogicalType& dstType) {
    if (ArrayType::getNumElements(srcType) != ArrayType::getNumElements(dstType)) {
        return false;
    }
    return CastFunction::hasImplicitCast(ArrayType::getChildType(srcType),
        ArrayType::getChildType(dstType));
}

static bool hasImplicitCastArrayToList(const LogicalType& srcType, const LogicalType& dstType) {
    return CastFunction::hasImplicitCast(ArrayType::getChildType(srcType),
        ListType::getChildType(dstType));
}

static bool hasImplicitCastListToArray(const LogicalType& srcType, const LogicalType& dstType) {
    return CastFunction::hasImplicitCast(ListType::getChildType(srcType),
        ArrayType::getChildType(dstType));
}

static bool hasImplicitCastStruct(const LogicalType& srcType, const LogicalType& dstType) {
    const auto& srcFields = StructType::getFields(srcType);
    const auto& dstFields = StructType::getFields(dstType);
    if (srcFields.size() != dstFields.size()) {
        return false;
    }
    for (auto i = 0u; i < srcFields.size(); i++) {
        if (srcFields[i].getName() != dstFields[i].getName()) {
            return false;
        }
        if (!CastFunction::hasImplicitCast(srcFields[i].getType(), dstFields[i].getType())) {
            return false;
        }
    }
    return true;
}

static bool hasImplicitCastUnion(const LogicalType& srcType, const LogicalType& dstType) {
    if (srcType.getLogicalTypeID() == LogicalTypeID::UNION) {
        auto numFieldsSrc = UnionType::getNumFields(srcType);
        for (auto i = 0u; i < numFieldsSrc; ++i) {
            const auto& fieldName = UnionType::getFieldName(srcType, i);
            const auto& fieldType = UnionType::getFieldType(srcType, i);
            if (!UnionType::hasField(dstType, fieldName) ||
                !CastFunction::hasImplicitCast(fieldType,
                    UnionType::getFieldType(dstType, fieldName))) {
                return false;
            }
        }
        return true;
    } else {
        auto numFields = UnionType::getNumFields(dstType);
        for (auto i = 0u; i < numFields; ++i) {
            const auto& fieldType = UnionType::getFieldType(dstType, i);
            if (CastFunction::hasImplicitCast(srcType, fieldType)) {
                return true;
            }
        }
        return false;
    }
}

static bool hasImplicitCastMap(const LogicalType& srcType, const LogicalType& dstType) {
    const auto& srcKeyType = MapType::getKeyType(srcType);
    const auto& srcValueType = MapType::getValueType(srcType);
    const auto& dstKeyType = MapType::getKeyType(dstType);
    const auto& dstValueType = MapType::getValueType(dstType);
    return CastFunction::hasImplicitCast(srcKeyType, dstKeyType) &&
           CastFunction::hasImplicitCast(srcValueType, dstValueType);
}

bool CastFunction::hasImplicitCast(const LogicalType& srcType, const LogicalType& dstType) {
    if (LogicalTypeUtils::isNested(srcType) && LogicalTypeUtils::isNested(dstType)) {
        if (srcType.getLogicalTypeID() == LogicalTypeID::ARRAY &&
            dstType.getLogicalTypeID() == LogicalTypeID::LIST) {
            return hasImplicitCastArrayToList(srcType, dstType);
        }
        if (srcType.getLogicalTypeID() == LogicalTypeID::LIST &&
            dstType.getLogicalTypeID() == LogicalTypeID::ARRAY) {
            return hasImplicitCastListToArray(srcType, dstType);
        }
        if (srcType.getLogicalTypeID() != dstType.getLogicalTypeID()) {
            return false;
        }
        switch (srcType.getLogicalTypeID()) {
        case LogicalTypeID::LIST:
            return hasImplicitCastList(srcType, dstType);
        case LogicalTypeID::ARRAY:
            return hasImplicitCastArray(srcType, dstType);
        case LogicalTypeID::STRUCT:
            return hasImplicitCastStruct(srcType, dstType);
        case LogicalTypeID::UNION:
            return hasImplicitCastUnion(srcType, dstType);
        case LogicalTypeID::MAP:
            return hasImplicitCastMap(srcType, dstType);
        default:
            // LCOV_EXCL_START
            KU_UNREACHABLE;
            // LCOV_EXCL_END
        }
    } else if (dstType.getLogicalTypeID() == LogicalTypeID::UNION) {
        return hasImplicitCastUnion(srcType, dstType);
    }
    if (BuiltInFunctionsUtils::getCastCost(srcType.getLogicalTypeID(),
            dstType.getLogicalTypeID()) != UNDEFINED_CAST_COST) {
        return true;
    }
    // TODO(Jiamin): there are still other special cases
    // We allow cast between any numerical types
    if (LogicalTypeUtils::isNumerical(srcType) && LogicalTypeUtils::isNumerical(dstType)) {
        return true;
    }
    return false;
}

template<typename EXECUTOR = UnaryFunctionExecutor>
static std::unique_ptr<ScalarFunction> bindCastFromStringFunction(const std::string& functionName,
    const LogicalType& targetType) {
    scalar_func_exec_t execFunc;
    switch (targetType.getLogicalTypeID()) {
    case LogicalTypeID::DATE: {
        execFunc =
            ScalarFunction::UnaryCastStringExecFunction<ku_string_t, date_t, CastString, EXECUTOR>;
    } break;
    case LogicalTypeID::TIMESTAMP_SEC: {
        execFunc = ScalarFunction::UnaryCastStringExecFunction<ku_string_t, timestamp_sec_t,
            CastString, EXECUTOR>;
    } break;
    case LogicalTypeID::TIMESTAMP_MS: {
        execFunc = ScalarFunction::UnaryCastStringExecFunction<ku_string_t, timestamp_ms_t,
            CastString, EXECUTOR>;
    } break;
    case LogicalTypeID::TIMESTAMP_NS: {
        execFunc = ScalarFunction::UnaryCastStringExecFunction<ku_string_t, timestamp_ns_t,
            CastString, EXECUTOR>;
    } break;
    case LogicalTypeID::TIMESTAMP_TZ: {
        execFunc = ScalarFunction::UnaryCastStringExecFunction<ku_string_t, timestamp_tz_t,
            CastString, EXECUTOR>;
    } break;
    case LogicalTypeID::TIMESTAMP: {
        execFunc = ScalarFunction::UnaryCastStringExecFunction<ku_string_t, timestamp_t, CastString,
            EXECUTOR>;
    } break;
    case LogicalTypeID::INTERVAL: {
        execFunc = ScalarFunction::UnaryCastStringExecFunction<ku_string_t, interval_t, CastString,
            EXECUTOR>;
    } break;
    case LogicalTypeID::BLOB: {
        execFunc =
            ScalarFunction::UnaryCastStringExecFunction<ku_string_t, blob_t, CastString, EXECUTOR>;
    } break;
    case LogicalTypeID::UUID: {
        execFunc = ScalarFunction::UnaryCastStringExecFunction<ku_string_t, ku_uuid_t, CastString,
            EXECUTOR>;
    } break;
    case LogicalTypeID::STRING: {
        execFunc =
            ScalarFunction::UnaryCastExecFunction<ku_string_t, ku_string_t, CastToString, EXECUTOR>;
    } break;
    case LogicalTypeID::BOOL: {
        execFunc =
            ScalarFunction::UnaryCastStringExecFunction<ku_string_t, bool, CastString, EXECUTOR>;
    } break;
    case LogicalTypeID::DOUBLE: {
        execFunc =
            ScalarFunction::UnaryCastStringExecFunction<ku_string_t, double, CastString, EXECUTOR>;
    } break;
    case LogicalTypeID::FLOAT: {
        execFunc =
            ScalarFunction::UnaryCastStringExecFunction<ku_string_t, float, CastString, EXECUTOR>;
    } break;
    case LogicalTypeID::DECIMAL: {
        switch (targetType.getPhysicalType()) {
        case PhysicalTypeID::INT16:
            execFunc =
                ScalarFunction::UnaryExecNestedTypeFunction<ku_string_t, int16_t, CastToDecimal>;
            break;
        case PhysicalTypeID::INT32:
            execFunc =
                ScalarFunction::UnaryExecNestedTypeFunction<ku_string_t, int32_t, CastToDecimal>;
            break;
        case PhysicalTypeID::INT64:
            execFunc =
                ScalarFunction::UnaryExecNestedTypeFunction<ku_string_t, int64_t, CastToDecimal>;
            break;
        case PhysicalTypeID::INT128:
            execFunc =
                ScalarFunction::UnaryExecNestedTypeFunction<ku_string_t, int128_t, CastToDecimal>;
            break;
        default:
            KU_UNREACHABLE;
        }
    } break;
    case LogicalTypeID::INT128: {
        execFunc = ScalarFunction::UnaryCastStringExecFunction<ku_string_t, int128_t, CastString,
            EXECUTOR>;
    } break;
    case LogicalTypeID::UINT128: {
        execFunc = ScalarFunction::UnaryCastStringExecFunction<ku_string_t, uint128_t, CastString,
            EXECUTOR>;
    } break;
    case LogicalTypeID::SERIAL:
    case LogicalTypeID::INT64: {
        execFunc =
            ScalarFunction::UnaryCastStringExecFunction<ku_string_t, int64_t, CastString, EXECUTOR>;
    } break;
    case LogicalTypeID::INT32: {
        execFunc =
            ScalarFunction::UnaryCastStringExecFunction<ku_string_t, int32_t, CastString, EXECUTOR>;
    } break;
    case LogicalTypeID::INT16: {
        execFunc =
            ScalarFunction::UnaryCastStringExecFunction<ku_string_t, int16_t, CastString, EXECUTOR>;
    } break;
    case LogicalTypeID::INT8: {
        execFunc =
            ScalarFunction::UnaryCastStringExecFunction<ku_string_t, int8_t, CastString, EXECUTOR>;
    } break;
    case LogicalTypeID::UINT64: {
        execFunc = ScalarFunction::UnaryCastStringExecFunction<ku_string_t, uint64_t, CastString,
            EXECUTOR>;
    } break;
    case LogicalTypeID::UINT32: {
        execFunc = ScalarFunction::UnaryCastStringExecFunction<ku_string_t, uint32_t, CastString,
            EXECUTOR>;
    } break;
    case LogicalTypeID::UINT16: {
        execFunc = ScalarFunction::UnaryCastStringExecFunction<ku_string_t, uint16_t, CastString,
            EXECUTOR>;
    } break;
    case LogicalTypeID::UINT8: {
        execFunc =
            ScalarFunction::UnaryCastStringExecFunction<ku_string_t, uint8_t, CastString, EXECUTOR>;
    } break;
    case LogicalTypeID::ARRAY:
    case LogicalTypeID::LIST: {
        execFunc = ScalarFunction::UnaryCastStringExecFunction<ku_string_t, list_entry_t,
            CastString, EXECUTOR>;
    } break;
    case LogicalTypeID::MAP: {
        execFunc = ScalarFunction::UnaryCastStringExecFunction<ku_string_t, map_entry_t, CastString,
            EXECUTOR>;
    } break;
    case LogicalTypeID::STRUCT: {
        execFunc = ScalarFunction::UnaryCastStringExecFunction<ku_string_t, struct_entry_t,
            CastString, EXECUTOR>;
    } break;
    case LogicalTypeID::UNION: {
        execFunc = ScalarFunction::UnaryCastStringExecFunction<ku_string_t, union_entry_t,
            CastString, EXECUTOR>;
    } break;
    default:
        throw ConversionException{
            stringFormat("Unsupported casting function from STRING to {}.", targetType.toString())};
    }
    return std::make_unique<ScalarFunction>(functionName,
        std::vector<LogicalTypeID>{LogicalTypeID::STRING}, targetType.getLogicalTypeID(), execFunc);
}

template<typename EXECUTOR = UnaryFunctionExecutor>
static std::unique_ptr<ScalarFunction> bindCastToStringFunction(const std::string& functionName,
    const LogicalType& sourceType) {
    scalar_func_exec_t func;
    switch (sourceType.getLogicalTypeID()) {
    case LogicalTypeID::BOOL: {
        func = ScalarFunction::UnaryCastExecFunction<bool, ku_string_t, CastToString, EXECUTOR>;
    } break;
    case LogicalTypeID::SERIAL:
    case LogicalTypeID::INT64: {
        func = ScalarFunction::UnaryCastExecFunction<int64_t, ku_string_t, CastToString, EXECUTOR>;
    } break;
    case LogicalTypeID::INT32: {
        func = ScalarFunction::UnaryCastExecFunction<int32_t, ku_string_t, CastToString, EXECUTOR>;
    } break;
    case LogicalTypeID::INT16: {
        func = ScalarFunction::UnaryCastExecFunction<int16_t, ku_string_t, CastToString, EXECUTOR>;
    } break;
    case LogicalTypeID::INT8: {
        func = ScalarFunction::UnaryCastExecFunction<int8_t, ku_string_t, CastToString, EXECUTOR>;
    } break;
    case LogicalTypeID::UINT64: {
        func = ScalarFunction::UnaryCastExecFunction<uint64_t, ku_string_t, CastToString, EXECUTOR>;
    } break;
    case LogicalTypeID::UINT32: {
        func = ScalarFunction::UnaryCastExecFunction<uint32_t, ku_string_t, CastToString, EXECUTOR>;
    } break;
    case LogicalTypeID::UINT16: {
        func = ScalarFunction::UnaryCastExecFunction<uint16_t, ku_string_t, CastToString, EXECUTOR>;
    } break;
    case LogicalTypeID::INT128: {
        func = ScalarFunction::UnaryCastExecFunction<int128_t, ku_string_t, CastToString, EXECUTOR>;
    } break;
    case LogicalTypeID::UINT128: {
        func =
            ScalarFunction::UnaryCastExecFunction<uint128_t, ku_string_t, CastToString, EXECUTOR>;
    } break;
    case LogicalTypeID::UINT8: {
        func = ScalarFunction::UnaryCastExecFunction<uint8_t, ku_string_t, CastToString, EXECUTOR>;
    } break;
    case LogicalTypeID::DOUBLE: {
        func = ScalarFunction::UnaryCastExecFunction<double, ku_string_t, CastToString, EXECUTOR>;
    } break;
    case LogicalTypeID::FLOAT: {
        func = ScalarFunction::UnaryCastExecFunction<float, ku_string_t, CastToString, EXECUTOR>;
    } break;
    case LogicalTypeID::DECIMAL: {
        switch (sourceType.getPhysicalType()) {
        case PhysicalTypeID::INT16:
            func = ScalarFunction::UnaryExecNestedTypeFunction<int16_t, ku_string_t, CastDecimalTo>;
            break;
        case PhysicalTypeID::INT32:
            func = ScalarFunction::UnaryExecNestedTypeFunction<int32_t, ku_string_t, CastDecimalTo>;
            break;
        case PhysicalTypeID::INT64:
            func = ScalarFunction::UnaryExecNestedTypeFunction<int64_t, ku_string_t, CastDecimalTo>;
            break;
        case PhysicalTypeID::INT128:
            func =
                ScalarFunction::UnaryExecNestedTypeFunction<int128_t, ku_string_t, CastDecimalTo>;
            break;
        default:
            KU_UNREACHABLE;
        }
    } break;
    case LogicalTypeID::DATE: {
        func = ScalarFunction::UnaryCastExecFunction<date_t, ku_string_t, CastToString, EXECUTOR>;
    } break;
    case LogicalTypeID::TIMESTAMP_NS: {
        func = ScalarFunction::UnaryCastExecFunction<timestamp_ns_t, ku_string_t, CastToString,
            EXECUTOR>;
    } break;
    case LogicalTypeID::TIMESTAMP_MS: {
        func = ScalarFunction::UnaryCastExecFunction<timestamp_ms_t, ku_string_t, CastToString,
            EXECUTOR>;
    } break;
    case LogicalTypeID::TIMESTAMP_SEC: {
        func = ScalarFunction::UnaryCastExecFunction<timestamp_sec_t, ku_string_t, CastToString,
            EXECUTOR>;
    } break;
    case LogicalTypeID::TIMESTAMP_TZ: {
        func = ScalarFunction::UnaryCastExecFunction<timestamp_tz_t, ku_string_t, CastToString,
            EXECUTOR>;
    } break;
    case LogicalTypeID::TIMESTAMP: {
        func =
            ScalarFunction::UnaryCastExecFunction<timestamp_t, ku_string_t, CastToString, EXECUTOR>;
    } break;
    case LogicalTypeID::INTERVAL: {
        func =
            ScalarFunction::UnaryCastExecFunction<interval_t, ku_string_t, CastToString, EXECUTOR>;
    } break;
    case LogicalTypeID::INTERNAL_ID: {
        func = ScalarFunction::UnaryCastExecFunction<internalID_t, ku_string_t, CastToString,
            EXECUTOR>;
    } break;
    case LogicalTypeID::BLOB: {
        func = ScalarFunction::UnaryCastExecFunction<blob_t, ku_string_t, CastToString, EXECUTOR>;
    } break;
    case LogicalTypeID::UUID: {
        func =
            ScalarFunction::UnaryCastExecFunction<ku_uuid_t, ku_string_t, CastToString, EXECUTOR>;
    } break;
    case LogicalTypeID::ARRAY:
    case LogicalTypeID::LIST: {
        func = ScalarFunction::UnaryCastExecFunction<list_entry_t, ku_string_t, CastToString,
            EXECUTOR>;
    } break;
    case LogicalTypeID::MAP: {
        func =
            ScalarFunction::UnaryCastExecFunction<map_entry_t, ku_string_t, CastToString, EXECUTOR>;
    } break;
    case LogicalTypeID::NODE: {
        func = ScalarFunction::UnaryCastExecFunction<struct_entry_t, ku_string_t, CastNodeToString,
            EXECUTOR>;
    } break;
    case LogicalTypeID::REL: {
        func = ScalarFunction::UnaryCastExecFunction<struct_entry_t, ku_string_t, CastRelToString,
            EXECUTOR>;
    } break;
    case LogicalTypeID::RECURSIVE_REL:
    case LogicalTypeID::STRUCT: {
        func = ScalarFunction::UnaryCastExecFunction<struct_entry_t, ku_string_t, CastToString,
            EXECUTOR>;
    } break;
    case LogicalTypeID::UNION: {
        func = ScalarFunction::UnaryCastExecFunction<union_entry_t, ku_string_t, CastToString,
            EXECUTOR>;
    } break;
    default:
        KU_UNREACHABLE;
    }
    return std::make_unique<ScalarFunction>(functionName,
        std::vector<LogicalTypeID>{sourceType.getLogicalTypeID()}, LogicalTypeID::STRING, func);
}

template<typename DST_TYPE, CastExecutor EXECUTOR>
static std::unique_ptr<ScalarFunction> bindCastToDecimalFunction(const std::string& functionName,
    const LogicalType& sourceType, const LogicalType& targetType) {
    scalar_func_exec_t func;
    if (sourceType.getLogicalTypeID() == LogicalTypeID::DECIMAL) {
        TypeUtils::visit(
            sourceType,
            [&]<SignedIntegerTypes T>(T) {
                func = ScalarFunction::UnaryCastExecFunction<T, DST_TYPE, CastBetweenDecimal,
                    EXECUTOR>;
            },
            [&](auto) { KU_UNREACHABLE; });
    } else {
        TypeUtils::visit(
            sourceType,
            [&]<NumericTypes T>(T) {
                func = ScalarFunction::UnaryCastExecFunction<T, DST_TYPE, CastToDecimal, EXECUTOR>;
            },
            [&](auto) { KU_UNREACHABLE; });
    }
    return std::make_unique<ScalarFunction>(functionName,
        std::vector<LogicalTypeID>{sourceType.getLogicalTypeID()}, targetType.getLogicalTypeID(),
        func);
}

template<typename DST_TYPE, typename OP, typename EXECUTOR = UnaryFunctionExecutor>
static std::unique_ptr<ScalarFunction> bindCastToNumericFunction(const std::string& functionName,
    const LogicalType& sourceType, const LogicalType& targetType) {
    scalar_func_exec_t func;
    switch (sourceType.getLogicalTypeID()) {
    case LogicalTypeID::INT8: {
        func = ScalarFunction::UnaryExecFunction<int8_t, DST_TYPE, OP, EXECUTOR>;
    } break;
    case LogicalTypeID::INT16: {
        func = ScalarFunction::UnaryExecFunction<int16_t, DST_TYPE, OP, EXECUTOR>;
    } break;
    case LogicalTypeID::INT32: {
        func = ScalarFunction::UnaryExecFunction<int32_t, DST_TYPE, OP, EXECUTOR>;
    } break;
    case LogicalTypeID::SERIAL:
    case LogicalTypeID::INT64: {
        func = ScalarFunction::UnaryExecFunction<int64_t, DST_TYPE, OP, EXECUTOR>;
    } break;
    case LogicalTypeID::UINT8: {
        func = ScalarFunction::UnaryExecFunction<uint8_t, DST_TYPE, OP, EXECUTOR>;
    } break;
    case LogicalTypeID::UINT16: {
        func = ScalarFunction::UnaryExecFunction<uint16_t, DST_TYPE, OP, EXECUTOR>;
    } break;
    case LogicalTypeID::UINT32: {
        func = ScalarFunction::UnaryExecFunction<uint32_t, DST_TYPE, OP, EXECUTOR>;
    } break;
    case LogicalTypeID::UINT64: {
        func = ScalarFunction::UnaryExecFunction<uint64_t, DST_TYPE, OP, EXECUTOR>;
    } break;
    case LogicalTypeID::INT128: {
        func = ScalarFunction::UnaryExecFunction<int128_t, DST_TYPE, OP, EXECUTOR>;
    } break;
    case LogicalTypeID::UINT128: {
        func = ScalarFunction::UnaryExecFunction<uint128_t, DST_TYPE, OP, EXECUTOR>;
    } break;
    case LogicalTypeID::FLOAT: {
        func = ScalarFunction::UnaryExecFunction<float, DST_TYPE, OP, EXECUTOR>;
    } break;
    case LogicalTypeID::DOUBLE: {
        func = ScalarFunction::UnaryExecFunction<double, DST_TYPE, OP, EXECUTOR>;
    } break;
    case LogicalTypeID::DECIMAL: {
        switch (sourceType.getPhysicalType()) {
        // note: this cannot handle decimal -> decimal casting.
        case PhysicalTypeID::INT16:
            func = ScalarFunction::UnaryExecNestedTypeFunction<int16_t, DST_TYPE, CastDecimalTo,
                EXECUTOR>;
            break;
        case PhysicalTypeID::INT32:
            func = ScalarFunction::UnaryExecNestedTypeFunction<int32_t, DST_TYPE, CastDecimalTo,
                EXECUTOR>;
            break;
        case PhysicalTypeID::INT64:
            func = ScalarFunction::UnaryExecNestedTypeFunction<int64_t, DST_TYPE, CastDecimalTo,
                EXECUTOR>;
            break;
        case PhysicalTypeID::INT128:
            func = ScalarFunction::UnaryExecNestedTypeFunction<int128_t, DST_TYPE, CastDecimalTo,
                EXECUTOR>;
            break;
        default:
            KU_UNREACHABLE;
        }
    } break;
    default:
        throw ConversionException{stringFormat("Unsupported casting function from {} to {}.",
            sourceType.toString(), targetType.toString())};
    }
    return std::make_unique<ScalarFunction>(functionName,
        std::vector<LogicalTypeID>{sourceType.getLogicalTypeID()}, targetType.getLogicalTypeID(),
        func);
}

static union_field_idx_t findUnionMinCostTag(const LogicalType& sourceType,
    const LogicalType& unionType) {
    uint32_t minCastCost = UNDEFINED_CAST_COST;
    union_field_idx_t minCostTag = 0;
    auto numFields = UnionType::getNumFields(unionType);
    for (auto i = 0u; i < numFields; ++i) {
        const auto& fieldType = UnionType::getFieldType(unionType, i);
        if (CastFunction::hasImplicitCast(sourceType, fieldType)) {
            uint32_t castCost = BuiltInFunctionsUtils::getCastCost(sourceType.getLogicalTypeID(),
                fieldType.getLogicalTypeID());
            if (castCost < minCastCost) {
                minCastCost = castCost;
                minCostTag = i;
            }
        }
    }
    if (minCastCost == UNDEFINED_CAST_COST) {
        throw ConversionException{
            stringFormat("Cannot cast from {} to {}, target type has no compatible field.",
                sourceType.toString(), unionType.toString())};
    }
    return minCostTag;
}

static std::unique_ptr<ScalarFunction> bindCastToUnionFunction(const std::string& functionName,
    const LogicalType& sourceType, const LogicalType& targetType) {
    auto minCostTag = findUnionMinCostTag(sourceType, targetType);
    const auto& innerType = common::UnionType::getFieldType(targetType, minCostTag);
    CastToUnionBindData::inner_func_t innerFunc;
    if (sourceType == innerType) {
        innerFunc = [](ValueVector* inputVector, ValueVector& valVector, SelectionVector*,
                        uint64_t inputPos, uint64_t resultPos) {
            valVector.copyFromVectorData(inputPos, inputVector, resultPos);
        };
    } else {
        std::shared_ptr<ScalarFunction> innerCast =
            CastFunction::bindCastFunction("CAST", sourceType, innerType);
        innerFunc = [innerCast](ValueVector* inputVector, ValueVector& valVector,
                        SelectionVector* selVector, uint64_t, uint64_t) {
            // Can we just use inputPos / resultPos and not the entire sel vector?
            auto input = std::shared_ptr<ValueVector>(inputVector, [](ValueVector*) {});
            innerCast->execFunc({input}, {selVector}, valVector, selVector, nullptr /* dataPtr */);
        };
    }
    auto castFunc = std::make_unique<ScalarFunction>(functionName,
        std::vector<LogicalTypeID>{sourceType.getLogicalTypeID()}, targetType.getLogicalTypeID(),
        ScalarFunction::UnaryCastExecFunction<void, void, CastToUnion, UnaryFunctionExecutor,
            UnaryCastUnionFunctionWrapper>);
    castFunc->bindFunc = [minCostTag, innerFunc, &targetType](const ScalarBindFuncInput&) {
        return std::make_unique<CastToUnionBindData>(minCostTag, innerFunc, targetType.copy());
    };
    return castFunc;
}

static std::unique_ptr<ScalarFunction> bindCastBetweenNested(const std::string& functionName,
    const LogicalType& sourceType, const LogicalType& targetType) {
    // todo: compile time checking of nested types
    if (CastArrayHelper::checkCompatibleNestedTypes(sourceType.getLogicalTypeID(),
            targetType.getLogicalTypeID())) {
        return std::make_unique<ScalarFunction>(functionName,
            std::vector<LogicalTypeID>{sourceType.getLogicalTypeID()},
            targetType.getLogicalTypeID(), nestedTypesCastExecFunction);
    }
    throw ConversionException{stringFormat("Unsupported casting function from {} to {}.",
        LogicalTypeUtils::toString(sourceType.getLogicalTypeID()),
        LogicalTypeUtils::toString(targetType.getLogicalTypeID()))};
}

template<typename EXECUTOR = UnaryFunctionExecutor, typename DST_TYPE>
static std::unique_ptr<ScalarFunction> bindCastToDateFunction(const std::string& functionName,
    const LogicalType& sourceType, const LogicalType& dstType) {
    scalar_func_exec_t func;
    switch (sourceType.getLogicalTypeID()) {
    case LogicalTypeID::TIMESTAMP_MS:
        func = ScalarFunction::UnaryExecFunction<timestamp_ms_t, DST_TYPE, CastToDate, EXECUTOR>;
        break;
    case LogicalTypeID::TIMESTAMP_NS:
        func = ScalarFunction::UnaryExecFunction<timestamp_ns_t, DST_TYPE, CastToDate, EXECUTOR>;
        break;
    case LogicalTypeID::TIMESTAMP_SEC:
        func = ScalarFunction::UnaryExecFunction<timestamp_sec_t, DST_TYPE, CastToDate, EXECUTOR>;
        break;
    case LogicalTypeID::TIMESTAMP_TZ:
    case LogicalTypeID::TIMESTAMP:
        func = ScalarFunction::UnaryExecFunction<timestamp_t, DST_TYPE, CastToDate, EXECUTOR>;
        break;
    // LCOV_EXCL_START
    default:
        throw ConversionException{stringFormat("Unsupported casting function from {} to {}.",
            sourceType.toString(), dstType.toString())};
        // LCOV_EXCL_END
    }
    return std::make_unique<ScalarFunction>(functionName,
        std::vector<LogicalTypeID>{sourceType.getLogicalTypeID()}, LogicalTypeID::DATE, func);
}

template<typename EXECUTOR = UnaryFunctionExecutor, typename DST_TYPE>
static std::unique_ptr<ScalarFunction> bindCastToTimestampFunction(const std::string& functionName,
    const LogicalType& sourceType, const LogicalType& dstType) {
    scalar_func_exec_t func;
    switch (sourceType.getLogicalTypeID()) {
    case LogicalTypeID::DATE: {
        func = ScalarFunction::UnaryExecFunction<date_t, DST_TYPE, CastDateToTimestamp, EXECUTOR>;
    } break;
    case LogicalTypeID::TIMESTAMP_MS: {
        func = ScalarFunction::UnaryExecFunction<timestamp_ms_t, DST_TYPE, CastBetweenTimestamp,
            EXECUTOR>;
    } break;
    case LogicalTypeID::TIMESTAMP_NS: {
        func = ScalarFunction::UnaryExecFunction<timestamp_ns_t, DST_TYPE, CastBetweenTimestamp,
            EXECUTOR>;
    } break;
    case LogicalTypeID::TIMESTAMP_SEC: {
        func = ScalarFunction::UnaryExecFunction<timestamp_sec_t, DST_TYPE, CastBetweenTimestamp,
            EXECUTOR>;
    } break;
    case LogicalTypeID::TIMESTAMP_TZ:
    case LogicalTypeID::TIMESTAMP: {
        func = ScalarFunction::UnaryExecFunction<timestamp_t, DST_TYPE, CastBetweenTimestamp,
            EXECUTOR>;
    } break;
    default:
        throw ConversionException{stringFormat("Unsupported casting function from {} to {}.",
            sourceType.toString(), dstType.toString())};
    }
    return std::make_unique<ScalarFunction>(functionName,
        std::vector<LogicalTypeID>{sourceType.getLogicalTypeID()}, LogicalTypeID::TIMESTAMP, func);
}

template<typename DST_TYPE>
static std::unique_ptr<ScalarFunction> bindCastBetweenDecimalFunction(
    const std::string& functionName, const LogicalType& sourceType) {
    scalar_func_exec_t func;
    switch (sourceType.getPhysicalType()) {
    case PhysicalTypeID::INT16:
        func = ScalarFunction::UnaryExecNestedTypeFunction<int16_t, DST_TYPE, CastBetweenDecimal>;
        break;
    case PhysicalTypeID::INT32:
        func = ScalarFunction::UnaryExecNestedTypeFunction<int32_t, DST_TYPE, CastBetweenDecimal>;
        break;
    case PhysicalTypeID::INT64:
        func = ScalarFunction::UnaryExecNestedTypeFunction<int64_t, DST_TYPE, CastBetweenDecimal>;
        break;
    case PhysicalTypeID::INT128:
        func = ScalarFunction::UnaryExecNestedTypeFunction<int128_t, DST_TYPE, CastBetweenDecimal>;
        break;
    default:
        KU_UNREACHABLE;
    }
    return std::make_unique<ScalarFunction>(functionName,
        std::vector<LogicalTypeID>{LogicalTypeID::DECIMAL}, LogicalTypeID::DECIMAL, func);
}

template<CastExecutor EXECUTOR>
std::unique_ptr<ScalarFunction> CastFunction::bindCastFunction(const std::string& functionName,
    const LogicalType& sourceType, const LogicalType& targetType) {
    auto sourceTypeID = sourceType.getLogicalTypeID();
    auto targetTypeID = targetType.getLogicalTypeID();
    if (sourceTypeID == LogicalTypeID::STRING) {
        return bindCastFromStringFunction<EXECUTOR>(functionName, targetType);
    }
    switch (targetTypeID) {
    case LogicalTypeID::STRING: {
        return bindCastToStringFunction<EXECUTOR>(functionName, sourceType);
    }
    case LogicalTypeID::DOUBLE: {
        return bindCastToNumericFunction<double, CastToDouble, EXECUTOR>(functionName, sourceType,
            targetType);
    }
    case LogicalTypeID::FLOAT: {
        return bindCastToNumericFunction<float, CastToFloat, EXECUTOR>(functionName, sourceType,
            targetType);
    }
    case LogicalTypeID::DECIMAL: {
        std::unique_ptr<ScalarFunction> scalarFunc;
        TypeUtils::visit(
            targetType.getPhysicalType(),
            [&]<IntegerTypes T>(T) {
                scalarFunc =
                    bindCastToDecimalFunction<T, EXECUTOR>(functionName, sourceType, targetType);
            },
            [](auto) { KU_UNREACHABLE; });
        return scalarFunc;
    }
    case LogicalTypeID::INT128: {
        return bindCastToNumericFunction<int128_t, CastToInt128, EXECUTOR>(functionName, sourceType,
            targetType);
    }
    case LogicalTypeID::UINT128: {
        return bindCastToNumericFunction<uint128_t, CastToUInt128, EXECUTOR>(functionName,
            sourceType, targetType);
    }
    case LogicalTypeID::SERIAL: {
        return bindCastToNumericFunction<int64_t, CastToSerial, EXECUTOR>(functionName, sourceType,
            targetType);
    }
    case LogicalTypeID::INT64: {
        return bindCastToNumericFunction<int64_t, CastToInt64, EXECUTOR>(functionName, sourceType,
            targetType);
    }
    case LogicalTypeID::INT32: {
        return bindCastToNumericFunction<int32_t, CastToInt32, EXECUTOR>(functionName, sourceType,
            targetType);
    }
    case LogicalTypeID::INT16: {
        return bindCastToNumericFunction<int16_t, CastToInt16, EXECUTOR>(functionName, sourceType,
            targetType);
    }
    case LogicalTypeID::INT8: {
        return bindCastToNumericFunction<int8_t, CastToInt8, EXECUTOR>(functionName, sourceType,
            targetType);
    }
    case LogicalTypeID::UINT64: {
        return bindCastToNumericFunction<uint64_t, CastToUInt64, EXECUTOR>(functionName, sourceType,
            targetType);
    }
    case LogicalTypeID::UINT32: {
        return bindCastToNumericFunction<uint32_t, CastToUInt32, EXECUTOR>(functionName, sourceType,
            targetType);
    }
    case LogicalTypeID::UINT16: {
        return bindCastToNumericFunction<uint16_t, CastToUInt16, EXECUTOR>(functionName, sourceType,
            targetType);
    }
    case LogicalTypeID::UINT8: {
        return bindCastToNumericFunction<uint8_t, CastToUInt8, EXECUTOR>(functionName, sourceType,
            targetType);
    }
    case LogicalTypeID::DATE: {
        return bindCastToDateFunction<EXECUTOR, date_t>(functionName, sourceType, targetType);
    }
    case LogicalTypeID::TIMESTAMP_NS: {
        return bindCastToTimestampFunction<EXECUTOR, timestamp_ns_t>(functionName, sourceType,
            targetType);
    }
    case LogicalTypeID::TIMESTAMP_MS: {
        return bindCastToTimestampFunction<EXECUTOR, timestamp_ms_t>(functionName, sourceType,
            targetType);
    }
    case LogicalTypeID::TIMESTAMP_SEC: {
        return bindCastToTimestampFunction<EXECUTOR, timestamp_sec_t>(functionName, sourceType,
            targetType);
    }
    case LogicalTypeID::TIMESTAMP_TZ:
    case LogicalTypeID::TIMESTAMP: {
        return bindCastToTimestampFunction<EXECUTOR, timestamp_t>(functionName, sourceType,
            targetType);
    }
    case LogicalTypeID::UNION: {
        if (sourceType.getLogicalTypeID() != LogicalTypeID::UNION &&
            !CastArrayHelper::isUnionSpecialCast(sourceType, targetType)) {
            return bindCastToUnionFunction(functionName, sourceType, targetType);
        }
        [[fallthrough]];
    }
    case LogicalTypeID::LIST:
    case LogicalTypeID::ARRAY:
    case LogicalTypeID::MAP:
    case LogicalTypeID::STRUCT: {
        return bindCastBetweenNested(functionName, sourceType, targetType);
    }
    default: {
        throw ConversionException(stringFormat("Unsupported casting function from {} to {}.",
            sourceType.toString(), targetType.toString()));
    }
    }
}

function_set CastToDateFunction::getFunctionSet() {
    function_set result;
    result.push_back(
        CastFunction::bindCastFunction(name, LogicalType::STRING(), LogicalType::DATE()));
    return result;
}

function_set CastToTimestampFunction::getFunctionSet() {
    function_set result;
    result.push_back(
        CastFunction::bindCastFunction(name, LogicalType::STRING(), LogicalType::TIMESTAMP()));
    return result;
}

function_set CastToIntervalFunction::getFunctionSet() {
    function_set result;
    result.push_back(
        CastFunction::bindCastFunction(name, LogicalType::STRING(), LogicalType::INTERVAL()));
    return result;
}

static std::unique_ptr<FunctionBindData> toStringBindFunc(ScalarBindFuncInput input) {
    return FunctionBindData::getSimpleBindData(input.arguments, LogicalType::STRING());
}

function_set CastToStringFunction::getFunctionSet() {
    function_set result;
    result.reserve(LogicalTypeUtils::getAllValidLogicTypes().size());
    for (auto& type : LogicalTypeUtils::getAllValidLogicTypes()) {
        auto function = CastFunction::bindCastFunction(name, type, LogicalType::STRING());
        function->bindFunc = toStringBindFunc;
        result.push_back(std::move(function));
    }
    return result;
}

function_set CastToBlobFunction::getFunctionSet() {
    function_set result;
    result.push_back(
        CastFunction::bindCastFunction(name, LogicalType::STRING(), LogicalType::BLOB()));
    return result;
}

function_set CastToUUIDFunction::getFunctionSet() {
    function_set result;
    result.push_back(
        CastFunction::bindCastFunction(name, LogicalType::STRING(), LogicalType::UUID()));
    return result;
}

function_set CastToBoolFunction::getFunctionSet() {
    function_set result;
    result.push_back(
        CastFunction::bindCastFunction(name, LogicalType::STRING(), LogicalType::BOOL()));
    return result;
}

function_set CastToDoubleFunction::getFunctionSet() {
    function_set result;
    for (auto typeID : LogicalTypeUtils::getNumericalLogicalTypeIDs()) {
        result.push_back(
            CastFunction::bindCastFunction(name, LogicalType(typeID), LogicalType::DOUBLE()));
    }
    result.push_back(
        CastFunction::bindCastFunction(name, LogicalType::STRING(), LogicalType::DOUBLE()));
    return result;
}

function_set CastToFloatFunction::getFunctionSet() {
    function_set result;
    for (auto typeID : LogicalTypeUtils::getNumericalLogicalTypeIDs()) {
        result.push_back(
            CastFunction::bindCastFunction(name, LogicalType(typeID), LogicalType::FLOAT()));
    }
    result.push_back(
        CastFunction::bindCastFunction(name, LogicalType::STRING(), LogicalType::FLOAT()));
    return result;
}

function_set CastToInt128Function::getFunctionSet() {
    function_set result;
    for (auto typeID : LogicalTypeUtils::getNumericalLogicalTypeIDs()) {
        result.push_back(
            CastFunction::bindCastFunction(name, LogicalType(typeID), LogicalType::INT128()));
    }
    result.push_back(
        CastFunction::bindCastFunction(name, LogicalType::STRING(), LogicalType::INT128()));
    return result;
}

function_set CastToSerialFunction::getFunctionSet() {
    function_set result;
    for (auto typeID : LogicalTypeUtils::getNumericalLogicalTypeIDs()) {
        result.push_back(
            CastFunction::bindCastFunction(name, LogicalType(typeID), LogicalType::SERIAL()));
    }
    result.push_back(
        CastFunction::bindCastFunction(name, LogicalType::STRING(), LogicalType::SERIAL()));
    return result;
}

function_set CastToInt64Function::getFunctionSet() {
    function_set result;
    for (auto typeID : LogicalTypeUtils::getNumericalLogicalTypeIDs()) {
        result.push_back(
            CastFunction::bindCastFunction(name, LogicalType(typeID), LogicalType::INT64()));
    }
    result.push_back(
        CastFunction::bindCastFunction(name, LogicalType::STRING(), LogicalType::INT64()));
    return result;
}

function_set CastToInt32Function::getFunctionSet() {
    function_set result;
    for (auto typeID : LogicalTypeUtils::getNumericalLogicalTypeIDs()) {
        result.push_back(
            CastFunction::bindCastFunction(name, LogicalType(typeID), LogicalType::INT32()));
    }
    result.push_back(
        CastFunction::bindCastFunction(name, LogicalType::STRING(), LogicalType::INT32()));
    return result;
}

function_set CastToInt16Function::getFunctionSet() {
    function_set result;
    for (auto typeID : LogicalTypeUtils::getNumericalLogicalTypeIDs()) {
        result.push_back(
            CastFunction::bindCastFunction(name, LogicalType(typeID), LogicalType::INT16()));
    }
    result.push_back(
        CastFunction::bindCastFunction(name, LogicalType::STRING(), LogicalType::INT16()));
    return result;
}

function_set CastToInt8Function::getFunctionSet() {
    function_set result;
    for (auto typeID : LogicalTypeUtils::getNumericalLogicalTypeIDs()) {
        result.push_back(
            CastFunction::bindCastFunction(name, LogicalType(typeID), LogicalType::INT8()));
    }
    result.push_back(
        CastFunction::bindCastFunction(name, LogicalType::STRING(), LogicalType::INT8()));
    return result;
}

function_set CastToUInt128Function::getFunctionSet() {
    function_set result;
    for (auto typeID : LogicalTypeUtils::getNumericalLogicalTypeIDs()) {
        result.push_back(
            CastFunction::bindCastFunction(name, LogicalType(typeID), LogicalType::UINT128()));
    }
    result.push_back(
        CastFunction::bindCastFunction(name, LogicalType::STRING(), LogicalType::UINT128()));
    return result;
}

function_set CastToUInt64Function::getFunctionSet() {
    function_set result;
    for (auto typeID : LogicalTypeUtils::getNumericalLogicalTypeIDs()) {
        result.push_back(
            CastFunction::bindCastFunction(name, LogicalType(typeID), LogicalType::UINT64()));
    }
    result.push_back(
        CastFunction::bindCastFunction(name, LogicalType::STRING(), LogicalType::UINT64()));
    return result;
}

function_set CastToUInt32Function::getFunctionSet() {
    function_set result;
    for (auto typeID : LogicalTypeUtils::getNumericalLogicalTypeIDs()) {
        result.push_back(
            CastFunction::bindCastFunction(name, LogicalType(typeID), LogicalType::UINT32()));
    }
    result.push_back(
        CastFunction::bindCastFunction(name, LogicalType::STRING(), LogicalType::UINT32()));
    return result;
}

function_set CastToUInt16Function::getFunctionSet() {
    function_set result;
    for (auto typeID : LogicalTypeUtils::getNumericalLogicalTypeIDs()) {
        result.push_back(
            CastFunction::bindCastFunction(name, LogicalType(typeID), LogicalType::UINT16()));
    }
    result.push_back(
        CastFunction::bindCastFunction(name, LogicalType::STRING(), LogicalType::UINT16()));
    return result;
}

function_set CastToUInt8Function::getFunctionSet() {
    function_set result;
    for (auto typeID : LogicalTypeUtils::getNumericalLogicalTypeIDs()) {
        result.push_back(
            CastFunction::bindCastFunction(name, LogicalType(typeID), LogicalType::UINT8()));
    }
    result.push_back(
        CastFunction::bindCastFunction(name, LogicalType::STRING(), LogicalType::UINT8()));
    return result;
}

static std::unique_ptr<FunctionBindData> castBindFunc(ScalarBindFuncInput input) {
    KU_ASSERT(input.arguments.size() == 2);
    // Bind target type.
    if (input.arguments[1]->expressionType != ExpressionType::LITERAL) {
        throw BinderException(stringFormat("Second parameter of CAST function must be a literal."));
    }
    auto literalExpr = input.arguments[1]->constPtrCast<LiteralExpression>();
    auto targetTypeStr = literalExpr->getValue().getValue<std::string>();
    auto func = input.definition->ptrCast<ScalarFunction>();
    func->name = "CAST_TO_" + targetTypeStr;
    auto targetType = LogicalType::convertFromString(targetTypeStr, input.context);
    if (!LogicalType::isBuiltInType(targetTypeStr)) {
        std::vector<LogicalType> typeVec;
        typeVec.push_back(input.arguments[0]->getDataType().copy());
        try {
            auto entry =
                catalog::Catalog::Get(*input.context)
                    ->getFunctionEntry(transaction::Transaction::Get(*input.context), func->name);
            auto match = BuiltInFunctionsUtils::matchFunction(func->name, typeVec,
                entry->ptrCast<catalog::FunctionCatalogEntry>());
            func->execFunc = match->constPtrCast<ScalarFunction>()->execFunc;
            return std::make_unique<function::CastFunctionBindData>(targetType.copy());
        } catch (...) { // NOLINT
            // If there's no user defined casting function for the corresponding user defined type,
            // we use the default casting function.
        }
    }
    // For STRUCT type, we will need to check its field name in later stage
    // Otherwise, there will be bug for: RETURN cast({'a': 12, 'b': 12} AS struct(c int64, d
    // int64)); being allowed.
    if (targetType == input.arguments[0]->getDataType() &&
        targetType.getLogicalTypeID() != LogicalTypeID::STRUCT) { // No need to cast.
        return nullptr;
    }
    if (ExpressionUtil::canCastStatically(*input.arguments[0], targetType) &&
        targetType.getLogicalTypeID() != LogicalTypeID::STRUCT) {
        input.arguments[0]->cast(targetType);
        return nullptr;
    }
    // TODO(Xiyang): Can we unify the binding of casting function with other scalar functions?
    auto res =
        CastFunction::bindCastFunction(func->name, input.arguments[0]->getDataType(), targetType);
    func->execFunc = res->execFunc;
    if (res->bindFunc) {
        return res->bindFunc(input);
    }
    return std::make_unique<function::CastFunctionBindData>(targetType.copy());
}

function_set CastAnyFunction::getFunctionSet() {
    function_set result;
    auto func = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::ANY, LogicalTypeID::STRING}, LogicalTypeID::ANY);
    func->bindFunc = castBindFunc;
    result.push_back(std::move(func));
    return result;
}

} // namespace function
} // namespace lbug
