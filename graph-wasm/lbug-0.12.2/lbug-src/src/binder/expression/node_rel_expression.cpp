#include "binder/expression/node_rel_expression.h"

#include "catalog/catalog_entry/table_catalog_entry.h"

using namespace lbug::catalog;
using namespace lbug::common;

namespace lbug {
namespace binder {

table_id_vector_t NodeOrRelExpression::getTableIDs() const {
    table_id_vector_t result;
    for (auto& entry : entries) {
        result.push_back(entry->getTableID());
    }
    return result;
}

table_id_set_t NodeOrRelExpression::getTableIDsSet() const {
    table_id_set_t result;
    for (auto& entry : entries) {
        result.insert(entry->getTableID());
    }
    return result;
}

void NodeOrRelExpression::addEntries(const std::vector<TableCatalogEntry*>& entries_) {
    auto tableIDsSet = getTableIDsSet();
    for (auto& entry : entries_) {
        if (!tableIDsSet.contains(entry->getTableID())) {
            entries.push_back(entry);
        }
    }
}

void NodeOrRelExpression::addPropertyExpression(std::shared_ptr<PropertyExpression> property) {
    auto propertyName = property->getPropertyName();
    KU_ASSERT(!propertyNameToIdx.contains(propertyName));
    propertyNameToIdx.insert({propertyName, propertyExprs.size()});
    propertyExprs.push_back(std::move(property));
}

} // namespace binder
} // namespace lbug
