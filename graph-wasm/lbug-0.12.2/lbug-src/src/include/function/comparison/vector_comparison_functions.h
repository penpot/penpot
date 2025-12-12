#pragma once

#include "common/exception/runtime.h"
#include "common/types/int128_t.h"
#include "common/types/interval_t.h"
#include "common/types/uint128_t.h"
#include "comparison_functions.h"
#include "function/scalar_function.h"

namespace lbug {
namespace function {

struct ComparisonFunction {
    template<typename OP>
    static function_set getFunctionSet(const std::string& name) {
        function_set functionSet;
        for (auto& comparableType : common::LogicalTypeUtils::getAllValidLogicTypeIDs()) {
            functionSet.push_back(getFunction<OP>(name, comparableType, comparableType));
        }
        functionSet.push_back(getDecimalCompare<OP>(name));
        return functionSet;
    }

private:
    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename RESULT_TYPE, typename FUNC>
    static void BinaryComparisonExecFunction(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr = nullptr) {
        KU_ASSERT(params.size() == 2);
        BinaryFunctionExecutor::executeSwitch<LEFT_TYPE, RIGHT_TYPE, RESULT_TYPE, FUNC,
            BinaryComparisonFunctionWrapper>(*params[0], paramSelVectors[0], *params[1],
            paramSelVectors[1], result, resultSelVector, dataPtr);
    }

    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename FUNC>
    static bool BinaryComparisonSelectFunction(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        common::SelectionVector& selVector, void* dataPtr = nullptr) {
        KU_ASSERT(params.size() == 2);
        return BinaryFunctionExecutor::selectComparison<LEFT_TYPE, RIGHT_TYPE, FUNC>(*params[0],
            *params[1], selVector, dataPtr);
    }

    template<typename FUNC>
    static std::unique_ptr<ScalarFunction> getFunction(const std::string& name,
        common::LogicalTypeID leftType, common::LogicalTypeID rightType) {
        auto leftPhysical = common::LogicalType::getPhysicalType(leftType);
        auto rightPhysical = common::LogicalType::getPhysicalType(rightType);
        scalar_func_exec_t execFunc;
        getExecFunc<FUNC>(leftPhysical, rightPhysical, execFunc);
        scalar_func_select_t selectFunc;
        getSelectFunc<FUNC>(leftPhysical, rightPhysical, selectFunc);
        return std::make_unique<ScalarFunction>(name,
            std::vector<common::LogicalTypeID>{leftType, rightType}, common::LogicalTypeID::BOOL,
            execFunc, selectFunc);
    }

    template<typename FUNC>
    static std::unique_ptr<FunctionBindData> bindDecimalCompare(ScalarBindFuncInput bindInput) {
        auto func = bindInput.definition->ptrCast<ScalarFunction>();
        // assumes input types are identical
        auto physicalType = bindInput.arguments[0]->dataType.getPhysicalType();
        getExecFunc<FUNC>(physicalType, physicalType, func->execFunc);
        getSelectFunc<FUNC>(physicalType, physicalType, func->selectFunc);
        return nullptr;
    }

    template<typename FUNC>
    static std::unique_ptr<ScalarFunction> getDecimalCompare(const std::string& name) {
        scalar_bind_func bindFunc = bindDecimalCompare<FUNC>;
        auto func = std::make_unique<ScalarFunction>(name,
            std::vector<common::LogicalTypeID>{common::LogicalTypeID::DECIMAL,
                common::LogicalTypeID::DECIMAL},
            common::LogicalTypeID::BOOL); // necessary because decimal physical type is not known
                                          // from the ID
        func->bindFunc = bindFunc;
        return func;
    }

