#pragma once

#include "binder/bound_statement.h"
#include "bound_create_sequence_info.h"
namespace lbug {
namespace binder {

class BoundCreateSequence final : public BoundStatement {
    static constexpr common::StatementType type_ = common::StatementType::CREATE_SEQUENCE;

public:
    explicit BoundCreateSequence(BoundCreateSequenceInfo info)
        : BoundStatement{type_, BoundStatementResult::createSingleStringColumnResult()},
          info{std::move(info)} {}

    const BoundCreateSequenceInfo& getInfo() const { return info; }

private:
    BoundCreateSequenceInfo info;
};

} // namespace binder
} // namespace lbug
