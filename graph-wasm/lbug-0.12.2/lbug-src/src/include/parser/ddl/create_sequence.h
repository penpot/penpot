#pragma once

#include "create_sequence_info.h"
#include "parser/statement.h"

namespace lbug {
namespace parser {

class CreateSequence final : public Statement {
    static constexpr common::StatementType type_ = common::StatementType::CREATE_SEQUENCE;

public:
    explicit CreateSequence(CreateSequenceInfo info) : Statement{type_}, info{std::move(info)} {}

    CreateSequenceInfo getInfo() const { return info.copy(); }

private:
    CreateSequenceInfo info;
};

} // namespace parser
} // namespace lbug
