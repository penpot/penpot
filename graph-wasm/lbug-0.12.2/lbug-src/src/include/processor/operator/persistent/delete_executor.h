#pragma once

#include <memory>
#include <utility>

#include "common/enums/delete_type.h"
#include "common/vector/value_vector.h"
#include "processor/result/result_set.h"
#include "storage/table/node_table.h"
#include "storage/table/rel_table.h"

namespace lbug {
namespace processor {

struct NodeDeleteInfo {
    common::DeleteNodeType deleteType;
    DataPos nodeIDPos;

    common::ValueVector* nodeIDVector = nullptr;

    NodeDeleteInfo(common::DeleteNodeType deleteType, const DataPos& nodeIDPos)
        : deleteType{deleteType}, nodeIDPos{nodeIDPos} {};
    EXPLICIT_COPY_DEFAULT_MOVE(NodeDeleteInfo);

    void init(const ResultSet& resultSet);

private:
    NodeDeleteInfo(const NodeDeleteInfo& other)
        : deleteType{other.deleteType}, nodeIDPos{other.nodeIDPos} {}
};

struct NodeTableDeleteInfo {
    storage::NodeTable* table;
    std::unordered_set<storage::RelTable*> fwdRelTables;
    std::unordered_set<storage::RelTable*> bwdRelTables;
    DataPos pkPos;

    common::ValueVector* pkVector;

    NodeTableDeleteInfo(storage::NodeTable* table,
        std::unordered_set<storage::RelTable*> fwdRelTables,
        std::unordered_set<storage::RelTable*> bwdRelTables, const DataPos& pkPos)
        : table{table}, fwdRelTables{std::move(fwdRelTables)},
          bwdRelTables{std::move(bwdRelTables)}, pkPos{pkPos}, pkVector{nullptr} {};
    EXPLICIT_COPY_DEFAULT_MOVE(NodeTableDeleteInfo);

    void init(const ResultSet& resultSet);

    void deleteFromRelTable(transaction::Transaction* transaction,
        common::ValueVector* nodeIDVector) const;
    void detachDeleteFromRelTable(transaction::Transaction* transaction,
        storage::RelTableDeleteState* detachDeleteState) const;

private:
    NodeTableDeleteInfo(const NodeTableDeleteInfo& other)
        : table{other.table}, fwdRelTables{other.fwdRelTables}, bwdRelTables{other.bwdRelTables},
          pkPos{other.pkPos}, pkVector{nullptr} {}
};

class NodeDeleteExecutor {
public:
    explicit NodeDeleteExecutor(NodeDeleteInfo info) : info{std::move(info)} {}
    NodeDeleteExecutor(const NodeDeleteExecutor& other) : info{other.info.copy()} {}
    virtual ~NodeDeleteExecutor() = default;

    virtual void init(ResultSet* resultSet, ExecutionContext* context);

    virtual void delete_(ExecutionContext* context) = 0;

    virtual std::unique_ptr<NodeDeleteExecutor> copy() const = 0;

protected:
    NodeDeleteInfo info;
    std::unique_ptr<common::ValueVector> dstNodeIDVector;
    std::unique_ptr<common::ValueVector> relIDVector;
    std::unique_ptr<storage::RelTableDeleteState> detachDeleteState;
};

// Handle MATCH (n) (DETACH)? DELETE n
class EmptyNodeDeleteExecutor final : public NodeDeleteExecutor {
public:
    explicit EmptyNodeDeleteExecutor(NodeDeleteInfo info) : NodeDeleteExecutor{std::move(info)} {}
    EmptyNodeDeleteExecutor(const EmptyNodeDeleteExecutor& other) : NodeDeleteExecutor{other} {}

    void delete_(ExecutionContext*) override {}

    std::unique_ptr<NodeDeleteExecutor> copy() const override {
        return std::make_unique<EmptyNodeDeleteExecutor>(*this);
    }
};

class SingleLabelNodeDeleteExecutor final : public NodeDeleteExecutor {
public:
    SingleLabelNodeDeleteExecutor(NodeDeleteInfo info, NodeTableDeleteInfo tableInfo)
        : NodeDeleteExecutor(std::move(info)), tableInfo{std::move(tableInfo)} {}
    SingleLabelNodeDeleteExecutor(const SingleLabelNodeDeleteExecutor& other)
        : NodeDeleteExecutor(other), tableInfo{other.tableInfo.copy()} {}

    void init(ResultSet* resultSet, ExecutionContext*) override;
    void delete_(ExecutionContext* context) override;

