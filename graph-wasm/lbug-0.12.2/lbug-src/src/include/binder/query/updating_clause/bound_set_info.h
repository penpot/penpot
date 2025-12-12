#pragma once

#include "binder/expression/expression.h"
#include "common/enums/table_type.h"

namespace lbug {
namespace binder {

struct BoundSetPropertyInfo {
    common::TableType tableType;
    std::shared_ptr<Expression> pattern;
    std::shared_ptr<Expression> column;
    std::shared_ptr<Expression> columnData;

    BoundSetPropertyInfo(common::TableType tableType, std::shared_ptr<Expression> pattern,
        std::shared_ptr<Expression> column, std::shared_ptr<Expression> columnData)
        : tableType{tableType}, pattern{std::move(pattern)}, column{std::move(column)},
          columnData{std::move(columnData)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(BoundSetPropertyInfo);

private:
    BoundSetPropertyInfo(const BoundSetPropertyInfo& other)
        : tableType{other.tableType}, pattern{other.pattern}, column{other.column},
          columnData{other.columnData} {}
};

} // namespace binder
} // namespace lbug
