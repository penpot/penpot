#pragma once

#include "binder/bound_statement.h"
#include "binder/copy/bound_copy_from.h"
#include "bound_create_table_info.h"

namespace lbug {
namespace binder {

class BoundCreateTable final : public BoundStatement {
    static constexpr common::StatementType type_ = common::StatementType::CREATE_TABLE;

public:
    explicit BoundCreateTable(BoundCreateTableInfo info, BoundStatementResult result)
        : BoundStatement{type_, std::move(result)}, info{std::move(info)} {}

    const BoundCreateTableInfo& getInfo() const { return info; }

    void setCopyInfo(BoundCopyFromInfo copyInfo_) { copyInfo = std::move(copyInfo_); }
    bool hasCopyInfo() const { return copyInfo.has_value(); }
    const BoundCopyFromInfo& getCopyInfo() const {
        KU_ASSERT(copyInfo.has_value());
        return copyInfo.value();
    }

private:
    BoundCreateTableInfo info;
    std::optional<BoundCopyFromInfo> copyInfo;
};

} // namespace binder
} // namespace lbug
