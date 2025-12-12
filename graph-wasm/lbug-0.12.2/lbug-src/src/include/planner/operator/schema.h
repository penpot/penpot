#pragma once

#include <unordered_map>

#include "binder/expression/expression.h"

namespace lbug {
namespace planner {

using f_group_pos = uint32_t;
using f_group_pos_set = std::unordered_set<f_group_pos>;
constexpr f_group_pos INVALID_F_GROUP_POS = UINT32_MAX;

class FactorizationGroup {
    friend class Schema;
    friend class CardinalityEstimator;

public:
    FactorizationGroup() : flat{false}, singleState{false}, cardinalityMultiplier{1} {}
    FactorizationGroup(const FactorizationGroup& other)
        : flat{other.flat}, singleState{other.singleState},
          cardinalityMultiplier{other.cardinalityMultiplier}, expressions{other.expressions},
          expressionNameToPos{other.expressionNameToPos} {}

    void setFlat() {
        KU_ASSERT(!flat);
        flat = true;
    }
    bool isFlat() const { return flat; }
    void setSingleState() {
        KU_ASSERT(!singleState);
        singleState = true;
        setFlat();
    }
    bool isSingleState() const { return singleState; }

    void setMultiplier(double multiplier) { cardinalityMultiplier = multiplier; }
    double getMultiplier() const { return cardinalityMultiplier; }

    void insertExpression(const std::shared_ptr<binder::Expression>& expression) {
        KU_ASSERT(!expressionNameToPos.contains(expression->getUniqueName()));
        expressionNameToPos.insert({expression->getUniqueName(), expressions.size()});
        expressions.push_back(expression);
    }
    binder::expression_vector getExpressions() const { return expressions; }
    uint32_t getExpressionPos(const binder::Expression& expression) const {
        KU_ASSERT(expressionNameToPos.contains(expression.getUniqueName()));
        return expressionNameToPos.at(expression.getUniqueName());
    }

private:
    bool flat;
    bool singleState;
    double cardinalityMultiplier;
    binder::expression_vector expressions;
    std::unordered_map<std::string, uint32_t> expressionNameToPos;
};

class Schema {
public:
    common::idx_t getNumGroups() const { return groups.size(); }

    FactorizationGroup* getGroup(const std::shared_ptr<binder::Expression>& expression) const {
        return getGroup(getGroupPos(expression->getUniqueName()));
    }

    FactorizationGroup* getGroup(const std::string& expressionName) const {
        return getGroup(getGroupPos(expressionName));
    }

    FactorizationGroup* getGroup(uint32_t pos) const { return groups[pos].get(); }

    f_group_pos createGroup();

    void insertToScope(const std::shared_ptr<binder::Expression>& expression, uint32_t groupPos);
    void insertToGroupAndScope(const std::shared_ptr<binder::Expression>& expression,
        uint32_t groupPos);
    // Use these unsafe insert functions only if the operator may work with duplicate expressions.
    // E.g. group by a.age, a.age
    void insertToScopeMayRepeat(const std::shared_ptr<binder::Expression>& expression,
        uint32_t groupPos);
    void insertToGroupAndScopeMayRepeat(const std::shared_ptr<binder::Expression>& expression,
        uint32_t groupPos);

    void insertToGroupAndScope(const binder::expression_vector& expressions, uint32_t groupPos);

    f_group_pos getGroupPos(const binder::Expression& expression) const {
        return getGroupPos(expression.getUniqueName());
    }

    f_group_pos getGroupPos(const std::string& expressionName) const;

    std::pair<f_group_pos, uint32_t> getExpressionPos(const binder::Expression& expression) const {
        auto groupPos = getGroupPos(expression);
        return std::make_pair(groupPos, groups[groupPos]->getExpressionPos(expression));
    }

    void flattenGroup(f_group_pos pos) { groups[pos]->setFlat(); }
    void setGroupAsSingleState(f_group_pos pos) { groups[pos]->setSingleState(); }

    bool isExpressionInScope(const binder::Expression& expression) const;

    binder::expression_vector getExpressionsInScope() const { return expressionsInScope; }

    binder::expression_vector getExpressionsInScope(f_group_pos pos) const;

    bool evaluable(const binder::Expression& expression) const;

    void clearExpressionsInScope() {
        expressionNameToGroupPos.clear();
        expressionsInScope.clear();
    }

    // Get the group positions containing at least one expression in scope.
    f_group_pos_set getGroupsPosInScope() const;

    LBUG_API std::unique_ptr<Schema> copy() const;

    void clear();

private:
    size_t getNumGroups(bool isFlat) const;

private:
    std::vector<std::unique_ptr<FactorizationGroup>> groups;
    std::unordered_map<std::string, uint32_t> expressionNameToGroupPos;
    // Our projection doesn't explicitly remove expressions. Instead, we keep track of what
    // expressions are in scope (i.e. being projected).
    binder::expression_vector expressionsInScope;
};

class SchemaUtils {
public:
    // Given a set of factorization group, a leading group is selected as the unFlat group (caller
    // should ensure at most one unFlat group which is our general assumption of factorization). If
    // all groups are flat, we select any (the first) group as leading group.
    static f_group_pos getLeadingGroupPos(const std::unordered_set<f_group_pos>& groupPositions,
        const Schema& schema);

    static void validateAtMostOneUnFlatGroup(const std::unordered_set<f_group_pos>& groupPositions,
        const Schema& schema);
    static void validateNoUnFlatGroup(const std::unordered_set<f_group_pos>& groupPositions,
        const Schema& schema);
};

} // namespace planner
} // namespace lbug