    // When comparing two values, we guarantee that they must have the same dataType. So we only
    // need to switch the physical type to get the corresponding exec function.
    template<typename FUNC>
    static void getExecFunc(common::PhysicalTypeID leftType, common::PhysicalTypeID rightType,
        scalar_func_exec_t& func) {
        switch (leftType) {
        case common::PhysicalTypeID::INT64: {
            func = BinaryComparisonExecFunction<int64_t, int64_t, uint8_t, FUNC>;
        } break;
        case common::PhysicalTypeID::INT32: {
            func = BinaryComparisonExecFunction<int32_t, int32_t, uint8_t, FUNC>;
        } break;
        case common::PhysicalTypeID::INT16: {
            func = BinaryComparisonExecFunction<int16_t, int16_t, uint8_t, FUNC>;
        } break;
        case common::PhysicalTypeID::INT8: {
            func = BinaryComparisonExecFunction<int8_t, int8_t, uint8_t, FUNC>;
        } break;
        case common::PhysicalTypeID::UINT64: {
            func = BinaryComparisonExecFunction<uint64_t, uint64_t, uint8_t, FUNC>;
        } break;
        case common::PhysicalTypeID::UINT32: {
            func = BinaryComparisonExecFunction<uint32_t, uint32_t, uint8_t, FUNC>;
        } break;
        case common::PhysicalTypeID::UINT16: {
            func = BinaryComparisonExecFunction<uint16_t, uint16_t, uint8_t, FUNC>;
        } break;
        case common::PhysicalTypeID::UINT8: {
            func = BinaryComparisonExecFunction<uint8_t, uint8_t, uint8_t, FUNC>;
        } break;
        case common::PhysicalTypeID::INT128: {
            func = BinaryComparisonExecFunction<common::int128_t, common::int128_t, uint8_t, FUNC>;
        } break;
        case common::PhysicalTypeID::DOUBLE: {
            func = BinaryComparisonExecFunction<double, double, uint8_t, FUNC>;
        } break;
        case common::PhysicalTypeID::FLOAT: {
            func = BinaryComparisonExecFunction<float, float, uint8_t, FUNC>;
        } break;
        case common::PhysicalTypeID::BOOL: {
            func = BinaryComparisonExecFunction<uint8_t, uint8_t, uint8_t, FUNC>;
        } break;
        case common::PhysicalTypeID::STRING: {
            func = BinaryComparisonExecFunction<common::ku_string_t, common::ku_string_t, uint8_t,
                FUNC>;
        } break;
        case common::PhysicalTypeID::INTERNAL_ID: {
            func = BinaryComparisonExecFunction<common::nodeID_t, common::nodeID_t, uint8_t, FUNC>;
        } break;
        case common::PhysicalTypeID::UINT128: {
            func =
                BinaryComparisonExecFunction<common::uint128_t, common::uint128_t, uint8_t, FUNC>;
        } break;
        case common::PhysicalTypeID::INTERVAL: {
            func =
                BinaryComparisonExecFunction<common::interval_t, common::interval_t, uint8_t, FUNC>;
        } break;
        case common::PhysicalTypeID::ARRAY:
        case common::PhysicalTypeID::LIST: {
            func = BinaryComparisonExecFunction<common::list_entry_t, common::list_entry_t, uint8_t,
                FUNC>;
        } break;
        case common::PhysicalTypeID::STRUCT: {
            func = BinaryComparisonExecFunction<common::struct_entry_t, common::struct_entry_t,
                uint8_t, FUNC>;
        } break;
        default:
            throw common::RuntimeException(
                "Invalid input data types(" + common::PhysicalTypeUtils::toString(leftType) + "," +
                common::PhysicalTypeUtils::toString(rightType) + ") for getExecFunc.");
        }
    }

