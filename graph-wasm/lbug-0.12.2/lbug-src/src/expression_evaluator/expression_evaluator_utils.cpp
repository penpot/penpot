#include "expression_evaluator/expression_evaluator_utils.h"

#include "common/types/value/value.h"
#include "processor/expression_mapper.h"

using namespace lbug::common;
using namespace lbug::processor;

namespace lbug {
namespace evaluator {

Value ExpressionEvaluatorUtils::evaluateConstantExpression(
    std::shared_ptr<binder::Expression> expression, main::ClientContext* clientContext) {
    auto exprMapper = ExpressionMapper();
    auto evaluator = exprMapper.getConstantEvaluator(expression);
    auto emptyResultSet = std::make_unique<ResultSet>(0);
    evaluator->init(*emptyResultSet, clientContext);
    evaluator->evaluate();
    auto& selVector = evaluator->resultVector->state->getSelVector();
    KU_ASSERT(selVector.getSelSize() == 1);
    return *evaluator->resultVector->getAsValue(selVector[0]);
}

} // namespace evaluator
} // namespace lbug
