#include "processor/operator/order_by/order_by_merge.h"

#include <thread>

#include "common/constants.h"
#include "processor/execution_context.h"
#include "storage/buffer_manager/memory_manager.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

void OrderByMerge::initLocalStateInternal(ResultSet* /*resultSet*/, ExecutionContext* /*context*/) {
    // OrderByMerge is the only sink operator in a pipeline and only modifies the
    // sharedState by merging sortedKeyBlocks, So we don't need to initialize the resultSet.
    localMerger = make_unique<KeyBlockMerger>(sharedState->getPayloadTables(),
        sharedState->getStrKeyColInfo(), sharedState->getNumBytesPerTuple());
}

void OrderByMerge::executeInternal(ExecutionContext* /*context*/) {
    while (!sharedDispatcher->isDoneMerge()) {
        auto keyBlockMergeMorsel = sharedDispatcher->getMorsel();
        if (keyBlockMergeMorsel == nullptr) {
            std::this_thread::sleep_for(
                std::chrono::microseconds(THREAD_SLEEP_TIME_WHEN_WAITING_IN_MICROS));
            continue;
        }
        localMerger->mergeKeyBlocks(*keyBlockMergeMorsel);
        sharedDispatcher->doneMorsel(std::move(keyBlockMergeMorsel));
    }
}

void OrderByMerge::initGlobalStateInternal(ExecutionContext* context) {
    // TODO(Ziyi): directly feed sharedState to merger and dispatcher.
    sharedDispatcher->init(storage::MemoryManager::Get(*context->clientContext),
        sharedState->getSortedKeyBlocks(), sharedState->getPayloadTables(),
        sharedState->getStrKeyColInfo(), sharedState->getNumBytesPerTuple());
}

} // namespace processor
} // namespace lbug
