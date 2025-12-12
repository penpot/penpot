#include "processor/operator/persistent/delete_executor.h"

#include <memory>

#include "common/assert.h"
#include "common/exception/message.h"
#include "common/vector/value_vector.h"
#include "processor/execution_context.h"
#include "storage/table/rel_table.h"

using namespace lbug::common;
using namespace lbug::storage;
using namespace lbug::transaction;

namespace lbug {
namespace processor {

void NodeDeleteInfo::init(const ResultSet& resultSet) {
    nodeIDVector = resultSet.getValueVector(nodeIDPos).get();
}

void NodeTableDeleteInfo::init(const ResultSet& resultSet) {
    pkVector = resultSet.getValueVector(pkPos).get();
}

static void throwDeleteNodeWithConnectedEdgesError(const std::string& tableName,
    offset_t nodeOffset, RelDataDirection direction) {
    throw RuntimeException(ExceptionMessage::violateDeleteNodeWithConnectedEdgesConstraint(
        tableName, std::to_string(nodeOffset), RelDirectionUtils::relDirectionToString(direction)));
}

void NodeTableDeleteInfo::deleteFromRelTable(Transaction* transaction,
    ValueVector* nodeIDVector) const {
    for (auto& relTable : fwdRelTables) {
        relTable->throwIfNodeHasRels(transaction, RelDataDirection::FWD, nodeIDVector,
            throwDeleteNodeWithConnectedEdgesError);
    }
    for (auto& relTable : bwdRelTables) {
        relTable->throwIfNodeHasRels(transaction, RelDataDirection::BWD, nodeIDVector,
            throwDeleteNodeWithConnectedEdgesError);
    }
}

void NodeTableDeleteInfo::detachDeleteFromRelTable(Transaction* transaction,
    RelTableDeleteState* detachDeleteState) const {
    for (auto& relTable : fwdRelTables) {
        detachDeleteState->detachDeleteDirection = RelDataDirection::FWD;
        relTable->detachDelete(transaction, detachDeleteState);
    }
    for (auto& relTable : bwdRelTables) {
        detachDeleteState->detachDeleteDirection = RelDataDirection::BWD;
        relTable->detachDelete(transaction, detachDeleteState);
    }
}

void NodeDeleteExecutor::init(ResultSet* resultSet, ExecutionContext*) {
    info.init(*resultSet);
    if (info.deleteType == DeleteNodeType::DETACH_DELETE) {
        const auto tempSharedState = std::make_shared<DataChunkState>();
        dstNodeIDVector = std::make_unique<ValueVector>(LogicalType::INTERNAL_ID());
        relIDVector = std::make_unique<ValueVector>(LogicalType::INTERNAL_ID());
        dstNodeIDVector->setState(tempSharedState);
        relIDVector->setState(tempSharedState);
        detachDeleteState = std::make_unique<RelTableDeleteState>(*info.nodeIDVector,
            *dstNodeIDVector, *relIDVector);
    }
}

void SingleLabelNodeDeleteExecutor::init(ResultSet* resultSet, ExecutionContext* context) {
    NodeDeleteExecutor::init(resultSet, context);
    tableInfo.init(*resultSet);
}

void SingleLabelNodeDeleteExecutor::delete_(ExecutionContext* context) {
    KU_ASSERT(tableInfo.pkVector->state == info.nodeIDVector->state);
    auto deleteState =
        std::make_unique<NodeTableDeleteState>(*info.nodeIDVector, *tableInfo.pkVector);
    auto transaction = Transaction::Get(*context->clientContext);
    if (!tableInfo.table->delete_(transaction, *deleteState)) {
        return;
    }
    switch (info.deleteType) {
    case DeleteNodeType::DELETE: {
        tableInfo.deleteFromRelTable(transaction, info.nodeIDVector);
    } break;
    case DeleteNodeType::DETACH_DELETE: {
        tableInfo.detachDeleteFromRelTable(transaction, detachDeleteState.get());
    } break;
    default:
        KU_UNREACHABLE;
    }
}

void MultiLabelNodeDeleteExecutor::init(ResultSet* resultSet, ExecutionContext* context) {
    NodeDeleteExecutor::init(resultSet, context);
    for (auto& [_, tableInfo] : tableInfos) {
        tableInfo.init(*resultSet);
    }
}

void MultiLabelNodeDeleteExecutor::delete_(ExecutionContext* context) {
    auto& nodeIDSelVector = info.nodeIDVector->state->getSelVector();
    KU_ASSERT(nodeIDSelVector.getSelSize() == 1);
    const auto pos = nodeIDSelVector[0];
    if (info.nodeIDVector->isNull(pos)) {
        return;
    }
    const auto nodeID = info.nodeIDVector->getValue<internalID_t>(pos);
    const auto& tableInfo = tableInfos.at(nodeID.tableID);
    auto deleteState =
        std::make_unique<NodeTableDeleteState>(*info.nodeIDVector, *tableInfo.pkVector);
    auto transaction = Transaction::Get(*context->clientContext);
    if (!tableInfo.table->delete_(transaction, *deleteState)) {
        return;
    }
    switch (info.deleteType) {
    case DeleteNodeType::DELETE: {
        tableInfo.deleteFromRelTable(transaction, info.nodeIDVector);
    } break;
    case DeleteNodeType::DETACH_DELETE: {
        tableInfo.detachDeleteFromRelTable(transaction, detachDeleteState.get());
    } break;
    default:
        KU_UNREACHABLE;
    }
}

void RelDeleteInfo::init(const ResultSet& resultSet) {
    srcNodeIDVector = resultSet.getValueVector(srcNodeIDPos).get();
    dstNodeIDVector = resultSet.getValueVector(dstNodeIDPos).get();
    relIDVector = resultSet.getValueVector(relIDPos).get();
}

void RelDeleteExecutor::init(ResultSet* resultSet, ExecutionContext*) {
    info.init(*resultSet);
}

void SingleLabelRelDeleteExecutor::delete_(ExecutionContext* context) {
    auto deleteState = std::make_unique<RelTableDeleteState>(*info.srcNodeIDVector,
        *info.dstNodeIDVector, *info.relIDVector);
    table->delete_(Transaction::Get(*context->clientContext), *deleteState);
}

void MultiLabelRelDeleteExecutor::delete_(ExecutionContext* context) {
    auto& idSelVector = info.relIDVector->state->getSelVector();
    KU_ASSERT(idSelVector.getSelSize() == 1);
    const auto pos = idSelVector[0];
    const auto relID = info.relIDVector->getValue<internalID_t>(pos);
    KU_ASSERT(tableIDToTableMap.contains(relID.tableID));
    auto table = tableIDToTableMap.at(relID.tableID);
    auto deleteState = std::make_unique<RelTableDeleteState>(*info.srcNodeIDVector,
        *info.dstNodeIDVector, *info.relIDVector);
    table->delete_(Transaction::Get(*context->clientContext), *deleteState);
}

} // namespace processor
} // namespace lbug
