#include "binder/binder.h"
#include "binder/bound_table_scan_info.h"
#include "binder/expression/expression_util.h"
#include "binder/expression/literal_expression.h"
#include "catalog/catalog.h"
#include "function/built_in_function_utils.h"
#include "main/client_context.h"
#include "transaction/transaction.h"

using namespace lbug::common;
using namespace lbug::function;

namespace lbug {
namespace binder {

BoundTableScanInfo Binder::bindTableFunc(const std::string& tableFuncName,
    const parser::ParsedExpression& expr, std::vector<parser::YieldVariable> yieldVariables) {
    auto catalog = catalog::Catalog::Get(*clientContext);
    auto transaction = transaction::Transaction::Get(*clientContext);
    auto entry = catalog->getFunctionEntry(transaction, tableFuncName,
        clientContext->useInternalCatalogEntry());
    expression_vector positionalParams;
    std::vector<LogicalType> positionalParamTypes;
    optional_params_t optionalParams;
    expression_vector optionalParamsLegacy;
    for (auto i = 0u; i < expr.getNumChildren(); i++) {
        auto& childExpr = *expr.getChild(i);
        auto param = expressionBinder.bindExpression(childExpr);
        ExpressionUtil::validateExpressionType(*param,
            {ExpressionType::LITERAL, ExpressionType::PARAMETER, ExpressionType::PATTERN});
        if (!childExpr.hasAlias()) {
            positionalParams.push_back(param);
            positionalParamTypes.push_back(param->getDataType().copy());
        } else {
            if (param->expressionType == ExpressionType::LITERAL) {
                auto literalExpr = param->constPtrCast<LiteralExpression>();
                optionalParams.emplace(childExpr.getAlias(), literalExpr->getValue());
            }
            param->setAlias(expr.getChild(i)->getAlias());
            optionalParamsLegacy.push_back(param);
        }
    }
    auto func = BuiltInFunctionsUtils::matchFunction(tableFuncName, positionalParamTypes,
        entry->ptrCast<catalog::FunctionCatalogEntry>());
    auto tableFunc = func->constPtrCast<TableFunction>();
    std::vector<LogicalType> inputTypes;
    if (tableFunc->inferInputTypes) {
        // For functions which take in nested data types, we have to use the input parameters to
        // detect the input types. (E.g. query_hnsw_index takes in an ARRAY which needs the user
        // input parameters to decide the array dimension).
        inputTypes = tableFunc->inferInputTypes(positionalParams);
    } else {
        // For functions which don't have nested type parameters, we can simply use the types
        // declared in the function signature.
        for (auto i = 0u; i < tableFunc->parameterTypeIDs.size(); i++) {
            inputTypes.push_back(LogicalType(tableFunc->parameterTypeIDs[i]));
        }
    }
    for (auto i = 0u; i < positionalParams.size(); ++i) {
        auto parameterTypeID = tableFunc->parameterTypeIDs[i];
        if (positionalParams[i]->expressionType == ExpressionType::LITERAL &&
            parameterTypeID != LogicalTypeID::ANY) {
            positionalParams[i] = expressionBinder.foldExpression(
                expressionBinder.implicitCastIfNecessary(positionalParams[i], inputTypes[i]));
        }
    }
    auto bindInput = TableFuncBindInput();
    bindInput.params = std::move(positionalParams);
    bindInput.optionalParams = std::move(optionalParams);
    bindInput.optionalParamsLegacy = std::move(optionalParamsLegacy);
    bindInput.binder = this;
    bindInput.yieldVariables = std::move(yieldVariables);
    return BoundTableScanInfo{*tableFunc, tableFunc->bindFunc(clientContext, &bindInput)};
}

} // namespace binder
} // namespace lbug
