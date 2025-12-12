#pragma once

#include "common/case_insensitive_map.h"
#include "expression.h"
#include "property_expression.h"

namespace lbug {
namespace catalog {
class TableCatalogEntry;
}
namespace binder {

class LBUG_API NodeOrRelExpression : public Expression {
    static constexpr common::ExpressionType expressionType_ = common::ExpressionType::PATTERN;

public:
    NodeOrRelExpression(common::LogicalType dataType, std::string uniqueName,
        std::string variableName, std::vector<catalog::TableCatalogEntry*> entries)
        : Expression{expressionType_, std::move(dataType), std::move(uniqueName)},
          variableName(std::move(variableName)), entries{std::move(entries)} {}

    void setDataType(common::LogicalType dataType) { this->dataType = std::move(dataType); }

    std::string getVariableName() const { return variableName; }

    bool isEmpty() const { return entries.empty(); }
    virtual bool isMultiLabeled() const = 0;

    common::table_id_vector_t getTableIDs() const;
    common::table_id_set_t getTableIDsSet() const;

    // Table entries
    common::idx_t getNumEntries() const { return entries.size(); }
    const std::vector<catalog::TableCatalogEntry*>& getEntries() const { return entries; }
    catalog::TableCatalogEntry* getEntry(common::idx_t idx) const { return entries[idx]; }
    void setEntries(std::vector<catalog::TableCatalogEntry*> entries_) {
        entries = std::move(entries_);
    }
    void addEntries(const std::vector<catalog::TableCatalogEntry*>& entries_);

    // Property expressions
    void addPropertyExpression(std::shared_ptr<PropertyExpression> property);
    bool hasPropertyExpression(const std::string& propertyName) const {
        return propertyNameToIdx.contains(propertyName);
    }
    std::vector<std::shared_ptr<PropertyExpression>> getPropertyExpressions() const {
        return propertyExprs;
    }
    std::shared_ptr<PropertyExpression> getPropertyExpression(
        const std::string& propertyName) const {
        KU_ASSERT(propertyNameToIdx.contains(propertyName));
        return propertyExprs[propertyNameToIdx.at(propertyName)];
    }
    virtual std::shared_ptr<PropertyExpression> getInternalID() const = 0;

    // Label expression
    void setLabelExpression(std::shared_ptr<Expression> expression) {
        labelExpression = std::move(expression);
    }
    std::shared_ptr<Expression> getLabelExpression() const { return labelExpression; }

    // Property data expressions
    void addPropertyDataExpr(std::string propertyName, std::shared_ptr<Expression> expr) {
        propertyDataExprs.insert({propertyName, expr});
    }
    const common::case_insensitive_map_t<std::shared_ptr<Expression>>&
    getPropertyDataExprRef() const {
        return propertyDataExprs;
    }
    bool hasPropertyDataExpr(const std::string& propertyName) const {
        return propertyDataExprs.contains(propertyName);
    }
    std::shared_ptr<Expression> getPropertyDataExpr(const std::string& propertyName) const {
        KU_ASSERT(propertyDataExprs.contains(propertyName));
        return propertyDataExprs.at(propertyName);
    }

    std::string toStringInternal() const final { return variableName; }

protected:
    std::string variableName;
    // A pattern may bind to multiple tables.
    std::vector<catalog::TableCatalogEntry*> entries;
    // Index over propertyExprs on property name.
    common::case_insensitive_map_t<common::idx_t> propertyNameToIdx;
    // Property expressions with order (aligned with catalog).
    std::vector<std::shared_ptr<PropertyExpression>> propertyExprs;
    // Label expression
    std::shared_ptr<Expression> labelExpression;
    // Property data expressions specified by user in the form of "{propertyName : data}"
    common::case_insensitive_map_t<std::shared_ptr<Expression>> propertyDataExprs;
};

} // namespace binder
} // namespace lbug
