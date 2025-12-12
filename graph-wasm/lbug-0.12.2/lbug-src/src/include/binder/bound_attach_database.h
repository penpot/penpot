#pragma once

#include "binder/bound_statement.h"
#include "bound_attach_info.h"

namespace lbug {
namespace binder {

class BoundAttachDatabase final : public BoundStatement {
public:
    explicit BoundAttachDatabase(binder::AttachInfo attachInfo)
        : BoundStatement{common::StatementType::ATTACH_DATABASE,
              BoundStatementResult::createSingleStringColumnResult()},
          attachInfo{std::move(attachInfo)} {}

    AttachInfo getAttachInfo() const { return attachInfo; }

private:
    AttachInfo attachInfo;
};

} // namespace binder
} // namespace lbug
