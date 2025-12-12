#include "processor/operator/scan/scan_node_table.h"

#include "binder/expression/expression_util.h"
#include "processor/execution_context.h"
#include "storage/local_storage/local_node_table.h"
#include "storage/local_storage/local_storage.h"

using namespace lbug::common;
using namespace lbug::storage;

namespace lbug {
namespace processor {

std::string ScanNodeTablePrintInfo::toString() const {
    std::string result = "Tables: ";
    for (auto& tableName : tableNames) {
        result += tableName;
        if (tableName != tableNames.back()) {
            result += ", ";
        }
    }
    if (!alias.empty()) {
        result += ",Alias: ";
        result += alias;
    }
    if (!properties.empty()) {
        result += ",Properties: ";
        result += binder::ExpressionUtil::toString(properties);
    }
    return result;
}

void ScanNodeTableSharedState::initialize(const transaction::Transaction* transaction,
    NodeTable* table, ScanNodeTableProgressSharedState& progressSharedState) {
    this->table = table;
    this->currentCommittedGroupIdx = 0;
    this->currentUnCommittedGroupIdx = 0;
    this->numCommittedNodeGroups = table->getNumCommittedNodeGroups();
    if (transaction->isWriteTransaction()) {
        if (const auto localTable =
                transaction->getLocalStorage()->getLocalTable(this->table->getTableID())) {
            auto& localNodeTable = localTable->cast<LocalNodeTable>();
            this->numUnCommittedNodeGroups = localNodeTable.getNumNodeGroups();
        }
    }
    progressSharedState.numGroups += numCommittedNodeGroups;
}

void ScanNodeTableSharedState::nextMorsel(NodeTableScanState& scanState,
    ScanNodeTableProgressSharedState& progressSharedState) {
    std::unique_lock lck{mtx};
    if (currentCommittedGroupIdx < numCommittedNodeGroups) {
        scanState.nodeGroupIdx = currentCommittedGroupIdx++;
        progressSharedState.numGroupsScanned++;
        scanState.source = TableScanSource::COMMITTED;
        return;
    }
    if (currentUnCommittedGroupIdx < numUnCommittedNodeGroups) {
        scanState.nodeGroupIdx = currentUnCommittedGroupIdx++;
        scanState.source = TableScanSource::UNCOMMITTED;
        return;
    }
    scanState.source = TableScanSource::NONE;
}

table_id_map_t<SemiMask*> ScanNodeTable::getSemiMasks() const {
    table_id_map_t<SemiMask*> result;
    KU_ASSERT(tableInfos.size() == sharedStates.size());
    for (auto i = 0u; i < sharedStates.size(); ++i) {
        result.insert({tableInfos[i].table->getTableID(), sharedStates[i]->getSemiMask()});
    }
    return result;
}

void ScanNodeTableInfo::initScanState(TableScanState& scanState,
    const std::vector<ValueVector*>& outVectors, main::ClientContext* context) {
    auto transaction = transaction::Transaction::Get(*context);
    scanState.setToTable(transaction, table, columnIDs, copyVector(columnPredicates));
    initScanStateVectors(scanState, outVectors, MemoryManager::Get(*context));
}

void ScanNodeTable::initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) {
    ScanTable::initLocalStateInternal(resultSet, context);
    auto nodeIDVector = resultSet->getValueVector(opInfo.nodeIDPos).get();
    scanState = std::make_unique<NodeTableScanState>(nodeIDVector, outVectors, nodeIDVector->state);
    currentTableIdx = 0;
    initCurrentTable(context);
}

void ScanNodeTable::initCurrentTable(ExecutionContext* context) {
    auto& currentInfo = tableInfos[currentTableIdx];
    currentInfo.initScanState(*scanState, outVectors, context->clientContext);
    scanState->semiMask = sharedStates[currentTableIdx]->getSemiMask();
}

void ScanNodeTable::initGlobalStateInternal(ExecutionContext* context) {
    KU_ASSERT(sharedStates.size() == tableInfos.size());
    for (auto i = 0u; i < tableInfos.size(); i++) {
        sharedStates[i]->initialize(transaction::Transaction::Get(*context->clientContext),
            tableInfos[i].table->ptrCast<NodeTable>(), *progressSharedState);
    }
}

bool ScanNodeTable::getNextTuplesInternal(ExecutionContext* context) {
    const auto transaction = transaction::Transaction::Get(*context->clientContext);
    while (currentTableIdx < tableInfos.size()) {
        auto& info = tableInfos[currentTableIdx];
        while (info.table->scan(transaction, *scanState)) {
            const auto outputSize = scanState->outState->getSelVector().getSelSize();
            if (outputSize > 0) {
                info.castColumns();
                scanState->outState->setToUnflat();
                metrics->numOutputTuple.increase(outputSize);
                return true;
            }
        }
        sharedStates[currentTableIdx]->nextMorsel(*scanState, *progressSharedState);
        if (scanState->source == TableScanSource::NONE) {
            currentTableIdx++;
            if (currentTableIdx < tableInfos.size()) {
                initCurrentTable(context);
            }
        } else {
            info.table->initScanState(transaction, *scanState);
        }
    }
    return false;
}

double ScanNodeTable::getProgress(ExecutionContext* /*context*/) const {
    if (currentTableIdx >= tableInfos.size()) {
        return 1.0;
    }
    if (progressSharedState->numGroups == 0) {
        return 0.0;
    }
    return static_cast<double>(progressSharedState->numGroupsScanned) /
           progressSharedState->numGroups;
}

} // namespace processor
} // namespace lbug