    template<typename FUNC>
    static void getSelectFunc(common::PhysicalTypeID leftTypeID, common::PhysicalTypeID rightTypeID,
        scalar_func_select_t& func) {
        KU_ASSERT(leftTypeID == rightTypeID);
        switch (leftTypeID) {
        case common::PhysicalTypeID::INT64: {
            func = BinaryComparisonSelectFunction<int64_t, int64_t, FUNC>;
        } break;
        case common::PhysicalTypeID::INT32: {
            func = BinaryComparisonSelectFunction<int32_t, int32_t, FUNC>;
        } break;
        case common::PhysicalTypeID::INT16: {
            func = BinaryComparisonSelectFunction<int16_t, int16_t, FUNC>;
        } break;
        case common::PhysicalTypeID::INT8: {
            func = BinaryComparisonSelectFunction<int8_t, int8_t, FUNC>;
        } break;
        case common::PhysicalTypeID::UINT64: {
            func = BinaryComparisonSelectFunction<uint64_t, uint64_t, FUNC>;
        } break;
        case common::PhysicalTypeID::UINT32: {
            func = BinaryComparisonSelectFunction<uint32_t, uint32_t, FUNC>;
        } break;
        case common::PhysicalTypeID::UINT16: {
            func = BinaryComparisonSelectFunction<uint16_t, uint16_t, FUNC>;
        } break;
        case common::PhysicalTypeID::UINT8: {
            func = BinaryComparisonSelectFunction<uint8_t, uint8_t, FUNC>;
        } break;
        case common::PhysicalTypeID::INT128: {
            func = BinaryComparisonSelectFunction<common::int128_t, common::int128_t, FUNC>;
        } break;
        case common::PhysicalTypeID::DOUBLE: {
            func = BinaryComparisonSelectFunction<double, double, FUNC>;
        } break;
        case common::PhysicalTypeID::FLOAT: {
            func = BinaryComparisonSelectFunction<float, float, FUNC>;
        } break;
        case common::PhysicalTypeID::BOOL: {
            func = BinaryComparisonSelectFunction<uint8_t, uint8_t, FUNC>;
        } break;
        case common::PhysicalTypeID::STRING: {
            func = BinaryComparisonSelectFunction<common::ku_string_t, common::ku_string_t, FUNC>;
        } break;
        case common::PhysicalTypeID::INTERNAL_ID: {
            func = BinaryComparisonSelectFunction<common::nodeID_t, common::nodeID_t, FUNC>;
        } break;
        case common::PhysicalTypeID::UINT128: {
            func = BinaryComparisonSelectFunction<common::uint128_t, common::uint128_t, FUNC>;
        } break;
        case common::PhysicalTypeID::INTERVAL: {
            func = BinaryComparisonSelectFunction<common::interval_t, common::interval_t, FUNC>;
        } break;
        case common::PhysicalTypeID::ARRAY:
        case common::PhysicalTypeID::LIST: {
            func = BinaryComparisonSelectFunction<common::list_entry_t, common::list_entry_t, FUNC>;
        } break;
        case common::PhysicalTypeID::STRUCT: {
            func = BinaryComparisonSelectFunction<common::struct_entry_t, common::struct_entry_t,
                FUNC>;
        } break;
        default:
            throw common::RuntimeException(
                "Invalid input data types(" + common::PhysicalTypeUtils::toString(leftTypeID) +
                "," + common::PhysicalTypeUtils::toString(rightTypeID) + ") for getSelectFunc.");
        }
    }
};

struct EqualsFunction {
    static constexpr const char* name = "EQUALS";

    static function_set getFunctionSet() {
        return ComparisonFunction::getFunctionSet<Equals>(name);
    }
};

struct NotEqualsFunction {
    static constexpr const char* name = "NOT_EQUALS";

    static function_set getFunctionSet() {
        return ComparisonFunction::getFunctionSet<NotEquals>(name);
    }
};

struct GreaterThanFunction {
    static constexpr const char* name = "GREATER_THAN";

    static function_set getFunctionSet() {
        return ComparisonFunction::getFunctionSet<GreaterThan>(name);
    }
};

struct GreaterThanEqualsFunction {
    static constexpr const char* name = "GREATER_THAN_EQUALS";

    static function_set getFunctionSet() {
        return ComparisonFunction::getFunctionSet<GreaterThanEquals>(name);
    }
};

struct LessThanFunction {
    static constexpr const char* name = "LESS_THAN";

    static function_set getFunctionSet() {
        return ComparisonFunction::getFunctionSet<LessThan>(name);
    }
};

struct LessThanEqualsFunction {
    static constexpr const char* name = "LESS_THAN_EQUALS";

    static function_set getFunctionSet() {
        return ComparisonFunction::getFunctionSet<LessThanEquals>(name);
    }
};

} // namespace function
} // namespace lbug
