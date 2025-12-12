#include "planner/operator/schema.h"

#include "binder/expression_visitor.h"
#include "common/exception/internal.h"

using namespace lbug::binder;
using namespace lbug::common;

namespace lbug {
namespace planner {

f_group_pos Schema::createGroup() {
    auto pos = groups.size();
    groups.push_back(std::make_unique<FactorizationGroup>());
    return pos;
}

void Schema::insertToScope(const std::shared_ptr<Expression>& expression, f_group_pos groupPos) {
    KU_ASSERT(!expressionNameToGroupPos.contains(expression->getUniqueName()));
    expressionNameToGroupPos.insert({expression->getUniqueName(), groupPos});
    KU_ASSERT(getGroup(groupPos)->expressionNameToPos.contains(expression->getUniqueName()));
    expressionsInScope.push_back(expression);
}

void Schema::insertToGroupAndScope(const std::shared_ptr<Expression>& expression,
    f_group_pos groupPos) {
    KU_ASSERT(!expressionNameToGroupPos.contains(expression->getUniqueName()));
    expressionNameToGroupPos.insert({expression->getUniqueName(), groupPos});
    groups[groupPos]->insertExpression(expression);
    expressionsInScope.push_back(expression);
}

void Schema::insertToScopeMayRepeat(const std::shared_ptr<Expression>& expression,
    uint32_t groupPos) {
    if (expressionNameToGroupPos.contains(expression->getUniqueName())) {
        return;
    }
    insertToScope(expression, groupPos);
}

void Schema::insertToGroupAndScopeMayRepeat(const std::shared_ptr<Expression>& expression,
    uint32_t groupPos) {
    if (expressionNameToGroupPos.contains(expression->getUniqueName())) {
        return;
    }
    insertToGroupAndScope(expression, groupPos);
}

void Schema::insertToGroupAndScope(const expression_vector& expressions, f_group_pos groupPos) {
    for (auto& expression : expressions) {
        insertToGroupAndScope(expression, groupPos);
    }
}

f_group_pos Schema::getGroupPos(const std::string& expressionName) const {
    KU_ASSERT(expressionNameToGroupPos.contains(expressionName));
    return expressionNameToGroupPos.at(expressionName);
}

bool Schema::isExpressionInScope(const Expression& expression) const {
    for (auto& expressionInScope : expressionsInScope) {
        if (expressionInScope->getUniqueName() == expression.getUniqueName()) {
            return true;
        }
    }
    return false;
}

expression_vector Schema::getExpressionsInScope(f_group_pos pos) const {
    expression_vector result;
    for (auto& expression : expressionsInScope) {
        if (getGroupPos(expression->getUniqueName()) == pos) {
            result.push_back(expression);
        }
    }
    return result;
}

bool Schema::evaluable(const Expression& expression) const {
    auto inScope = isExpressionInScope(expression);
    if (expression.expressionType == ExpressionType::LITERAL || inScope) {
        return true;
    }
    auto children = ExpressionChildrenCollector::collectChildren(expression);
    if (children.empty()) {
        return inScope;
    } else {
        for (auto& child : children) {
            if (!evaluable(*child)) {
                return false;
            }
        }
        return true;
    }
}

std::unordered_set<f_group_pos> Schema::getGroupsPosInScope() const {
    std::unordered_set<f_group_pos> result;
    for (auto& expressionInScope : expressionsInScope) {
        result.insert(getGroupPos(expressionInScope->getUniqueName()));
    }
    return result;
}

std::unique_ptr<Schema> Schema::copy() const {
    auto newSchema = std::make_unique<Schema>();
    newSchema->expressionNameToGroupPos = expressionNameToGroupPos;
    for (auto& group : groups) {
        newSchema->groups.push_back(std::make_unique<FactorizationGroup>(*group));
    }
    newSchema->expressionsInScope = expressionsInScope;
    return newSchema;
}

void Schema::clear() {
    groups.clear();
    clearExpressionsInScope();
}

size_t Schema::getNumGroups(bool isFlat) const {
    auto result = 0u;
    for (auto groupPos : getGroupsPosInScope()) {
        result += groups[groupPos]->isFlat() == isFlat;
    }
    return result;
}

f_group_pos SchemaUtils::getLeadingGroupPos(const std::unordered_set<f_group_pos>& groupPositions,
    const Schema& schema) {
    auto leadingGroupPos = INVALID_F_GROUP_POS;
    for (auto groupPos : groupPositions) {
        if (!schema.getGroup(groupPos)->isFlat()) {
            return groupPos;
        }
        leadingGroupPos = groupPos;
    }
    return leadingGroupPos;
}

void SchemaUtils::validateAtMostOneUnFlatGroup(
    const std::unordered_set<f_group_pos>& groupPositions, const Schema& schema) {
    auto hasUnFlatGroup = false;
    for (auto groupPos : groupPositions) {
        if (!schema.getGroup(groupPos)->isFlat()) {
            if (hasUnFlatGroup) {
                throw InternalException("Unexpected multiple unFlat factorization groups found.");
            }
            hasUnFlatGroup = true;
        }
    }
}

void SchemaUtils::validateNoUnFlatGroup(const std::unordered_set<f_group_pos>& groupPositions,
    const Schema& schema) {
    for (auto groupPos : groupPositions) {
        if (!schema.getGroup(groupPos)->isFlat()) {
            throw InternalException("Unexpected unFlat factorization group found.");
        }
    }
}

} // namespace planner
} // namespace lbug
