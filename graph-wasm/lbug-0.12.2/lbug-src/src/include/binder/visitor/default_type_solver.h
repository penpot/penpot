#pragma once

#include "binder/bound_statement_visitor.h"

namespace lbug {
namespace binder {

// Assign a default data type (STRING) for expressions with ANY data type for a given statement.
// E.g. RETURN NULL; Expression NULL can be resolved as any type based on semantic.
// We don't iterate all expressions because
// - predicates must have been resolved to BOOL type
// - lhs expressions for update must have been resolved to column type
// So we only need to resolve for expressions appear in the projection clause. This assumption might
// change as we add more features.
class DefaultTypeSolver final : public BoundStatementVisitor {
private:
    void visitProjectionBody(const BoundProjectionBody& projectionBody) override;
};

} // namespace binder
} // namespace lbug
