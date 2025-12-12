#pragma once

#include "planner/operator/schema.h"

namespace lbug {
namespace planner {

class GroupDependencyAnalyzer;

struct FlattenAllButOne {
    static std::pair<f_group_pos, f_group_pos_set> getGroupsPosToFlatten(
        const binder::expression_vector& exprs, const Schema& schema);
    static f_group_pos_set getGroupsPosToFlatten(std::shared_ptr<binder::Expression> expr,
        const Schema& schema);
    // Assume no requiredFlatGroups
    static f_group_pos_set getGroupsPosToFlatten(
        const std::unordered_set<f_group_pos>& dependentGroups, const Schema& schema);
};

struct FlattenAll {
    static f_group_pos_set getGroupsPosToFlatten(const binder::expression_vector& exprs,
        const Schema& schema);
    static f_group_pos_set getGroupsPosToFlatten(std::shared_ptr<binder::Expression> expr,
        const Schema& schema);
    static f_group_pos_set getGroupsPosToFlatten(
        const std::unordered_set<f_group_pos>& dependentGroups, const Schema& schema);
};

class GroupDependencyAnalyzer {
public:
    GroupDependencyAnalyzer(bool collectDependentExpr, const Schema& schema)
        : collectDependentExpr{collectDependentExpr}, schema{schema} {}

    binder::expression_vector getDependentExprs() const {
        return binder::expression_vector{dependentExprs.begin(), dependentExprs.end()};
    }
    std::unordered_set<f_group_pos> getDependentGroups() const { return dependentGroups; }
    std::unordered_set<f_group_pos> getRequiredFlatGroups() const { return requiredFlatGroups; }

    void visit(std::shared_ptr<binder::Expression> expr);

private:
    void visitFunction(std::shared_ptr<binder::Expression> expr);

    void visitCase(std::shared_ptr<binder::Expression> expr);

    void visitNodeOrRel(std::shared_ptr<binder::Expression> expr);

    void visitSubquery(std::shared_ptr<binder::Expression> expr);

private:
    bool collectDependentExpr;
    const Schema& schema;
    std::unordered_set<f_group_pos> dependentGroups;
    std::unordered_set<f_group_pos> requiredFlatGroups;
    binder::expression_set dependentExprs;
};

} // namespace planner
} // namespace lbug