    std::unique_ptr<NodeDeleteExecutor> copy() const override {
        return std::make_unique<SingleLabelNodeDeleteExecutor>(*this);
    }

private:
    NodeTableDeleteInfo tableInfo;
};

class MultiLabelNodeDeleteExecutor final : public NodeDeleteExecutor {
public:
    MultiLabelNodeDeleteExecutor(NodeDeleteInfo info,
        common::table_id_map_t<NodeTableDeleteInfo> tableInfos)
        : NodeDeleteExecutor(std::move(info)), tableInfos{std::move(tableInfos)} {}
    MultiLabelNodeDeleteExecutor(const MultiLabelNodeDeleteExecutor& other)
        : NodeDeleteExecutor(other), tableInfos{copyUnorderedMap(other.tableInfos)} {}

    void init(ResultSet* resultSet, ExecutionContext*) override;
    void delete_(ExecutionContext* context) override;

    std::unique_ptr<NodeDeleteExecutor> copy() const override {
        return std::make_unique<MultiLabelNodeDeleteExecutor>(*this);
    }

private:
    common::table_id_map_t<NodeTableDeleteInfo> tableInfos;
};

struct RelDeleteInfo {
    DataPos srcNodeIDPos;
    DataPos dstNodeIDPos;
    DataPos relIDPos;

    common::ValueVector* srcNodeIDVector = nullptr;
    common::ValueVector* dstNodeIDVector = nullptr;
    common::ValueVector* relIDVector = nullptr;

    RelDeleteInfo()
        : srcNodeIDPos{INVALID_DATA_CHUNK_POS, INVALID_VALUE_VECTOR_POS},
          dstNodeIDPos{INVALID_DATA_CHUNK_POS, INVALID_VALUE_VECTOR_POS},
          relIDPos{INVALID_DATA_CHUNK_POS, INVALID_VALUE_VECTOR_POS} {}
    RelDeleteInfo(DataPos srcNodeIDPos, DataPos dstNodeIDPos, DataPos relIDPos)
        : srcNodeIDPos{srcNodeIDPos}, dstNodeIDPos{dstNodeIDPos}, relIDPos{relIDPos} {}
    EXPLICIT_COPY_DEFAULT_MOVE(RelDeleteInfo);

    void init(const ResultSet& resultSet);

private:
    RelDeleteInfo(const RelDeleteInfo& other)
        : srcNodeIDPos{other.srcNodeIDPos}, dstNodeIDPos{other.dstNodeIDPos},
          relIDPos{other.relIDPos} {}
};

class RelDeleteExecutor {
public:
    explicit RelDeleteExecutor(RelDeleteInfo info) : info{std::move(info)} {}
    RelDeleteExecutor(const RelDeleteExecutor& other) : info{other.info.copy()} {}
    virtual ~RelDeleteExecutor() = default;

    virtual void init(ResultSet* resultSet, ExecutionContext* context);

    virtual void delete_(ExecutionContext* context) = 0;

    virtual std::unique_ptr<RelDeleteExecutor> copy() const = 0;

protected:
    RelDeleteInfo info;
};

class EmptyRelDeleteExecutor final : public RelDeleteExecutor {
public:
    explicit EmptyRelDeleteExecutor() : RelDeleteExecutor{RelDeleteInfo{}} {}
    EmptyRelDeleteExecutor(const EmptyRelDeleteExecutor& other) : RelDeleteExecutor{other} {}

    void init(ResultSet*, ExecutionContext*) override {}

    void delete_(ExecutionContext*) override {}

    std::unique_ptr<RelDeleteExecutor> copy() const override {
        return std::make_unique<EmptyRelDeleteExecutor>(*this);
    }
};

class SingleLabelRelDeleteExecutor final : public RelDeleteExecutor {
public:
    SingleLabelRelDeleteExecutor(storage::RelTable* table, RelDeleteInfo info)
        : RelDeleteExecutor(std::move(info)), table{table} {}
    SingleLabelRelDeleteExecutor(const SingleLabelRelDeleteExecutor& other)
        : RelDeleteExecutor{other}, table{other.table} {}

    void delete_(ExecutionContext* context) override;

    std::unique_ptr<RelDeleteExecutor> copy() const override {
        return std::make_unique<SingleLabelRelDeleteExecutor>(*this);
    }

private:
    storage::RelTable* table;
};

class MultiLabelRelDeleteExecutor final : public RelDeleteExecutor {
public:
    MultiLabelRelDeleteExecutor(common::table_id_map_t<storage::RelTable*> tableIDToTableMap,
        RelDeleteInfo info)
        : RelDeleteExecutor(std::move(info)), tableIDToTableMap{std::move(tableIDToTableMap)} {}
    MultiLabelRelDeleteExecutor(const MultiLabelRelDeleteExecutor& other)
        : RelDeleteExecutor{other}, tableIDToTableMap{other.tableIDToTableMap} {}

    void delete_(ExecutionContext* context) override;

    std::unique_ptr<RelDeleteExecutor> copy() const override {
        return std::make_unique<MultiLabelRelDeleteExecutor>(*this);
    }

private:
    common::table_id_map_t<storage::RelTable*> tableIDToTableMap;
};

} // namespace processor
} // namespace lbug
