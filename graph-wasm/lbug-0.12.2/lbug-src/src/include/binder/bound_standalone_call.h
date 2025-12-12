#pragma once

#include "binder/bound_statement.h"
#include "binder/expression/expression.h"

namespace lbug {
namespace main {
struct Option;
}
namespace binder {

class BoundStandaloneCall final : public BoundStatement {
    static constexpr common::StatementType type_ = common::StatementType::STANDALONE_CALL;

public:
    BoundStandaloneCall(const main::Option* option, std::shared_ptr<Expression> optionValue)
        : BoundStatement{type_, BoundStatementResult::createEmptyResult()}, option{option},
          optionValue{std::move(optionValue)} {}

    const main::Option* getOption() const { return option; }

    std::shared_ptr<Expression> getOptionValue() const { return optionValue; }

private:
    const main::Option* option;
    std::shared_ptr<Expression> optionValue;
};

} // namespace binder
} // namespace lbug
