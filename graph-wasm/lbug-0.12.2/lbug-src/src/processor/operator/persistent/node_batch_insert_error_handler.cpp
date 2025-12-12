#include "processor/operator/persistent/node_batch_insert_error_handler.h"

#include "processor/execution_context.h"
#include "storage/table/node_table.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

NodeBatchInsertErrorHandler::NodeBatchInsertErrorHandler(ExecutionContext* context,
    LogicalTypeID pkType, storage::NodeTable* nodeTable, bool ignoreErrors,
    std::shared_ptr<row_idx_t> sharedErrorCounter, std::mutex* sharedErrorCounterMtx)
    : nodeTable(nodeTable), context(context),
      keyVector(std::make_shared<ValueVector>(pkType,
          storage::MemoryManager::Get(*context->clientContext))),
      offsetVector(std::make_shared<ValueVector>(LogicalTypeID::INTERNAL_ID,
          storage::MemoryManager::Get(*context->clientContext))),
      baseErrorHandler(context, ignoreErrors, sharedErrorCounter, sharedErrorCounterMtx) {
    keyVector->state = DataChunkState::getSingleValueDataChunkState();
    offsetVector->state = DataChunkState::getSingleValueDataChunkState();
}

void NodeBatchInsertErrorHandler::deleteCurrentErroneousRow() {
    storage::NodeTableDeleteState deleteState{
        *offsetVector,
        *keyVector,
    };
    nodeTable->delete_(transaction::Transaction::Get(*context->clientContext), deleteState);
}

void NodeBatchInsertErrorHandler::flushStoredErrors() {
    baseErrorHandler.flushStoredErrors();
}

} // namespace processor
} // namespace lbug
