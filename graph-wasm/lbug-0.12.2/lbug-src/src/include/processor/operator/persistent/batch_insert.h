#pragma once

#include "main/query_result/materialized_query_result.h"
#include "processor/operator/sink.h"
#include "processor/result/factorized_table.h"
#include "storage/page_allocator.h"
#include "storage/table/table.h"

namespace lbug {
namespace storage {
class MemoryManager;
class ChunkedNodeGroup;
} // namespace storage
namespace processor {

struct BatchInsertInfo {
    std::string tableName;
    bool compressionEnabled = true;

    std::vector<common::LogicalType> warningColumnTypes;
    // column types include property and warning
    std::vector<common::LogicalType> columnTypes;
    std::vector<common::column_id_t> insertColumnIDs;
    std::vector<common::column_id_t> outputDataColumns;
    std::vector<common::column_id_t> warningDataColumns;

    BatchInsertInfo(std::string tableName, std::vector<common::LogicalType> warningColumnTypes)
        : tableName{std::move(tableName)}, warningColumnTypes{std::move(warningColumnTypes)} {}
    BatchInsertInfo(const BatchInsertInfo& other)
        : tableName{other.tableName}, compressionEnabled{other.compressionEnabled},
          warningColumnTypes{copyVector(other.warningColumnTypes)},
          columnTypes{copyVector(other.columnTypes)}, insertColumnIDs{other.insertColumnIDs},
          outputDataColumns{other.outputDataColumns}, warningDataColumns{other.warningDataColumns} {
    }
    DELETE_COPY_ASSN(BatchInsertInfo);
    virtual ~BatchInsertInfo() = default;

    virtual std::unique_ptr<BatchInsertInfo> copy() const = 0;

    template<class TARGET>
    TARGET* ptrCast() {
        return common::ku_dynamic_cast<TARGET*>(this);
    }
};

struct LBUG_API BatchInsertSharedState {
    std::mutex mtx;
    std::atomic<common::row_idx_t> numRows;

    // Use a separate mutex for numErroredRows to avoid double-locking in local error handlers
    // As access to numErroredRows is independent of access to other shared state
    std::mutex erroredRowMutex;
    std::shared_ptr<common::row_idx_t> numErroredRows;

    storage::Table* table;
    std::shared_ptr<FactorizedTable> fTable;

    explicit BatchInsertSharedState(std::shared_ptr<FactorizedTable> fTable)
        : numRows{0}, numErroredRows(std::make_shared<common::row_idx_t>(0)), table{nullptr},
          fTable{std::move(fTable)} {};
    BatchInsertSharedState(const BatchInsertSharedState& other) = delete;

    virtual ~BatchInsertSharedState() = default;

    void incrementNumRows(common::row_idx_t numRowsToIncrement) {
        numRows.fetch_add(numRowsToIncrement);
    }
    common::row_idx_t getNumRows() const { return numRows.load(); }
    common::row_idx_t getNumErroredRows() {
        common::UniqLock lockGuard{erroredRowMutex};
        return *numErroredRows;
    }

    template<class TARGET>
    TARGET* ptrCast() {
        return common::ku_dynamic_cast<TARGET*>(this);
    }
};

struct BatchInsertLocalState {
    std::unique_ptr<storage::InMemChunkedNodeGroup> chunkedGroup;
    storage::PageAllocator* optimisticAllocator = nullptr;

    virtual ~BatchInsertLocalState() = default;

    template<class TARGET>
    TARGET* ptrCast() {
        return common::ku_dynamic_cast<TARGET*>(this);
    }
};

class LBUG_API BatchInsert : public Sink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::BATCH_INSERT;

public:
    BatchInsert(std::unique_ptr<BatchInsertInfo> info,
        std::shared_ptr<BatchInsertSharedState> sharedState, physical_op_id id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : Sink{type_, id, std::move(printInfo)}, info{std::move(info)},
          sharedState{std::move(sharedState)} {}

    ~BatchInsert() override = default;

    std::shared_ptr<FactorizedTable> getResultFTable() const override {
        return sharedState->fTable;
    }

    std::unique_ptr<main::QueryResult> getQueryResult() const override {
        return std::make_unique<main::MaterializedQueryResult>(sharedState->fTable);
    }

    std::unique_ptr<PhysicalOperator> copy() override = 0;

protected:
    std::unique_ptr<BatchInsertInfo> info;
    std::shared_ptr<BatchInsertSharedState> sharedState;
    std::unique_ptr<BatchInsertLocalState> localState;
};

} // namespace processor
} // namespace lbug
