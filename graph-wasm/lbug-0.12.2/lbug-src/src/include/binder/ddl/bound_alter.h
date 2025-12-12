#pragma once

#include "binder/bound_statement.h"
#include "bound_alter_info.h"

namespace lbug {
namespace binder {

class BoundAlter final : public BoundStatement {
    static constexpr common::StatementType type_ = common::StatementType::ALTER;

public:
    explicit BoundAlter(BoundAlterInfo info)
        : BoundStatement{type_, BoundStatementResult::createSingleStringColumnResult()},
          info{std::move(info)} {}

    const BoundAlterInfo& getInfo() const { return info; }

private:
    BoundAlterInfo info;
};

} // namespace binder
} // namespace lbug
