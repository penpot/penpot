#include "main/storage_driver.h"

#include <thread>

#include "catalog/catalog_entry/table_catalog_entry.h"
#include "main/client_context.h"
#include "storage/storage_manager.h"
#include "storage/table/node_table.h"

using namespace lbug::common;
using namespace lbug::transaction;
using namespace lbug::storage;
using namespace lbug::catalog;

namespace lbug {
namespace main {

StorageDriver::StorageDriver(Database* database) {
    clientContext = std::make_unique<ClientContext>(database);
}

StorageDriver::~StorageDriver() = default;

static TableCatalogEntry* getEntry(const ClientContext& context, const std::string& tableName) {
    return Catalog::Get(context)->getTableCatalogEntry(Transaction::Get(context), tableName);
}

static Table* getTable(const ClientContext& context, const std::string& tableName) {
    return StorageManager::Get(context)->getTable(getEntry(context, tableName)->getTableID());
}

static bool validateNumericalType(const LogicalType& type) {
    switch (type.getLogicalTypeID()) {
    case LogicalTypeID::BOOL:
    case LogicalTypeID::INT128:
    case LogicalTypeID::INT64:
    case LogicalTypeID::INT32:
    case LogicalTypeID::INT16:
    case LogicalTypeID::INT8:
    case LogicalTypeID::UINT64:
    case LogicalTypeID::UINT32:
    case LogicalTypeID::UINT16:
    case LogicalTypeID::UINT8:
    case LogicalTypeID::DOUBLE:
    case LogicalTypeID::FLOAT:
        return true;
    default:
        return false;
    }
}

static std::string getUnsupportedTypeErrMsg(const LogicalType& type) {
    return stringFormat("Unsupported data type {}.", type.toString());
}

static uint32_t getElementSize(const LogicalType& type) {
    switch (type.getLogicalTypeID()) {
    case LogicalTypeID::BOOL:
    case LogicalTypeID::INT128:
    case LogicalTypeID::INT64:
    case LogicalTypeID::INT32:
    case LogicalTypeID::INT16:
    case LogicalTypeID::INT8:
    case LogicalTypeID::UINT64:
    case LogicalTypeID::UINT32:
    case LogicalTypeID::UINT16:
    case LogicalTypeID::UINT8:
    case LogicalTypeID::DOUBLE:
    case LogicalTypeID::FLOAT:
        return PhysicalTypeUtils::getFixedTypeSize(type.getPhysicalType());
    case LogicalTypeID::ARRAY: {
        auto& childType = ArrayType::getChildType(type);
        if (!validateNumericalType(childType)) {
            throw RuntimeException(getUnsupportedTypeErrMsg(type));
        }
        auto numElements = ArrayType::getNumElements(type);
        return numElements * PhysicalTypeUtils::getFixedTypeSize(childType.getPhysicalType());
    }
    default:
        throw RuntimeException(getUnsupportedTypeErrMsg(type));
    }
}

void StorageDriver::scan(const std::string& nodeName, const std::string& propertyName,
    common::offset_t* offsets, size_t numOffsets, uint8_t* result, size_t numThreads) {
    clientContext->query("BEGIN TRANSACTION READ ONLY;");
    auto entry = getEntry(*clientContext, nodeName);
    auto columnID = entry->getColumnID(propertyName);
    auto table = getTable(*clientContext, nodeName);
    auto& dataType = table->ptrCast<NodeTable>()->getColumn(columnID).getDataType();
    auto elementSize = getElementSize(dataType);
    auto numOffsetsPerThread = numOffsets / numThreads + 1;
    auto remainingNumOffsets = numOffsets;
    auto current_buffer = result;
    std::vector<std::thread> threads;
    while (remainingNumOffsets > 0) {
        auto numOffsetsToScan = std::min(numOffsetsPerThread, remainingNumOffsets);
        threads.emplace_back(&StorageDriver::scanColumn, this, table, columnID, offsets,
            numOffsetsToScan, current_buffer);
        offsets += numOffsetsToScan;
        current_buffer += numOffsetsToScan * elementSize;
        remainingNumOffsets -= numOffsetsToScan;
    }
    for (auto& thread : threads) {
        thread.join();
    }
    clientContext->query("COMMIT");
}

uint64_t StorageDriver::getNumNodes(const std::string& nodeName) const {
    clientContext->query("BEGIN TRANSACTION READ ONLY;");
    auto transaction = Transaction::Get(*clientContext);
    auto result = getTable(*clientContext, nodeName)->getNumTotalRows(transaction);
    clientContext->query("COMMIT");
    return result;
}

uint64_t StorageDriver::getNumRels(const std::string& relName) const {
    clientContext->query("BEGIN TRANSACTION READ ONLY;");
    auto transaction = Transaction::Get(*clientContext);
    auto result = getTable(*clientContext, relName)->getNumTotalRows(transaction);
    clientContext->query("COMMIT");
    return result;
}

void StorageDriver::scanColumn(Table* table, column_id_t columnID, const offset_t* offsets,
    size_t size, uint8_t* result) const {
    // Create scan state.
    auto nodeTable = table->ptrCast<NodeTable>();
    auto column = &nodeTable->getColumn(columnID);
    // Create value vectors
    auto idVector = std::make_unique<ValueVector>(LogicalType::INTERNAL_ID());
    auto columnVector = std::make_unique<ValueVector>(column->getDataType().copy(),
        MemoryManager::Get(*clientContext));
    auto vectorState = DataChunkState::getSingleValueDataChunkState();
    idVector->state = vectorState;
    columnVector->state = vectorState;
    auto scanState = std::make_unique<NodeTableScanState>(idVector.get(),
        std::vector{columnVector.get()}, vectorState);
    auto transaction = Transaction::Get(*clientContext);
    switch (auto physicalType = column->getDataType().getPhysicalType()) {
    case PhysicalTypeID::BOOL:
    case PhysicalTypeID::INT128:
    case PhysicalTypeID::INT64:
    case PhysicalTypeID::INT32:
    case PhysicalTypeID::INT16:
    case PhysicalTypeID::INT8:
    case PhysicalTypeID::UINT64:
    case PhysicalTypeID::UINT32:
    case PhysicalTypeID::UINT16:
    case PhysicalTypeID::UINT8:
    case PhysicalTypeID::DOUBLE:
    case PhysicalTypeID::FLOAT: {
        for (auto i = 0u; i < size; ++i) {
            idVector->setValue(0, nodeID_t{offsets[i], table->getTableID()});
            [[maybe_unused]] auto res = nodeTable->lookup(transaction, *scanState);
            memcpy(result, columnVector->getData(),
                PhysicalTypeUtils::getFixedTypeSize(physicalType));
        }
    } break;
    case PhysicalTypeID::ARRAY: {
        auto& childType = ArrayType::getChildType(column->getDataType());
        auto elementSize = PhysicalTypeUtils::getFixedTypeSize(childType.getPhysicalType());
        auto numElements = ArrayType::getNumElements(column->getDataType());
        auto arraySize = elementSize * numElements;
        for (auto i = 0u; i < size; ++i) {
            idVector->setValue(0, nodeID_t{offsets[i], table->getTableID()});
            [[maybe_unused]] auto res = nodeTable->lookup(transaction, *scanState);
            auto dataVector = ListVector::getDataVector(columnVector.get());
            memcpy(result, dataVector->getData() + i * arraySize, arraySize);
        }
    } break;
    default:
        throw RuntimeException(stringFormat("Not supported data type in StorageDriver::scanColumn",
            PhysicalTypeUtils::toString(physicalType)));
    }
}

} // namespace main
} // namespace lbug
