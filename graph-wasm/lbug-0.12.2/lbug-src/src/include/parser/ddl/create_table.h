#pragma once

#include "create_table_info.h"
#include "parser/scan_source.h"
#include "parser/statement.h"

namespace lbug {
namespace parser {

class CreateTable final : public Statement {
    static constexpr common::StatementType type_ = common::StatementType::CREATE_TABLE;

public:
    explicit CreateTable(CreateTableInfo info) : Statement{type_}, info{std::move(info)} {}

    CreateTable(CreateTableInfo info, std::unique_ptr<QueryScanSource>&& source)
        : Statement{type_}, info{std::move(info)}, source{std::move(source)} {}

    const CreateTableInfo* getInfo() const { return &info; }
    const QueryScanSource* getSource() const { return source.get(); }

private:
    CreateTableInfo info;
    std::unique_ptr<QueryScanSource> source;
};

} // namespace parser
} // namespace lbug
