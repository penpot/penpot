#include "binder/binder_scope.h"

namespace lbug {
namespace binder {

void BinderScope::addExpression(const std::string& varName,
    std::shared_ptr<Expression> expression) {
    nameToExprIdx.insert({varName, expressions.size()});
    expressions.push_back(std::move(expression));
}

void BinderScope::replaceExpression(const std::string& oldName, const std::string& newName,
    std::shared_ptr<Expression> expression) {
    KU_ASSERT(nameToExprIdx.contains(oldName));
    auto idx = nameToExprIdx.at(oldName);
    expressions[idx] = std::move(expression);
    nameToExprIdx.erase(oldName);
    nameToExprIdx.insert({newName, idx});
}

void BinderScope::clear() {
    expressions.clear();
    nameToExprIdx.clear();
}

} // namespace binder
} // namespace lbug
