#pragma once

#include "parsed_data/attach_info.h"
#include "parser/statement.h"

namespace lbug {
namespace parser {

class AttachDatabase final : public Statement {
public:
    explicit AttachDatabase(AttachInfo attachInfo)
        : Statement{common::StatementType::ATTACH_DATABASE}, attachInfo{std::move(attachInfo)} {}

    const AttachInfo& getAttachInfo() const { return attachInfo; }

private:
    AttachInfo attachInfo;
};

} // namespace parser
} // namespace lbug
