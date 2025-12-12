#pragma once

#include "processor/operator/scan/scan_table.h"
#include "storage/predicate/column_predicate.h"
#include "storage/table/node_table.h"

namespace lbug {
namespace processor {

struct ScanNodeTableProgressSharedState {
    std::atomic<common::node_group_idx_t> numGroupsScanned;
    common::node_group_idx_t numGroups;

    ScanNodeTableProgressSharedState() : numGroupsScanned{0}, numGroups{0} {};
};

class ScanNodeTableSharedState {
public:
    explicit ScanNodeTableSharedState(std::unique_ptr<common::SemiMask> semiMask)
        : table{nullptr}, currentCommittedGroupIdx{common::INVALID_NODE_GROUP_IDX},
          currentUnCommittedGroupIdx{common::INVALID_NODE_GROUP_IDX}, numCommittedNodeGroups{0},
          numUnCommittedNodeGroups{0}, semiMask{std::move(semiMask)} {};

    void initialize(const transaction::Transaction* transaction, storage::NodeTable* table,
        ScanNodeTableProgressSharedState& progressSharedState);

    void nextMorsel(storage::NodeTableScanState& scanState,
        ScanNodeTableProgressSharedState& progressSharedState);

    common::SemiMask* getSemiMask() const { return semiMask.get(); }

private:
    std::mutex mtx;
    storage::NodeTable* table;
    common::node_group_idx_t currentCommittedGroupIdx;
    common::node_group_idx_t currentUnCommittedGroupIdx;
    common::node_group_idx_t numCommittedNodeGroups;
    common::node_group_idx_t numUnCommittedNodeGroups;
    std::unique_ptr<common::SemiMask> semiMask;
};

struct ScanNodeTablePrintInfo final : OPPrintInfo {
    std::vector<std::string> tableNames;
    std::string alias;
    binder::expression_vector properties;

    ScanNodeTablePrintInfo(std::vector<std::string> tableNames, std::string alias,
        binder::expression_vector properties)
        : tableNames{std::move(tableNames)}, alias{std::move(alias)},
          properties{std::move(properties)} {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<ScanNodeTablePrintInfo>(new ScanNodeTablePrintInfo(*this));
    }

private:
    ScanNodeTablePrintInfo(const ScanNodeTablePrintInfo& other)
        : OPPrintInfo{other}, tableNames{other.tableNames}, alias{other.alias},
          properties{other.properties} {}
};

struct ScanNodeTableInfo : ScanTableInfo {
    ScanNodeTableInfo(storage::Table* table,
        std::vector<storage::ColumnPredicateSet> columnPredicates)
        : ScanTableInfo{table, std::move(columnPredicates)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(ScanNodeTableInfo);

    void initScanState(storage::TableScanState& scanState,
        const std::vector<common::ValueVector*>& outVectors, main::ClientContext* context) override;

private:
    ScanNodeTableInfo(const ScanNodeTableInfo& other) : ScanTableInfo{other} {}
};

class ScanNodeTable final : public ScanTable {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::SCAN_NODE_TABLE;

public:
    ScanNodeTable(ScanOpInfo opInfo, std::vector<ScanNodeTableInfo> tableInfos,
        std::vector<std::shared_ptr<ScanNodeTableSharedState>> sharedStates, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo,
        std::shared_ptr<ScanNodeTableProgressSharedState> progressSharedState)
        : ScanTable{type_, std::move(opInfo), id, std::move(printInfo)}, currentTableIdx{0},
          scanState{nullptr}, tableInfos{std::move(tableInfos)},
          sharedStates{std::move(sharedStates)},
          progressSharedState{std::move(progressSharedState)} {
        KU_ASSERT(this->tableInfos.size() == this->sharedStates.size());
    }

    common::table_id_map_t<common::SemiMask*> getSemiMasks() const;

    bool isSource() const override { return true; }

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    bool getNextTuplesInternal(ExecutionContext* context) override;

    const ScanNodeTableSharedState& getSharedState(common::idx_t idx) const {
        KU_ASSERT(idx < sharedStates.size());
        return *sharedStates[idx];
    }

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<ScanNodeTable>(opInfo.copy(), copyVector(tableInfos), sharedStates,
            id, printInfo->copy(), progressSharedState);
    }

    double getProgress(ExecutionContext* context) const override;

private:
    void initGlobalStateInternal(ExecutionContext* context) override;

    void initCurrentTable(ExecutionContext* context);

private:
    common::idx_t currentTableIdx;
    std::unique_ptr<storage::NodeTableScanState> scanState;
    std::vector<ScanNodeTableInfo> tableInfos;
    std::vector<std::shared_ptr<ScanNodeTableSharedState>> sharedStates;
    std::shared_ptr<ScanNodeTableProgressSharedState> progressSharedState;
};

} // namespace processor
} // namespace lbug
