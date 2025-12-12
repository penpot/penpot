#include "processor/operator/persistent/set_executor.h"

#include "transaction/transaction.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

void NodeSetInfo::init(const ResultSet& resultSet, main::ClientContext* context) {
    nodeIDVector = resultSet.getValueVector(nodeIDPos).get();
    if (columnVectorPos.isValid()) {
        columnVector = resultSet.getValueVector(columnVectorPos).get();
    }
    evaluator->init(resultSet, context);
    columnDataVector = evaluator->resultVector.get();
}

void NodeSetExecutor::init(ResultSet* resultSet, ExecutionContext* context) {
    info.init(*resultSet, context->clientContext);
}

void NodeSetExecutor::setNodeID(nodeID_t nodeID) const {
    info.nodeIDVector->setValue(info.nodeIDVector->state->getSelVector()[0], nodeID);
}

static void writeColumnUpdateResult(ValueVector* idVector, ValueVector* columnVector,
    ValueVector* dataVector) {
    auto& idSelVector = idVector->state->getSelVector();
    auto& columnSelVector = columnVector->state->getSelVector();
    auto& dataSelVector = dataVector->state->getSelVector();
    KU_ASSERT(idSelVector.getSelSize() == 1);
    if (idVector->isNull(idSelVector[0])) { // No update happened.
        return;
    }
    KU_ASSERT(dataSelVector.getSelSize() == 1);
    if (dataVector->isNull(dataSelVector[0])) { // Update to NULL
        columnVector->setNull(dataSelVector[0], true);
        return;
    }
    columnVector->setNull(columnSelVector[0], false);
    columnVector->copyFromVectorData(columnSelVector[0], dataVector, dataSelVector[0]);
}

void SingleLabelNodeSetExecutor::set(ExecutionContext* context) {
    if (tableInfo.columnID == INVALID_COLUMN_ID) {
        // Not a valid column. Set projected column to null.
        if (info.columnVectorPos.isValid()) {
            info.columnVector->setNull(info.columnDataVector->state->getSelVector()[0], true);
        }
        return;
    }
    info.evaluator->evaluate();
    auto updateState = std::make_unique<storage::NodeTableUpdateState>(tableInfo.columnID,
        *info.nodeIDVector, *info.columnDataVector);
    tableInfo.table->initUpdateState(context->clientContext, *updateState);
    tableInfo.table->update(transaction::Transaction::Get(*context->clientContext), *updateState);
    if (info.columnVectorPos.isValid()) {
        writeColumnUpdateResult(info.nodeIDVector, info.columnVector, info.columnDataVector);
    }
}

void MultiLabelNodeSetExecutor::set(ExecutionContext* context) {
    info.evaluator->evaluate();
    auto& nodeIDSelVector = info.nodeIDVector->state->getSelVector();
    KU_ASSERT(nodeIDSelVector.getSelSize() == 1);
    auto nodeIDPos = nodeIDSelVector[0];
    auto& nodeID = info.nodeIDVector->getValue<internalID_t>(nodeIDPos);
    if (!tableInfos.contains(nodeID.tableID)) {
        if (info.columnVectorPos.isValid()) {
            info.columnVector->setNull(info.columnDataVector->state->getSelVector()[0], true);
        }
        return;
    }
    auto& tableInfo = tableInfos.at(nodeID.tableID);
    auto updateState = std::make_unique<storage::NodeTableUpdateState>(tableInfo.columnID,
        *info.nodeIDVector, *info.columnDataVector);
    tableInfo.table->initUpdateState(context->clientContext, *updateState);
    tableInfo.table->update(transaction::Transaction::Get(*context->clientContext), *updateState);
    if (info.columnVectorPos.isValid()) {
        writeColumnUpdateResult(info.nodeIDVector, info.columnVector, info.columnDataVector);
    }
}

void RelSetInfo::init(const ResultSet& resultSet, main::ClientContext* context) {
    srcNodeIDVector = resultSet.getValueVector(srcNodeIDPos).get();
    dstNodeIDVector = resultSet.getValueVector(dstNodeIDPos).get();
    relIDVector = resultSet.getValueVector(relIDPos).get();
    if (columnVectorPos.isValid()) {
        columnVector = resultSet.getValueVector(columnVectorPos).get();
    }
    evaluator->init(resultSet, context);
    columnDataVector = evaluator->resultVector.get();
}

void RelSetExecutor::init(ResultSet* resultSet, ExecutionContext* context) {
    info.init(*resultSet, context->clientContext);
}

void RelSetExecutor::setRelID(nodeID_t relID) const {
    info.relIDVector->setValue(info.relIDVector->state->getSelVector()[0], relID);
}

void SingleLabelRelSetExecutor::set(ExecutionContext* context) {
    if (tableInfo.columnID == INVALID_COLUMN_ID) {
        if (info.columnVectorPos.isValid()) {
            info.columnVector->setNull(info.columnDataVector->state->getSelVector()[0], true);
        }
        return;
    }
    info.evaluator->evaluate();
    auto updateState = std::make_unique<storage::RelTableUpdateState>(tableInfo.columnID,
        *info.srcNodeIDVector, *info.dstNodeIDVector, *info.relIDVector, *info.columnDataVector);
    tableInfo.table->update(transaction::Transaction::Get(*context->clientContext), *updateState);
    if (info.columnVectorPos.isValid()) {
        writeColumnUpdateResult(info.relIDVector, info.columnVector, info.columnDataVector);
    }
}

void MultiLabelRelSetExecutor::set(ExecutionContext* context) {
    info.evaluator->evaluate();
    auto& idSelVector = info.relIDVector->state->getSelVector();
    KU_ASSERT(idSelVector.getSelSize() == 1);
    auto relID = info.relIDVector->getValue<internalID_t>(idSelVector[0]);
    if (!tableInfos.contains(relID.tableID)) {
        if (info.columnVectorPos.isValid()) {
            info.columnVector->setNull(info.columnDataVector->state->getSelVector()[0], true);
        }
        return;
    }
    auto& tableInfo = tableInfos.at(relID.tableID);
    auto updateState = std::make_unique<storage::RelTableUpdateState>(tableInfo.columnID,
        *info.srcNodeIDVector, *info.dstNodeIDVector, *info.relIDVector, *info.columnDataVector);
    tableInfo.table->update(transaction::Transaction::Get(*context->clientContext), *updateState);
    if (info.columnVectorPos.isValid()) {
        writeColumnUpdateResult(info.relIDVector, info.columnVector, info.columnDataVector);
    }
}

} // namespace processor
} // namespace lbug
