#pragma once

#include "binder/expression/expression.h"
#include "binder/expression/node_expression.h"
#include "common/case_insensitive_map.h"

namespace lbug {
namespace binder {

class BinderScope {
public:
    BinderScope() = default;
    EXPLICIT_COPY_DEFAULT_MOVE(BinderScope);

    bool empty() const { return expressions.empty(); }
    bool contains(const std::string& varName) const { return nameToExprIdx.contains(varName); }
    std::shared_ptr<Expression> getExpression(const std::string& varName) const {
        KU_ASSERT(nameToExprIdx.contains(varName));
        return expressions[nameToExprIdx.at(varName)];
    }
    expression_vector getExpressions() const { return expressions; }
    void addExpression(const std::string& varName, std::shared_ptr<Expression> expression);
    void replaceExpression(const std::string& oldName, const std::string& newName,
        std::shared_ptr<Expression> expression);

    void memorizeTableEntries(const std::string& name,
        std::vector<catalog::TableCatalogEntry*> entries) {
        memorizedNodeNameToEntries.insert({name, entries});
    }
    bool hasMemorizedTableIDs(const std::string& name) const {
        return memorizedNodeNameToEntries.contains(name);
    }
    std::vector<catalog::TableCatalogEntry*> getMemorizedTableEntries(const std::string& name) {
        KU_ASSERT(memorizedNodeNameToEntries.contains(name));
        return memorizedNodeNameToEntries.at(name);
    }

    void addNodeReplacement(std::shared_ptr<NodeExpression> node) {
        nodeReplacement.insert({node->getVariableName(), node});
    }
    bool hasNodeReplacement(const std::string& name) const {
        return nodeReplacement.contains(name);
    }
    std::shared_ptr<NodeExpression> getNodeReplacement(const std::string& name) const {
        KU_ASSERT(hasNodeReplacement(name));
        return nodeReplacement.at(name);
    }

    void clear();

private:
    BinderScope(const BinderScope& other)
        : expressions{other.expressions}, nameToExprIdx{other.nameToExprIdx},
          memorizedNodeNameToEntries{other.memorizedNodeNameToEntries} {}

private:
    // Expressions in scope. Order should be preserved.
    expression_vector expressions;
    common::case_insensitive_map_t<common::idx_t> nameToExprIdx;
    // A node might be popped out of scope. But we may need to retain its table ID information.
    // E.g. MATCH (a:person) WITH collect(a) AS list_a UNWIND list_a AS new_a MATCH (new_a)-[]->()
    // It will be more performant if we can retain the information that new_a has label person.
    common::case_insensitive_map_t<std::vector<catalog::TableCatalogEntry*>>
        memorizedNodeNameToEntries;
    // A node pattern may not always be bound as a node expression, e.g. in the above query,
    // (new_a) is bound as a variable rather than node expression.
    common::case_insensitive_map_t<std::shared_ptr<NodeExpression>> nodeReplacement;
};

} // namespace binder
} // namespace lbug
