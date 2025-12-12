#include "storage/table/table.h"

#include "storage/storage_manager.h"
#include "storage/table/node_table.h"
#include "storage/table/rel_table.h"

using namespace lbug::common;

namespace lbug {
namespace storage {

TableScanState::~TableScanState() = default;

// NOLINTNEXTLINE(readability-make-member-function-const): Semantically non-const.
void TableScanState::resetOutVectors() {
    for (const auto& outputVector : outputVectors) {
        KU_ASSERT(outputVector->state.get() == outState.get());
        KU_UNUSED(outputVector);
        outputVector->resetAuxiliaryBuffer();
    }
    outState->getSelVectorUnsafe().setToUnfiltered();
}

void TableScanState::setToTable(const transaction::Transaction*, Table* table_,
    std::vector<column_id_t> columnIDs_, std::vector<ColumnPredicateSet> columnPredicateSets_,
    RelDataDirection) {
    table = table_;
    columnIDs = std::move(columnIDs_);
    columnPredicateSets = std::move(columnPredicateSets_);
    nodeGroupScanState->chunkStates.resize(columnIDs.size());
}

TableInsertState::TableInsertState(std::vector<ValueVector*> propertyVectors)
    : propertyVectors{std::move(propertyVectors)}, logToWAL{true} {}
TableInsertState::~TableInsertState() = default;
TableUpdateState::TableUpdateState(column_id_t columnID, ValueVector& propertyVector)
    : columnID{columnID}, propertyVector{propertyVector}, logToWAL{true} {}
TableUpdateState::~TableUpdateState() = default;
TableDeleteState::TableDeleteState() : logToWAL{true} {}
TableDeleteState::~TableDeleteState() = default;

Table::Table(const catalog::TableCatalogEntry* tableEntry, const StorageManager* storageManager,
    MemoryManager* memoryManager)
    : tableType{tableEntry->getTableType()}, tableID{tableEntry->getTableID()},
      tableName{tableEntry->getName()}, enableCompression{storageManager->compressionEnabled()},
      memoryManager{memoryManager}, shadowFile{&storageManager->getShadowFile()},
      hasChanges{false} {}

Table::~Table() = default;

bool Table::scan(transaction::Transaction* transaction, TableScanState& scanState) {
    return scanInternal(transaction, scanState);
}

DataChunk Table::constructDataChunk(MemoryManager* mm, std::vector<LogicalType> types) {
    DataChunk dataChunk(types.size());
    for (auto i = 0u; i < types.size(); i++) {
        auto valueVector = std::make_unique<ValueVector>(std::move(types[i]), mm);
        dataChunk.insert(i, std::move(valueVector));
    }
    return dataChunk;
}

} // namespace storage
} // namespace lbug
