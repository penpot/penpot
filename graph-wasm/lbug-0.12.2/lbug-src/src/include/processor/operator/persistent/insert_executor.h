#pragma once

#include "common/enums/conflict_action.h"
#include "expression_evaluator/expression_evaluator.h"
#include "processor/execution_context.h"
#include "storage/table/node_table.h"
#include "storage/table/rel_table.h"

namespace lbug {
namespace processor {

// Operator level info
struct NodeInsertInfo {
    DataPos nodeIDPos;
    // Column vector pos is invalid if it doesn't need to be projected.
    std::vector<DataPos> columnsPos;
    common::ConflictAction conflictAction;

    common::ValueVector* nodeIDVector = nullptr;
    std::vector<common::ValueVector*> columnVectors;

    NodeInsertInfo(DataPos nodeIDPos, std::vector<DataPos> columnsPos,
        common::ConflictAction conflictAction)
        : nodeIDPos{nodeIDPos}, columnsPos{std::move(columnsPos)}, conflictAction{conflictAction} {}
    EXPLICIT_COPY_DEFAULT_MOVE(NodeInsertInfo);

    void init(const ResultSet& resultSet);

    void updateNodeID(common::nodeID_t nodeID) const;
    common::nodeID_t getNodeID() const;

private:
    NodeInsertInfo(const NodeInsertInfo& other)
        : nodeIDPos{other.nodeIDPos}, columnsPos{other.columnsPos},
          conflictAction{other.conflictAction} {}
};

// Table level info
struct NodeTableInsertInfo {
    storage::NodeTable* table;
    evaluator::evaluator_vector_t columnDataEvaluators;

    common::ValueVector* pkVector;
    std::vector<common::ValueVector*> columnDataVectors;

    NodeTableInsertInfo(storage::NodeTable* table,
        evaluator::evaluator_vector_t columnDataEvaluators)
        : table{table}, columnDataEvaluators{std::move(columnDataEvaluators)}, pkVector{nullptr} {}
    EXPLICIT_COPY_DEFAULT_MOVE(NodeTableInsertInfo);

    void init(const ResultSet& resultSet, main::ClientContext* context);

private:
    NodeTableInsertInfo(const NodeTableInsertInfo& other)
        : table{other.table}, columnDataEvaluators{copyVector(other.columnDataEvaluators)},
          pkVector{nullptr} {}
};

class NodeInsertExecutor {
public:
    NodeInsertExecutor(NodeInsertInfo info, NodeTableInsertInfo tableInfo)
        : info{std::move(info)}, tableInfo{std::move(tableInfo)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(NodeInsertExecutor);

    void init(ResultSet* resultSet, const ExecutionContext* context);

    void setNodeIDVectorToNonNull() const;
    common::nodeID_t insert(main::ClientContext* context);

    // For MERGE, we might need to skip the insert for duplicate input. But still, we need to write
    // the output vector for later usage.
    void skipInsert() const;

private:
    NodeInsertExecutor(const NodeInsertExecutor& other)
        : info{other.info.copy()}, tableInfo{other.tableInfo.copy()} {}

    bool checkConflict(const transaction::Transaction* transaction) const;

private:
    NodeInsertInfo info;
    NodeTableInsertInfo tableInfo;
};

struct RelInsertInfo {
    DataPos srcNodeIDPos;
    DataPos dstNodeIDPos;
    std::vector<DataPos> columnsPos;

    common::ValueVector* srcNodeIDVector;
    common::ValueVector* dstNodeIDVector;
    std::vector<common::ValueVector*> columnVectors;

    RelInsertInfo(DataPos srcNodeIDPos, DataPos dstNodeIDPos, std::vector<DataPos> columnsPos)
        : srcNodeIDPos{srcNodeIDPos}, dstNodeIDPos{dstNodeIDPos}, columnsPos{std::move(columnsPos)},
          srcNodeIDVector{nullptr}, dstNodeIDVector{nullptr} {}
    EXPLICIT_COPY_DEFAULT_MOVE(RelInsertInfo);

    void init(const ResultSet& resultSet);

private:
    RelInsertInfo(const RelInsertInfo& other)
        : srcNodeIDPos{other.srcNodeIDPos}, dstNodeIDPos{other.dstNodeIDPos},
          columnsPos{other.columnsPos}, srcNodeIDVector{nullptr}, dstNodeIDVector{nullptr} {}
};

struct RelTableInsertInfo {
    storage::RelTable* table;
    evaluator::evaluator_vector_t columnDataEvaluators;

    std::vector<common::ValueVector*> columnDataVectors;

    RelTableInsertInfo(storage::RelTable* table, evaluator::evaluator_vector_t evaluators)
        : table{table}, columnDataEvaluators{std::move(evaluators)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(RelTableInsertInfo);

    void init(const ResultSet& resultSet, main::ClientContext* context);
    common::internalID_t getRelID() const;

private:
    RelTableInsertInfo(const RelTableInsertInfo& other)
        : table{other.table}, columnDataEvaluators(copyVector(other.columnDataEvaluators)) {}
};

class RelInsertExecutor {
public:
    RelInsertExecutor(RelInsertInfo info, RelTableInsertInfo tableInfo)
        : info{std::move(info)}, tableInfo{std::move(tableInfo)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(RelInsertExecutor);

    void init(ResultSet* resultSet, const ExecutionContext* context);

    common::internalID_t insert(main::ClientContext* context);

    // See comment in NodeInsertExecutor.
    void skipInsert() const;

private:
    RelInsertExecutor(const RelInsertExecutor& other)
        : info{other.info.copy()}, tableInfo{other.tableInfo.copy()} {}

private:
    RelInsertInfo info;
    RelTableInsertInfo tableInfo;
};

} // namespace processor
} // namespace lbug
