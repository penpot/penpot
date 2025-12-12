#include "binder/expression/node_expression.h"
#include "binder/expression/rel_expression.h"
#include "catalog/catalog_entry/node_table_catalog_entry.h"
#include "planner/operator/persistent/logical_delete.h"
#include "processor/operator/persistent/delete.h"
#include "processor/plan_mapper.h"
#include "storage/storage_manager.h"

using namespace lbug::binder;
using namespace lbug::catalog;
using namespace lbug::common;
using namespace lbug::planner;
using namespace lbug::storage;

namespace lbug {
namespace processor {

std::vector<RelTable*> getFwdRelTables(table_id_t nodeTableID, const main::ClientContext* context) {
    std::vector<RelTable*> result;
    auto transaction = transaction::Transaction::Get(*context);
    for (const auto entry : Catalog::Get(*context)->getRelGroupEntries(transaction, false)) {
        auto& relGroupEntry = entry->constCast<RelGroupCatalogEntry>();
        for (auto& relEntryInfo : relGroupEntry.getRelEntryInfos()) {
            const auto srcTableID = relEntryInfo.nodePair.srcTableID;
            if (srcTableID == nodeTableID) {
                const auto relTable = StorageManager::Get(*context)->getTable(relEntryInfo.oid);
                result.push_back(relTable->ptrCast<RelTable>());
            }
        }
    }
    return result;
}

std::vector<RelTable*> getBwdRelTables(table_id_t nodeTableID, const main::ClientContext* context) {
    std::vector<RelTable*> result;
    auto transaction = transaction::Transaction::Get(*context);
    for (const auto entry : Catalog::Get(*context)->getRelGroupEntries(transaction, false)) {
        auto& relGroupEntry = entry->constCast<RelGroupCatalogEntry>();
        for (auto& relEntryInfo : relGroupEntry.getRelEntryInfos()) {
            const auto dstTableID = relEntryInfo.nodePair.dstTableID;
            if (dstTableID == nodeTableID) {
                const auto relTable = StorageManager::Get(*context)->getTable(relEntryInfo.oid);
                result.push_back(relTable->ptrCast<RelTable>());
            }
        }
    }
    return result;
}

NodeTableDeleteInfo PlanMapper::getNodeTableDeleteInfo(const TableCatalogEntry& entry,
    DataPos pkPos) const {
    auto storageManager = StorageManager::Get(*clientContext);
    auto tableID = entry.getTableID();
    auto table = storageManager->getTable(tableID)->ptrCast<NodeTable>();
    std::unordered_set<RelTable*> fwdRelTables;
    std::unordered_set<RelTable*> bwdRelTables;
    auto& nodeEntry = entry.constCast<NodeTableCatalogEntry>();
    for (auto relTable : getFwdRelTables(nodeEntry.getTableID(), clientContext)) {
        fwdRelTables.insert(relTable);
    }
    for (auto relTable : getBwdRelTables(nodeEntry.getTableID(), clientContext)) {
        bwdRelTables.insert(relTable);
    }
    return NodeTableDeleteInfo(table, std::move(fwdRelTables), std::move(bwdRelTables), pkPos);
}

std::unique_ptr<NodeDeleteExecutor> PlanMapper::getNodeDeleteExecutor(
    const BoundDeleteInfo& boundInfo, const Schema& schema) const {
    KU_ASSERT(boundInfo.tableType == TableType::NODE);
    auto& node = boundInfo.pattern->constCast<NodeExpression>();
    auto nodeIDPos = getDataPos(*node.getInternalID(), schema);
    auto info = NodeDeleteInfo(boundInfo.deleteType, nodeIDPos);
    if (node.isEmpty()) {
        return std::make_unique<EmptyNodeDeleteExecutor>(std::move(info));
    }
    if (node.isMultiLabeled()) {
        table_id_map_t<NodeTableDeleteInfo> tableInfos;
        for (auto entry : node.getEntries()) {
            auto tableID = entry->getTableID();
            auto pkPos = getDataPos(*node.getPrimaryKey(tableID), schema);
            tableInfos.insert({tableID, getNodeTableDeleteInfo(*entry, pkPos)});
        }
        return std::make_unique<MultiLabelNodeDeleteExecutor>(std::move(info),
            std::move(tableInfos));
    }
    KU_ASSERT(node.getNumEntries() == 1);
    auto entry = node.getEntry(0);
    auto pkPos = getDataPos(*node.getPrimaryKey(entry->getTableID()), schema);
    auto extraInfo = getNodeTableDeleteInfo(*entry, pkPos);
    return std::make_unique<SingleLabelNodeDeleteExecutor>(std::move(info), std::move(extraInfo));
}

std::unique_ptr<PhysicalOperator> PlanMapper::mapDelete(const LogicalOperator* logicalOperator) {
    auto delete_ = logicalOperator->constPtrCast<LogicalDelete>();
    switch (delete_->getTableType()) {
    case TableType::NODE: {
        return mapDeleteNode(logicalOperator);
    }
    case TableType::REL: {
        return mapDeleteRel(logicalOperator);
    }
    default:
        KU_UNREACHABLE;
    }
}

std::unique_ptr<PhysicalOperator> PlanMapper::mapDeleteNode(
    const LogicalOperator* logicalOperator) {
    auto delete_ = logicalOperator->constPtrCast<LogicalDelete>();
    auto inSchema = delete_->getChild(0)->getSchema();
    auto prevOperator = mapOperator(logicalOperator->getChild(0).get());
    std::vector<std::unique_ptr<NodeDeleteExecutor>> executors;
    for (auto& info : delete_->getInfos()) {
        executors.push_back(getNodeDeleteExecutor(info, *inSchema));
    }
    expression_vector patterns;
    for (auto& info : delete_->getInfos()) {
        patterns.push_back(info.pattern);
    }
    auto printInfo =
        std::make_unique<DeleteNodePrintInfo>(patterns, delete_->getInfos()[0].deleteType);
    return std::make_unique<DeleteNode>(std::move(executors), std::move(prevOperator),
        getOperatorID(), std::move(printInfo));
}

std::unique_ptr<RelDeleteExecutor> PlanMapper::getRelDeleteExecutor(
    const BoundDeleteInfo& boundInfo, const Schema& schema) const {
    auto& rel = boundInfo.pattern->constCast<RelExpression>();
    if (rel.isEmpty()) {
        return std::make_unique<EmptyRelDeleteExecutor>();
    }
    auto relIDPos = getDataPos(*rel.getInternalID(), schema);
    auto srcNodeIDPos = getDataPos(*rel.getSrcNode()->getInternalID(), schema);
    auto dstNodeIDPos = getDataPos(*rel.getDstNode()->getInternalID(), schema);
    auto info = RelDeleteInfo(srcNodeIDPos, dstNodeIDPos, relIDPos);
    auto storageManager = StorageManager::Get(*clientContext);
    if (rel.isMultiLabeled()) {
        table_id_map_t<RelTable*> tableIDToTableMap;
        for (auto entry : rel.getEntries()) {
            auto& relGroupEntry = entry->constCast<RelGroupCatalogEntry>();
            for (auto& relEntryInfo : relGroupEntry.getRelEntryInfos()) {
                auto table = storageManager->getTable(relEntryInfo.oid);
                tableIDToTableMap.insert({table->getTableID(), table->ptrCast<RelTable>()});
            }
        }
        return std::make_unique<MultiLabelRelDeleteExecutor>(std::move(tableIDToTableMap),
            std::move(info));
    }
    KU_ASSERT(rel.getNumEntries() == 1);
    auto& entry = rel.getEntry(0)->constCast<RelGroupCatalogEntry>();
    auto relEntryInfo = entry.getSingleRelEntryInfo();
    auto table = storageManager->getTable(relEntryInfo.oid)->ptrCast<RelTable>();
    return std::make_unique<SingleLabelRelDeleteExecutor>(table, std::move(info));
}

std::unique_ptr<PhysicalOperator> PlanMapper::mapDeleteRel(const LogicalOperator* logicalOperator) {
    auto delete_ = logicalOperator->constPtrCast<LogicalDelete>();
    auto inSchema = delete_->getChild(0)->getSchema();
    auto prevOperator = mapOperator(logicalOperator->getChild(0).get());
    std::vector<std::unique_ptr<RelDeleteExecutor>> executors;
    for (auto& info : delete_->getInfos()) {
        executors.push_back(getRelDeleteExecutor(info, *inSchema));
    }
    expression_vector patterns;
    for (auto& info : delete_->getInfos()) {
        patterns.push_back(info.pattern);
    }
    auto printInfo = std::make_unique<DeleteRelPrintInfo>(patterns);
    return std::make_unique<DeleteRel>(std::move(executors), std::move(prevOperator),
        getOperatorID(), std::move(printInfo));
}

} // namespace processor
} // namespace lbug
