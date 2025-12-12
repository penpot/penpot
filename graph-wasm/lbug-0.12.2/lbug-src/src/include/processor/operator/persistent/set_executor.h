#pragma once

#include "expression_evaluator/expression_evaluator.h"
#include "processor/execution_context.h"
#include "processor/result/result_set.h"
#include "storage/table/node_table.h"
#include "storage/table/rel_table.h"

namespace lbug {
namespace processor {

struct NodeSetInfo {
    DataPos nodeIDPos;
    DataPos columnVectorPos;

    std::unique_ptr<evaluator::ExpressionEvaluator> evaluator;

    common::ValueVector* nodeIDVector = nullptr;
    common::ValueVector* columnVector = nullptr;
    common::ValueVector* columnDataVector = nullptr;

    NodeSetInfo(DataPos nodeIDPos, DataPos columnVectorPos,
        std::unique_ptr<evaluator::ExpressionEvaluator> evaluator)
        : nodeIDPos{nodeIDPos}, columnVectorPos{columnVectorPos}, evaluator{std::move(evaluator)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(NodeSetInfo);

    void init(const ResultSet& resultSet, main::ClientContext* context);

private:
    NodeSetInfo(const NodeSetInfo& other)
        : nodeIDPos{other.nodeIDPos}, columnVectorPos{other.columnVectorPos},
          evaluator{other.evaluator->copy()} {}
};

struct NodeTableSetInfo {
    storage::NodeTable* table;
    common::column_id_t columnID;

    NodeTableSetInfo(storage::NodeTable* table, common::column_id_t columnID)
        : table{table}, columnID{columnID} {}
    EXPLICIT_COPY_DEFAULT_MOVE(NodeTableSetInfo);

private:
    NodeTableSetInfo(const NodeTableSetInfo& other)
        : table{other.table}, columnID{other.columnID} {}
};

class NodeSetExecutor {
public:
    explicit NodeSetExecutor(NodeSetInfo info) : info{std::move(info)} {}
    NodeSetExecutor(const NodeSetExecutor& other) : info{other.info.copy()} {}
    virtual ~NodeSetExecutor() = default;

    virtual void init(ResultSet* resultSet, ExecutionContext* context);

    void setNodeID(common::nodeID_t nodeID) const;

    virtual void set(ExecutionContext* context) = 0;

    virtual std::unique_ptr<NodeSetExecutor> copy() const = 0;

protected:
    NodeSetInfo info;
};

class SingleLabelNodeSetExecutor final : public NodeSetExecutor {
public:
    SingleLabelNodeSetExecutor(NodeSetInfo info, NodeTableSetInfo tableInfo)
        : NodeSetExecutor{std::move(info)}, tableInfo{std::move(tableInfo)} {}
    SingleLabelNodeSetExecutor(const SingleLabelNodeSetExecutor& other)
        : NodeSetExecutor{other}, tableInfo(other.tableInfo.copy()) {}

    void set(ExecutionContext* context) override;

    std::unique_ptr<NodeSetExecutor> copy() const override {
        return std::make_unique<SingleLabelNodeSetExecutor>(*this);
    }

private:
    NodeTableSetInfo tableInfo;
};

class MultiLabelNodeSetExecutor final : public NodeSetExecutor {
public:
    MultiLabelNodeSetExecutor(NodeSetInfo info, common::table_id_map_t<NodeTableSetInfo> tableInfos)
        : NodeSetExecutor{std::move(info)}, tableInfos{std::move(tableInfos)} {}
    MultiLabelNodeSetExecutor(const MultiLabelNodeSetExecutor& other)
        : NodeSetExecutor{other}, tableInfos{copyUnorderedMap(other.tableInfos)} {}

    void set(ExecutionContext* context) override;

