#include "processor/operator/persistent/insert_executor.h"

#include "transaction/transaction.h"

using namespace lbug::common;
using namespace lbug::transaction;

namespace lbug {
namespace processor {

void NodeInsertInfo::init(const ResultSet& resultSet) {
    nodeIDVector = resultSet.getValueVector(nodeIDPos).get();
    for (auto& pos : columnsPos) {
        if (pos.isValid()) {
            columnVectors.push_back(resultSet.getValueVector(pos).get());
        } else {
            columnVectors.push_back(nullptr);
        }
    }
}

void NodeInsertInfo::updateNodeID(nodeID_t nodeID) const {
    KU_ASSERT(nodeIDVector->state->getSelVector().getSelSize() == 1);
    auto pos = nodeIDVector->state->getSelVector()[0];
    nodeIDVector->setNull(pos, false);
    nodeIDVector->setValue<nodeID_t>(pos, nodeID);
}

nodeID_t NodeInsertInfo::getNodeID() const {
    auto& nodeIDSelVector = nodeIDVector->state->getSelVector();
    KU_ASSERT(nodeIDSelVector.getSelSize() == 1);
    if (nodeIDVector->isNull(nodeIDSelVector[0])) {
        return {INVALID_OFFSET, INVALID_TABLE_ID};
    }
    return nodeIDVector->getValue<nodeID_t>(nodeIDSelVector[0]);
}

void NodeTableInsertInfo::init(const ResultSet& resultSet, main::ClientContext* context) {
    for (auto& evaluator : columnDataEvaluators) {
        evaluator->init(resultSet, context);
        columnDataVectors.push_back(evaluator->resultVector.get());
    }
    pkVector = columnDataVectors[table->getPKColumnID()];
}

void NodeInsertExecutor::init(ResultSet* resultSet, const ExecutionContext* context) {
    info.init(*resultSet);
    tableInfo.init(*resultSet, context->clientContext);
}

static void writeColumnVector(ValueVector* columnVector, const ValueVector* dataVector) {
    auto& columnSelVector = columnVector->state->getSelVector();
    auto& dataSelVector = dataVector->state->getSelVector();
    KU_ASSERT(columnSelVector.getSelSize() == 1 && dataSelVector.getSelSize() == 1);
    auto columnPos = columnSelVector[0];
    auto dataPos = dataSelVector[0];
    if (dataVector->isNull(dataPos)) {
        columnVector->setNull(columnPos, true);
    } else {
        columnVector->setNull(columnPos, false);
        columnVector->copyFromVectorData(columnPos, dataVector, dataPos);
    }
}

// TODO(Guodong/Xiyang): think we can reference data vector instead of copy.
static void writeColumnVectors(const std::vector<ValueVector*>& columnVectors,
    const std::vector<ValueVector*>& dataVectors) {
    KU_ASSERT(columnVectors.size() == dataVectors.size());
    for (auto i = 0u; i < columnVectors.size(); ++i) {
        if (columnVectors[i] == nullptr) { // No need to project
            continue;
        }
        writeColumnVector(columnVectors[i], dataVectors[i]);
    }
}

static void writeColumnVectorsToNull(const std::vector<ValueVector*>& columnVectors) {
    for (auto i = 0u; i < columnVectors.size(); ++i) {
        auto columnVector = columnVectors[i];
        if (columnVector == nullptr) { // No need to project
            continue;
        }
        auto& columnSelVector = columnVector->state->getSelVector();
        KU_ASSERT(columnSelVector.getSelSize() == 1);
        columnVector->setNull(columnSelVector[0], true);
    }
}

void NodeInsertExecutor::setNodeIDVectorToNonNull() const {
    info.nodeIDVector->setNull(info.nodeIDVector->state->getSelVector()[0], false);
}

nodeID_t NodeInsertExecutor::insert(main::ClientContext* context) {
    for (auto& evaluator : tableInfo.columnDataEvaluators) {
        evaluator->evaluate();
    }
    auto transaction = Transaction::Get(*context);
    if (checkConflict(transaction)) {
        return info.getNodeID();
    }
    auto insertState = std::make_unique<storage::NodeTableInsertState>(*info.nodeIDVector,
        *tableInfo.pkVector, tableInfo.columnDataVectors);
    tableInfo.table->initInsertState(context, *insertState);
    tableInfo.table->insert(transaction, *insertState);
    writeColumnVectors(info.columnVectors, tableInfo.columnDataVectors);
    return info.getNodeID();
}

void NodeInsertExecutor::skipInsert() const {
    for (auto& evaluator : tableInfo.columnDataEvaluators) {
        evaluator->evaluate();
    }
    info.nodeIDVector->setNull(info.nodeIDVector->state->getSelVector()[0], false);
    writeColumnVectors(info.columnVectors, tableInfo.columnDataVectors);
}

bool NodeInsertExecutor::checkConflict(const Transaction* transaction) const {
    if (info.conflictAction == ConflictAction::ON_CONFLICT_DO_NOTHING) {
        auto offset =
            tableInfo.table->validateUniquenessConstraint(transaction, tableInfo.columnDataVectors);
        if (offset != INVALID_OFFSET) {
            // Conflict. Skip insertion.
            info.updateNodeID({offset, tableInfo.table->getTableID()});
            return true;
        }
    }
    return false;
}

void RelInsertInfo::init(const ResultSet& resultSet) {
    srcNodeIDVector = resultSet.getValueVector(srcNodeIDPos).get();
    dstNodeIDVector = resultSet.getValueVector(dstNodeIDPos).get();
    for (auto& pos : columnsPos) {
        if (pos.isValid()) {
            columnVectors.push_back(resultSet.getValueVector(pos).get());
        } else {
            columnVectors.push_back(nullptr);
        }
    }
}

void RelTableInsertInfo::init(const ResultSet& resultSet, main::ClientContext* context) {
    for (auto& evaluator : columnDataEvaluators) {
        evaluator->init(resultSet, context);
        columnDataVectors.push_back(evaluator->resultVector.get());
    }
}

internalID_t RelTableInsertInfo::getRelID() const {
    auto relIDVector = columnDataVectors[0];
    auto& nodeIDSelVector = relIDVector->state->getSelVector();
    KU_ASSERT(nodeIDSelVector.getSelSize() == 1);
    if (relIDVector->isNull(nodeIDSelVector[0])) {
        return {INVALID_OFFSET, INVALID_TABLE_ID};
    }
    return relIDVector->getValue<nodeID_t>(nodeIDSelVector[0]);
}

void RelInsertExecutor::init(ResultSet* resultSet, const ExecutionContext* context) {
    info.init(*resultSet);
    tableInfo.init(*resultSet, context->clientContext);
}

internalID_t RelInsertExecutor::insert(main::ClientContext* context) {
    KU_ASSERT(info.srcNodeIDVector->state->getSelVector().getSelSize() == 1);
    KU_ASSERT(info.dstNodeIDVector->state->getSelVector().getSelSize() == 1);
    auto srcNodeIDPos = info.srcNodeIDVector->state->getSelVector()[0];
    auto dstNodeIDPos = info.dstNodeIDVector->state->getSelVector()[0];
    if (info.srcNodeIDVector->isNull(srcNodeIDPos) || info.dstNodeIDVector->isNull(dstNodeIDPos)) {
        // No need to insert.
        writeColumnVectorsToNull(info.columnVectors);
        return tableInfo.getRelID();
    }
    for (auto i = 1u; i < tableInfo.columnDataEvaluators.size(); ++i) {
        tableInfo.columnDataEvaluators[i]->evaluate();
    }
    auto insertState = std::make_unique<storage::RelTableInsertState>(*info.srcNodeIDVector,
        *info.dstNodeIDVector, tableInfo.columnDataVectors);
    tableInfo.table->initInsertState(context, *insertState);
    tableInfo.table->insert(Transaction::Get(*context), *insertState);
    writeColumnVectors(info.columnVectors, tableInfo.columnDataVectors);
    return tableInfo.getRelID();
}

void RelInsertExecutor::skipInsert() const {
    for (auto i = 1u; i < tableInfo.columnDataEvaluators.size(); ++i) {
        tableInfo.columnDataEvaluators[i]->evaluate();
    }
    writeColumnVectors(info.columnVectors, tableInfo.columnDataVectors);
}

} // namespace processor
} // namespace lbug
