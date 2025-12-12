#pragma once

#include "binder/expression/expression.h"
#include "processor/operator/physical_operator.h"
#include "storage/table/table.h"

namespace lbug {
namespace processor {

struct ScanOpInfo {
    // Node ID vector position.
    DataPos nodeIDPos;
    // Output vector (properties or CSRs) positions
    std::vector<DataPos> outVectorsPos;

    ScanOpInfo(DataPos nodeIDPos, std::vector<DataPos> outVectorsPos)
        : nodeIDPos{nodeIDPos}, outVectorsPos{std::move(outVectorsPos)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(ScanOpInfo);

private:
    ScanOpInfo(const ScanOpInfo& other)
        : nodeIDPos{other.nodeIDPos}, outVectorsPos{other.outVectorsPos} {}
};

// For multi-table scan, a column with the same name could be of different types. In such case,
// we scan the original type from storage and then cast at operator level
class ColumnCaster {
public:
    explicit ColumnCaster(common::LogicalType columnType) : columnType{std::move(columnType)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(ColumnCaster);

    void setCastExpr(std::shared_ptr<binder::Expression> expr) { castExpr = std::move(expr); }
    bool hasCast() const { return castExpr != nullptr; }

    // Generate temporary vectors for scanning
    void init(common::ValueVector* vectorAfterCasting, storage::MemoryManager* memoryManager);
    // Get temporary vector for scanning. This vector has the same data type as column.
    common::ValueVector* getVectorBeforeCasting() const { return vectorBeforeCasting.get(); }

    void cast();

private:
    ColumnCaster(const ColumnCaster& other)
        : columnType{other.columnType.copy()}, castExpr{other.castExpr} {}

    common::LogicalType columnType;
    std::shared_ptr<binder::Expression> castExpr;

    // vector for scanning; same data type as column
    std::shared_ptr<common::ValueVector> vectorBeforeCasting = nullptr;
    // vector after casting. This should be the vector in result set so we don't manage its life
    // cycle
    common::ValueVector* vectorAfterCasting = nullptr;

    std::vector<std::shared_ptr<common::ValueVector>> funcInputVectors;
    std::vector<common::SelectionVector*> funcInputSelVectors;
};

struct ScanTableInfo {
    storage::Table* table;

    ScanTableInfo(storage::Table* table, std::vector<storage::ColumnPredicateSet> columnPredicates)
        : table{table}, columnPredicates{std::move(columnPredicates)} {}
    virtual ~ScanTableInfo() = default;

    void addColumnInfo(common::column_id_t columnID, ColumnCaster caster);

    virtual void initScanState(storage::TableScanState& scanState,
        const std::vector<common::ValueVector*>& outVectors, main::ClientContext* context) = 0;

    void castColumns();

protected:
    ScanTableInfo(const ScanTableInfo& other)
        : table{other.table}, columnIDs{other.columnIDs},
          columnPredicates{copyVector(other.columnPredicates)},
          columnCasters{copyVector(other.columnCasters)}, hasColumnCaster{other.hasColumnCaster} {}

    void initScanStateVectors(storage::TableScanState& scanState,
        const std::vector<common::ValueVector*>& outVectors, storage::MemoryManager* memoryManager);

    // Column ids to scan
    std::vector<common::column_id_t> columnIDs;
    // Column predicates for zone map
    std::vector<storage::ColumnPredicateSet> columnPredicates;
    // Column cast handler for multi table scan of the same column name but different type
    std::vector<ColumnCaster> columnCasters;
    bool hasColumnCaster = false;
};

class ScanTable : public PhysicalOperator {
public:
    ScanTable(PhysicalOperatorType operatorType, ScanOpInfo info,
        std::unique_ptr<PhysicalOperator> child, physical_op_id id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{operatorType, std::move(child), id, std::move(printInfo)},
          opInfo{std::move(info)} {}

    ScanTable(PhysicalOperatorType operatorType, ScanOpInfo info, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{operatorType, id, std::move(printInfo)}, opInfo{std::move(info)} {}

protected:
    void initLocalStateInternal(ResultSet*, ExecutionContext*) override;

protected:
    ScanOpInfo opInfo;
    std::vector<common::ValueVector*> outVectors;
};

} // namespace processor
} // namespace lbug
