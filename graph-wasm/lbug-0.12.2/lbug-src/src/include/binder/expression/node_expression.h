#pragma once

#include "node_rel_expression.h"

namespace lbug {
namespace binder {

class LBUG_API NodeExpression final : public NodeOrRelExpression {
public:
    NodeExpression(common::LogicalType dataType, std::string uniqueName, std::string variableName,
        std::vector<catalog::TableCatalogEntry*> entries)
        : NodeOrRelExpression{std::move(dataType), std::move(uniqueName), std::move(variableName),
              std::move(entries)} {}

    ~NodeExpression() override;

    bool isMultiLabeled() const override { return entries.size() > 1; }

    void setInternalID(std::shared_ptr<PropertyExpression> expr) { internalID = std::move(expr); }
    std::shared_ptr<PropertyExpression> getInternalID() const override {
        KU_ASSERT(internalID != nullptr);
        return internalID;
    }

    // Get the primary key property expression for a given table ID.
    std::shared_ptr<Expression> getPrimaryKey(common::table_id_t tableID) const;

private:
    std::shared_ptr<PropertyExpression> internalID;
};

} // namespace binder
} // namespace lbug
