#include "binder/binder.h"
#include "binder/query/reading_clause/bound_table_function_call.h"
#include "catalog/catalog.h"
#include "common/exception/binder.h"
#include "parser/expression/parsed_function_expression.h"
#include "parser/query/reading_clause/in_query_call_clause.h"
#include "transaction/transaction.h"

using namespace lbug::common;
using namespace lbug::catalog;
using namespace lbug::parser;
using namespace lbug::function;
using namespace lbug::catalog;

namespace lbug {
namespace binder {

std::unique_ptr<BoundReadingClause> Binder::bindInQueryCall(const ReadingClause& readingClause) {
    auto& call = readingClause.constCast<InQueryCallClause>();
    auto expr = call.getFunctionExpression();
    auto functionExpr = expr->constPtrCast<ParsedFunctionExpression>();
    auto functionName = functionExpr->getFunctionName();
    std::unique_ptr<BoundReadingClause> boundReadingClause;
    auto transaction = transaction::Transaction::Get(*clientContext);
    auto entry = Catalog::Get(*clientContext)->getFunctionEntry(transaction, functionName);
    switch (entry->getType()) {
    case CatalogEntryType::TABLE_FUNCTION_ENTRY: {
        auto boundTableFunction =
            bindTableFunc(functionName, *functionExpr, call.getYieldVariables());
        boundReadingClause =
            std::make_unique<BoundTableFunctionCall>(std::move(boundTableFunction));
    } break;
    default:
        throw BinderException(
            stringFormat("{} is not a table or algorithm function.", functionName));
    }
    if (call.hasWherePredicate()) {
        auto wherePredicate = bindWhereExpression(*call.getWherePredicate());
        boundReadingClause->setPredicate(std::move(wherePredicate));
    }
    return boundReadingClause;
}

} // namespace binder
} // namespace lbug