    std::unique_ptr<NodeSetExecutor> copy() const override {
        return std::make_unique<MultiLabelNodeSetExecutor>(*this);
    }

private:
    common::table_id_map_t<NodeTableSetInfo> tableInfos;
};

struct RelSetInfo {
    DataPos srcNodeIDPos;
    DataPos dstNodeIDPos;
    DataPos relIDPos;
    DataPos columnVectorPos;
    std::unique_ptr<evaluator::ExpressionEvaluator> evaluator;

    common::ValueVector* srcNodeIDVector = nullptr;
    common::ValueVector* dstNodeIDVector = nullptr;
    common::ValueVector* relIDVector = nullptr;
    common::ValueVector* columnVector = nullptr;
    common::ValueVector* columnDataVector = nullptr;

    RelSetInfo(DataPos srcNodeIDPos, DataPos dstNodeIDPos, DataPos relIDPos,
        DataPos columnVectorPos, std::unique_ptr<evaluator::ExpressionEvaluator> evaluator)
        : srcNodeIDPos{srcNodeIDPos}, dstNodeIDPos{dstNodeIDPos}, relIDPos{relIDPos},
          columnVectorPos{columnVectorPos}, evaluator{std::move(evaluator)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(RelSetInfo);

    void init(const ResultSet& resultSet, main::ClientContext* context);

private:
    RelSetInfo(const RelSetInfo& other)
        : srcNodeIDPos{other.srcNodeIDPos}, dstNodeIDPos{other.dstNodeIDPos},
          relIDPos{other.relIDPos}, columnVectorPos{other.columnVectorPos},
          evaluator{other.evaluator->copy()} {}
};

struct RelTableSetInfo {
    storage::RelTable* table;
    common::column_id_t columnID;

    RelTableSetInfo(storage::RelTable* table, common::column_id_t columnID)
        : table{table}, columnID{columnID} {}
    EXPLICIT_COPY_DEFAULT_MOVE(RelTableSetInfo);

private:
    RelTableSetInfo(const RelTableSetInfo& other) : table{other.table}, columnID{other.columnID} {}
};

class RelSetExecutor {
public:
    explicit RelSetExecutor(RelSetInfo info) : info{std::move(info)} {}
    RelSetExecutor(const RelSetExecutor& other) : info{other.info.copy()} {}
    virtual ~RelSetExecutor() = default;

    void init(ResultSet* resultSet, ExecutionContext* context);

    void setRelID(common::nodeID_t relID) const;

    virtual void set(ExecutionContext* context) = 0;

    virtual std::unique_ptr<RelSetExecutor> copy() const = 0;

protected:
    RelSetInfo info;
};

class SingleLabelRelSetExecutor final : public RelSetExecutor {
public:
    SingleLabelRelSetExecutor(RelSetInfo info, RelTableSetInfo tableInfo)
        : RelSetExecutor{std::move(info)}, tableInfo{std::move(tableInfo)} {}
    SingleLabelRelSetExecutor(const SingleLabelRelSetExecutor& other)
        : RelSetExecutor{other}, tableInfo{other.tableInfo.copy()} {}

    void set(ExecutionContext* context) override;

    std::unique_ptr<RelSetExecutor> copy() const override {
        return std::make_unique<SingleLabelRelSetExecutor>(*this);
    }

private:
    RelTableSetInfo tableInfo;
};

class MultiLabelRelSetExecutor final : public RelSetExecutor {
public:
    MultiLabelRelSetExecutor(RelSetInfo info, common::table_id_map_t<RelTableSetInfo> tableInfos)
        : RelSetExecutor{std::move(info)}, tableInfos{std::move(tableInfos)} {}
    MultiLabelRelSetExecutor(const MultiLabelRelSetExecutor& other)
        : RelSetExecutor{other}, tableInfos{copyUnorderedMap(other.tableInfos)} {}

    void set(ExecutionContext* context) override;

    std::unique_ptr<RelSetExecutor> copy() const override {
        return std::make_unique<MultiLabelRelSetExecutor>(*this);
    }

private:
    common::table_id_map_t<RelTableSetInfo> tableInfos;
};

} // namespace processor
} // namespace lbug
