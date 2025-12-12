#pragma once

#include <mutex>

#include "binder/expression/expression.h"
#include "join_hash_table.h"
#include "processor/operator/physical_operator.h"
#include "processor/operator/sink.h"
#include "processor/result/factorized_table.h"
#include "processor/result/result_set.h"

namespace lbug {
namespace processor {

struct HashJoinBuildPrintInfo final : OPPrintInfo {
    binder::expression_vector keys;
    binder::expression_vector payloads;

    HashJoinBuildPrintInfo(binder::expression_vector keys, binder::expression_vector payloads)
        : keys{std::move(keys)}, payloads(std::move(payloads)) {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<HashJoinBuildPrintInfo>(new HashJoinBuildPrintInfo(*this));
    }

private:
    HashJoinBuildPrintInfo(const HashJoinBuildPrintInfo& other)
        : OPPrintInfo{other}, keys{other.keys}, payloads{other.payloads} {}
};

class HashJoinBuild;

// This is a shared state between HashJoinBuild and HashJoinProbe operators.
// Each clone of these two operators will share the same state.
// Inside the state, we keep the materialized tuples in factorizedTable, which are merged by each
// HashJoinBuild thread when they finished materializing thread-local tuples. Also, the state holds
// a global htDirectory, which will be updated by the last thread in the hash join build side
// task/pipeline, and probed by the HashJoinProbe operators.
class HashJoinSharedState {
public:
    explicit HashJoinSharedState(std::unique_ptr<JoinHashTable> hashTable)
        : hashTable{std::move(hashTable)} {};

    void mergeLocalHashTable(JoinHashTable& localHashTable);

    JoinHashTable* getHashTable() { return hashTable.get(); }

protected:
    std::mutex mtx;
    std::unique_ptr<JoinHashTable> hashTable;
};

struct HashJoinBuildInfo {
    std::vector<DataPos> keysPos;
    std::vector<common::FStateType> fStateTypes;
    std::vector<DataPos> payloadsPos;
    FactorizedTableSchema tableSchema;

    HashJoinBuildInfo(std::vector<DataPos> keysPos, std::vector<common::FStateType> fStateTypes,
        std::vector<DataPos> payloadsPos, FactorizedTableSchema tableSchema)
        : keysPos{std::move(keysPos)}, fStateTypes{std::move(fStateTypes)},
          payloadsPos{std::move(payloadsPos)}, tableSchema{std::move(tableSchema)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(HashJoinBuildInfo);

    common::idx_t getNumKeys() const { return keysPos.size(); }

private:
    HashJoinBuildInfo(const HashJoinBuildInfo& other)
        : keysPos{other.keysPos}, fStateTypes{other.fStateTypes}, payloadsPos{other.payloadsPos},
          tableSchema{other.tableSchema.copy()} {}
};

class HashJoinBuild : public Sink {
public:
    HashJoinBuild(PhysicalOperatorType operatorType,
        std::shared_ptr<HashJoinSharedState> sharedState, HashJoinBuildInfo info,
        std::unique_ptr<PhysicalOperator> child, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : Sink{operatorType, std::move(child), id, std::move(printInfo)},
          sharedState{std::move(sharedState)}, info{std::move(info)} {}

    std::shared_ptr<HashJoinSharedState> getSharedState() const { return sharedState; }

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    void executeInternal(ExecutionContext* context) override;

    void finalizeInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return make_unique<HashJoinBuild>(operatorType, sharedState, info.copy(),
            children[0]->copy(), id, printInfo->copy());
    }

protected:
    virtual uint64_t appendVectors() {
        return hashTable->appendVectors(keyVectors, payloadVectors, keyState);
    }

private:
    void setKeyState(common::DataChunkState* state);

protected:
    std::shared_ptr<HashJoinSharedState> sharedState;
    HashJoinBuildInfo info;

    std::vector<common::ValueVector*> keyVectors;
    // State of unFlat key(s). If all keys are flat, it points to any flat key state.
    common::DataChunkState* keyState = nullptr;
    std::vector<common::ValueVector*> payloadVectors;

    std::unique_ptr<JoinHashTable> hashTable; // local state
};

} // namespace processor
} // namespace lbug
