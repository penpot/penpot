#pragma once

#include "drop_info.h"
#include "parser/statement.h"

namespace lbug {
namespace parser {

class Drop : public Statement {
    static constexpr common::StatementType type_ = common::StatementType::DROP;

public:
    explicit Drop(DropInfo dropInfo) : Statement{type_}, dropInfo{std::move(dropInfo)} {}

    const DropInfo& getDropInfo() const { return dropInfo; }

private:
    DropInfo dropInfo;
};

} // namespace parser
} // namespace lbug
