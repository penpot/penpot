#pragma once

#include "binder/expression/rel_expression.h"
#include "common/enums/extend_direction.h"
#include "processor/operator/scan/scan_table.h"
#include "storage/predicate/column_predicate.h"
#include "storage/table/rel_table.h"

namespace lbug {
namespace storage {
class MemoryManager;
}
namespace processor {

struct ScanRelTableInfo : ScanTableInfo {
    common::RelDataDirection direction;

    ScanRelTableInfo(storage::Table* table,
        std::vector<storage::ColumnPredicateSet> columnPredicates,
        common::RelDataDirection direction)
        : ScanTableInfo{table, std::move(columnPredicates)}, direction{direction} {}
    EXPLICIT_COPY_DEFAULT_MOVE(ScanRelTableInfo);

    void initScanState(storage::TableScanState& scanState,
        const std::vector<common::ValueVector*>& outVectors, main::ClientContext* context) override;

private:
    ScanRelTableInfo(const ScanRelTableInfo& other)
        : ScanTableInfo{other}, direction{other.direction} {}
};

struct ScanRelTablePrintInfo final : OPPrintInfo {
    std::vector<std::string> tableNames;
    binder::expression_vector properties;
    std::shared_ptr<binder::NodeExpression> boundNode;
    std::shared_ptr<binder::RelExpression> rel;
    std::shared_ptr<binder::NodeExpression> nbrNode;
    common::ExtendDirection direction;
    std::string alias;

    ScanRelTablePrintInfo(std::vector<std::string> tableNames, binder::expression_vector properties,
        std::shared_ptr<binder::NodeExpression> boundNode,
        std::shared_ptr<binder::RelExpression> rel, std::shared_ptr<binder::NodeExpression> nbrNode,
        common::ExtendDirection direction, std::string alias)
        : tableNames{std::move(tableNames)}, properties{std::move(properties)},
          boundNode{std::move(boundNode)}, rel{std::move(rel)}, nbrNode{std::move(nbrNode)},
          direction{direction}, alias{std::move(alias)} {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<ScanRelTablePrintInfo>(new ScanRelTablePrintInfo(*this));
    }

private:
    ScanRelTablePrintInfo(const ScanRelTablePrintInfo& other)
        : OPPrintInfo{other}, tableNames{other.tableNames}, properties{other.properties},
          boundNode{other.boundNode}, rel{other.rel}, nbrNode{other.nbrNode},
          direction{other.direction}, alias{other.alias} {}
};

class ScanRelTable final : public ScanTable {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::SCAN_REL_TABLE;

public:
    ScanRelTable(ScanOpInfo info, ScanRelTableInfo tableInfo,
        std::unique_ptr<PhysicalOperator> child, physical_op_id id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : ScanTable{type_, std::move(info), std::move(child), id, std::move(printInfo)},
          tableInfo{std::move(tableInfo)} {}

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    bool getNextTuplesInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<ScanRelTable>(opInfo.copy(), tableInfo.copy(), children[0]->copy(),
            id, printInfo->copy());
    }

protected:
    ScanRelTableInfo tableInfo;
    std::unique_ptr<storage::RelTableScanState> scanState;
};

} // namespace processor
} // namespace lbug
