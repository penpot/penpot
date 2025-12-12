#pragma once

#include "aggregate_function.h"
#include "catalog/catalog_entry/catalog_entry_type.h"
#include "function.h"

namespace lbug {
namespace transaction {
class Transaction;
} // namespace transaction

namespace catalog {
class FunctionCatalogEntry;
} // namespace catalog

namespace function {

class BuiltInFunctionsUtils {
public:
    // TODO(Ziyi): We should have a unified interface for matching table, aggregate and scalar
    // functions.
    static LBUG_API Function* matchFunction(const std::string& name,
        const catalog::FunctionCatalogEntry* catalogEntry) {
        return matchFunction(name, {}, catalogEntry);
    }
    static LBUG_API Function* matchFunction(const std::string& name,
        const std::vector<common::LogicalType>& inputTypes,
        const catalog::FunctionCatalogEntry* functionEntry);

    static AggregateFunction* matchAggregateFunction(const std::string& name,
        const std::vector<common::LogicalType>& inputTypes, bool isDistinct,
        const catalog::FunctionCatalogEntry* functionEntry);

    static LBUG_API uint32_t getCastCost(common::LogicalTypeID inputTypeID,
        common::LogicalTypeID targetTypeID);

    static LBUG_API std::string getFunctionMatchFailureMsg(const std::string name,
        const std::vector<common::LogicalType>& inputTypes, const std::string& supportedInputs,
        bool isDistinct = false);

private:
    // TODO(Xiyang): move casting cost related functions to binder.
    static uint32_t getTargetTypeCost(common::LogicalTypeID typeID);

    static uint32_t castInt64(common::LogicalTypeID targetTypeID);

    static uint32_t castInt32(common::LogicalTypeID targetTypeID);

    static uint32_t castInt16(common::LogicalTypeID targetTypeID);

    static uint32_t castInt8(common::LogicalTypeID targetTypeID);

    static uint32_t castUInt64(common::LogicalTypeID targetTypeID);

    static uint32_t castUInt32(common::LogicalTypeID targetTypeID);

    static uint32_t castUInt16(common::LogicalTypeID targetTypeID);

    static uint32_t castUInt8(common::LogicalTypeID targetTypeID);

    static uint32_t castInt128(common::LogicalTypeID targetTypeID);

    static uint32_t castDouble(common::LogicalTypeID targetTypeID);

    static uint32_t castFloat(common::LogicalTypeID targetTypeID);

    static uint32_t castDecimal(common::LogicalTypeID targetTypeID);

    static uint32_t castDate(common::LogicalTypeID targetTypeID);

    static uint32_t castSerial(common::LogicalTypeID targetTypeID);

    static uint32_t castTimestamp(common::LogicalTypeID targetTypeID);

    static uint32_t castFromString(common::LogicalTypeID inputTypeID);

    static uint32_t castUUID(common::LogicalTypeID targetTypeID);

    static uint32_t castList(common::LogicalTypeID targetTypeID);

    static uint32_t castArray(common::LogicalTypeID targetTypeID);

    static Function* getBestMatch(std::vector<Function*>& functions);

    static uint32_t getFunctionCost(const std::vector<common::LogicalType>& inputTypes,
        Function* function, catalog::CatalogEntryType type);
    static uint32_t matchParameters(const std::vector<common::LogicalType>& inputTypes,
        const std::vector<common::LogicalTypeID>& targetTypeIDs);
    static uint32_t matchVarLengthParameters(const std::vector<common::LogicalType>& inputTypes,
        common::LogicalTypeID targetTypeID);
    static uint32_t getAggregateFunctionCost(const std::vector<common::LogicalType>& inputTypes,
        bool isDistinct, AggregateFunction* function);

    static void validateSpecialCases(std::vector<Function*>& candidateFunctions,
        const std::string& name, const std::vector<common::LogicalType>& inputTypes,
        const function::function_set& set);
};

} // namespace function
} // namespace lbug
