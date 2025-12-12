#pragma once

#include "alter_info.h"
#include "parser/statement.h"

namespace lbug {
namespace parser {

class Alter : public Statement {
    static constexpr common::StatementType type_ = common::StatementType::ALTER;

public:
    explicit Alter(AlterInfo info) : Statement{type_}, info{std::move(info)} {}

    const AlterInfo* getInfo() const { return &info; }

private:
    AlterInfo info;
};

} // namespace parser
} // namespace lbug
