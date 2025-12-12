#pragma once

#include "binder/expression/expression.h"

namespace lbug {
namespace binder {

class BoundStatementResult {
public:
    BoundStatementResult() = default;
    explicit BoundStatementResult(expression_vector columns, std::vector<std::string> columnNames)
        : columns{std::move(columns)}, columnNames{std::move(columnNames)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(BoundStatementResult);

    static BoundStatementResult createEmptyResult() { return BoundStatementResult(); }

    static BoundStatementResult createSingleStringColumnResult(
        const std::string& columnName = "result");

    void addColumn(const std::string& columnName, std::shared_ptr<Expression> column) {
        columns.push_back(std::move(column));
        columnNames.push_back(columnName);
    }
    expression_vector getColumns() const { return columns; }
    std::vector<std::string> getColumnNames() const { return columnNames; }
    std::vector<common::LogicalType> getColumnTypes() const {
        std::vector<common::LogicalType> columnTypes;
        for (auto& column : columns) {
            columnTypes.push_back(column->getDataType().copy());
        }
        return columnTypes;
    }

    std::shared_ptr<Expression> getSingleColumnExpr() const {
        KU_ASSERT(columns.size() == 1);
        return columns[0];
    }

private:
    BoundStatementResult(const BoundStatementResult& other)
        : columns{other.columns}, columnNames{other.columnNames} {}

private:
    expression_vector columns;
    // ColumnNames might be different from column.toString() because the same column might have
    // different aliases, e.g. RETURN id AS a, id AS b
    // For both columns we currently refer to the same id expr object so we cannot resolve column
    // name properly from expression object.
    std::vector<std::string> columnNames;
};

} // namespace binder
} // namespace lbug
