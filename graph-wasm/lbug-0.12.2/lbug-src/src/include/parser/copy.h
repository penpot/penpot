#pragma once

#include <vector>

#include "parser/expression/parsed_expression.h"
#include "parser/scan_source.h"
#include "parser/statement.h"

namespace lbug {
namespace parser {

class Copy : public Statement {
public:
    explicit Copy(common::StatementType type) : Statement{type} {}

    void setParsingOption(options_t options) { parsingOptions = std::move(options); }
    const options_t& getParsingOptions() const { return parsingOptions; }

protected:
    options_t parsingOptions;
};

struct CopyFromColumnInfo {
    bool inputColumnOrder = false;
    std::vector<std::string> columnNames;

    CopyFromColumnInfo() = default;
    CopyFromColumnInfo(bool inputColumnOrder, std::vector<std::string> columnNames)
        : inputColumnOrder{inputColumnOrder}, columnNames{std::move(columnNames)} {}
};

class CopyFrom : public Copy {
public:
    CopyFrom(std::unique_ptr<BaseScanSource> source, std::string tableName)
        : Copy{common::StatementType::COPY_FROM}, byColumn_{false}, source{std::move(source)},
          tableName{std::move(tableName)} {}

    void setByColumn() { byColumn_ = true; }
    bool byColumn() const { return byColumn_; }

    BaseScanSource* getSource() const { return source.get(); }

    std::string getTableName() const { return tableName; }

    void setColumnInfo(CopyFromColumnInfo columnInfo_) { columnInfo = std::move(columnInfo_); }
    CopyFromColumnInfo getCopyColumnInfo() const { return columnInfo; }

private:
    bool byColumn_;
    std::unique_ptr<BaseScanSource> source;
    std::string tableName;
    CopyFromColumnInfo columnInfo;
};

class CopyTo : public Copy {
public:
    CopyTo(std::string filePath, std::unique_ptr<Statement> statement)
        : Copy{common::StatementType::COPY_TO}, filePath{std::move(filePath)},
          statement{std::move(statement)} {}

    std::string getFilePath() const { return filePath; }
    const Statement* getStatement() const { return statement.get(); }

private:
    std::string filePath;
    std::unique_ptr<Statement> statement;
};

} // namespace parser
} // namespace lbug
