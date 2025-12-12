#include "main/prepared_statement.h"

#include "binder/expression/expression.h" // IWYU pragma: keep
#include "common/exception/binder.h"
#include "common/types/value/value.h"
#include "planner/operator/logical_plan.h" // IWYU pragma: keep

using namespace lbug::common;

namespace lbug {
namespace main {

CachedPreparedStatement::CachedPreparedStatement() = default;
CachedPreparedStatement::~CachedPreparedStatement() = default;

std::vector<std::string> CachedPreparedStatement::getColumnNames() const {
    std::vector<std::string> names;
    for (auto& column : columns) {
        names.push_back(column->toString());
    }
    return names;
}

std::vector<LogicalType> CachedPreparedStatement::getColumnTypes() const {
    std::vector<LogicalType> types;
    for (auto& column : columns) {
        types.push_back(column->getDataType().copy());
    }
    return types;
}

bool PreparedStatement::isSuccess() const {
    return success;
}

std::string PreparedStatement::getErrorMessage() const {
    return errMsg;
}

bool PreparedStatement::isReadOnly() const {
    return readOnly;
}

StatementType PreparedStatement::getStatementType() const {
    return preparedSummary.statementType;
}

static void validateParam(const std::string& paramName, Value* newVal, Value* oldVal) {
    if (newVal->getDataType().getLogicalTypeID() == LogicalTypeID::POINTER &&
        newVal->getValue<uint8_t*>() != oldVal->getValue<uint8_t*>()) {
        throw BinderException(stringFormat(
            "When preparing the current statement the dataframe passed into parameter "
            "'{}' was different from the one provided during prepare. Dataframes parameters "
            "are only used during prepare; please make sure that they are either not passed into "
            "execute or they match the one passed during prepare.",
            paramName));
    }
}

std::unordered_set<std::string> PreparedStatement::getKnownParameters() {
    std::unordered_set<std::string> result;
    for (auto& [k, _] : parameterMap) {
        result.insert(k);
    }
    return result;
}

void PreparedStatement::updateParameter(const std::string& name, Value* value) {
    KU_ASSERT(parameterMap.contains(name));
    validateParam(name, value, parameterMap.at(name).get());
    *parameterMap.at(name) = std::move(*value);
}

void PreparedStatement::addParameter(const std::string& name, Value* value) {
    parameterMap.insert({name, std::make_shared<Value>(*value)});
}

PreparedStatement::~PreparedStatement() = default;

std::unique_ptr<PreparedStatement> PreparedStatement::getPreparedStatementWithError(
    const std::string& errorMessage) {
    auto preparedStatement = std::make_unique<PreparedStatement>();
    preparedStatement->success = false;
    preparedStatement->errMsg = errorMessage;
    return preparedStatement;
}

} // namespace main
} // namespace lbug
