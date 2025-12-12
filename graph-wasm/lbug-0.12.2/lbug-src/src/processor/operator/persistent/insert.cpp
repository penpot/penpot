#include "processor/operator/persistent/insert.h"

#include "binder/expression/expression_util.h"

using namespace lbug::common;
using namespace lbug::storage;

namespace lbug {
namespace processor {

std::string InsertPrintInfo::toString() const {
    std::string result = "Expressions: ";
    result += binder::ExpressionUtil::toString(expressions);
    result += ", Action: ";
    result += ConflictActionUtil::toString(action);
    return result;
}

void Insert::initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) {
    for (auto& executor : nodeExecutors) {
        executor.init(resultSet, context);
    }
    for (auto& executor : relExecutors) {
        executor.init(resultSet, context);
    }
}

bool Insert::getNextTuplesInternal(ExecutionContext* context) {
    if (!children[0]->getNextTuple(context)) {
        return false;
    }
    for (auto& executor : nodeExecutors) {
        executor.insert(context->clientContext);
    }
    for (auto& executor : relExecutors) {
        executor.insert(context->clientContext);
    }
    return true;
}

} // namespace processor
} // namespace lbug
