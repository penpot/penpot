#include "processor/operator/hash_join/hash_join_build.h"

#include "binder/expression/expression_util.h"
#include "processor/execution_context.h"
#include "storage/buffer_manager/memory_manager.h"

using namespace lbug::common;
using namespace lbug::storage;

namespace lbug {
namespace processor {

std::string HashJoinBuildPrintInfo::toString() const {
    std::string result = "Keys: ";
    result += binder::ExpressionUtil::toString(keys);
    if (!payloads.empty()) {
        result += ", Payloads: ";
        result += binder::ExpressionUtil::toString(payloads);
    }
    return result;
}

void HashJoinSharedState::mergeLocalHashTable(JoinHashTable& localHashTable) {
    std::unique_lock lck(mtx);
    hashTable->merge(localHashTable);
}

void HashJoinBuild::initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) {
    std::vector<LogicalType> keyTypes;
    for (auto i = 0u; i < info.keysPos.size(); ++i) {
        auto vector = resultSet->getValueVector(info.keysPos[i]).get();
        keyTypes.push_back(vector->dataType.copy());
        if (info.fStateTypes[i] == common::FStateType::UNFLAT) {
            setKeyState(vector->state.get());
        }
        keyVectors.push_back(vector);
    }
    if (keyState == nullptr) {
        setKeyState(keyVectors[0]->state.get());
    }
    for (auto& pos : info.payloadsPos) {
        payloadVectors.push_back(resultSet->getValueVector(pos).get());
    }
    hashTable = std::make_unique<JoinHashTable>(*MemoryManager::Get(*context->clientContext),
        std::move(keyTypes), info.tableSchema.copy());
}

void HashJoinBuild::setKeyState(common::DataChunkState* state) {
    if (keyState == nullptr) {
        keyState = state;
    } else {
        KU_ASSERT(keyState == state); // two pointers should be pointing to the same state
    }
}

void HashJoinBuild::finalizeInternal(ExecutionContext* /*context*/) {
    auto numTuples = sharedState->getHashTable()->getNumEntries();
    sharedState->getHashTable()->allocateHashSlots(numTuples);
    sharedState->getHashTable()->buildHashSlots();
}

void HashJoinBuild::executeInternal(ExecutionContext* context) {
    // Append thread-local tuples
    while (children[0]->getNextTuple(context)) {
        uint64_t numAppended = 0u;
        for (auto i = 0u; i < resultSet->multiplicity; ++i) {
            numAppended += appendVectors();
        }
        metrics->numOutputTuple.increase(numAppended);
    }
    // Merge with global hash table once local tuples are all appended.
    sharedState->mergeLocalHashTable(*hashTable);
}

} // namespace processor
} // namespace lbug
