#pragma once

#include "common/enums/conflict_action.h"
#include "common/enums/table_type.h"
#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

struct LogicalInsertInfo {
    common::TableType tableType;
    std::shared_ptr<binder::Expression> pattern;
    binder::expression_vector columnExprs;
    binder::expression_vector columnDataExprs;
    std::vector<bool> isReturnColumnExprs;
    common::ConflictAction conflictAction;

    LogicalInsertInfo(common::TableType tableType, std::shared_ptr<binder::Expression> pattern,
        binder::expression_vector columnExprs, binder::expression_vector columnDataExprs,
        common::ConflictAction conflictAction)
        : tableType{tableType}, pattern{std::move(pattern)}, columnExprs{std::move(columnExprs)},
          columnDataExprs{std::move(columnDataExprs)}, conflictAction{conflictAction} {}
    EXPLICIT_COPY_DEFAULT_MOVE(LogicalInsertInfo);

private:
    LogicalInsertInfo(const LogicalInsertInfo& other)
        : tableType{other.tableType}, pattern{other.pattern}, columnExprs{other.columnExprs},
          columnDataExprs{other.columnDataExprs}, isReturnColumnExprs{other.isReturnColumnExprs},
          conflictAction{other.conflictAction} {}
};

class LogicalInsert final : public LogicalOperator {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::INSERT;

public:
    LogicalInsert(std::vector<LogicalInsertInfo> infos, std::shared_ptr<LogicalOperator> child)
        : LogicalOperator{type_, std::move(child)}, infos{std::move(infos)} {}

    void computeFactorizedSchema() override;
    void computeFlatSchema() override;

    std::string getExpressionsForPrinting() const final;

    f_group_pos_set getGroupsPosToFlatten();

    const std::vector<LogicalInsertInfo>& getInfos() const { return infos; }

    std::unique_ptr<LogicalOperator> copy() override {
        return std::make_unique<LogicalInsert>(copyVector(infos), children[0]->copy());
    }

private:
    std::vector<LogicalInsertInfo> infos;
};

} // namespace planner
} // namespace lbug
