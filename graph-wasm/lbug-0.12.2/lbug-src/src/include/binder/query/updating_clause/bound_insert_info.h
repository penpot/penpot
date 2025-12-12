#pragma once

#include "binder/expression/expression.h"
#include "common/enums/conflict_action.h"
#include "common/enums/table_type.h"

namespace lbug {
namespace binder {

struct BoundInsertInfo {
    common::TableType tableType;
    std::shared_ptr<Expression> pattern;
    expression_vector columnExprs;
    expression_vector columnDataExprs;
    common::ConflictAction conflictAction;

    BoundInsertInfo(common::TableType tableType, std::shared_ptr<Expression> pattern)
        : tableType{tableType}, pattern{std::move(pattern)},
          conflictAction{common::ConflictAction::ON_CONFLICT_THROW} {}
    EXPLICIT_COPY_DEFAULT_MOVE(BoundInsertInfo);

private:
    BoundInsertInfo(const BoundInsertInfo& other)
        : tableType{other.tableType}, pattern{other.pattern}, columnExprs{other.columnExprs},
          columnDataExprs{other.columnDataExprs}, conflictAction{other.conflictAction} {}
};

} // namespace binder
} // namespace lbug
