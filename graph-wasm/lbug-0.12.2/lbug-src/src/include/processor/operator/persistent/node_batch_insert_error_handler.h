#pragma once

#include "common/types/types.h"
#include "common/vector/value_vector.h"
#include "processor/execution_context.h"
#include "processor/operator/persistent/batch_insert_error_handler.h"

namespace lbug {
namespace storage {
class NodeTable;
}

namespace processor {
template<typename T>
struct IndexBuilderError {
    std::string message;
    T key;
    common::nodeID_t nodeID;

    // CSV Reader data
    std::optional<WarningSourceData> warningData;
};

class NodeBatchInsertErrorHandler {
public:
    NodeBatchInsertErrorHandler(ExecutionContext* context, common::LogicalTypeID pkType,
        storage::NodeTable* nodeTable, bool ignoreErrors,
        std::shared_ptr<common::row_idx_t> sharedErrorCounter, std::mutex* sharedErrorCounterMtx);

    template<typename T>
    void handleError(IndexBuilderError<T> error) {
        baseErrorHandler.handleError(std::move(error.message), std::move(error.warningData));

        setCurrentErroneousRow(error.key, error.nodeID);
        deleteCurrentErroneousRow();
    }

    void flushStoredErrors();

private:
    template<typename T>
    void setCurrentErroneousRow(const T& key, common::nodeID_t nodeID) {
        keyVector->setValue<T>(0, key);
        offsetVector->setValue(0, nodeID);
    }

    void deleteCurrentErroneousRow();

    static constexpr common::idx_t DELETE_VECTOR_SIZE = 1;

    storage::NodeTable* nodeTable;
    ExecutionContext* context;

    // vectors that are reused by each deletion
    std::shared_ptr<common::ValueVector> keyVector;
    std::shared_ptr<common::ValueVector> offsetVector;

    BatchInsertErrorHandler baseErrorHandler;
};
} // namespace processor
} // namespace lbug
