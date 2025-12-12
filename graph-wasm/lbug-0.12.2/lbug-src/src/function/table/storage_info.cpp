#include "binder/binder.h"
#include "common/data_chunk/data_chunk_collection.h"
#include "common/exception/binder.h"
#include "common/type_utils.h"
#include "common/types/interval_t.h"
#include "common/types/ku_string.h"
#include "common/types/types.h"
#include "function/table/bind_data.h"
#include "function/table/bind_input.h"
#include "function/table/simple_table_function.h"
#include "main/client_context.h"
#include "processor/execution_context.h"
#include "storage/storage_manager.h"
#include "storage/table/list_chunk_data.h"
#include "storage/table/list_column.h"
#include "storage/table/node_table.h"
#include "storage/table/rel_table.h"
#include "storage/table/string_chunk_data.h"
#include "storage/table/string_column.h"
#include "storage/table/struct_chunk_data.h"
#include "storage/table/struct_column.h"
#include <concepts>

using namespace lbug::common;
using namespace lbug::catalog;
using namespace lbug::storage;
using namespace lbug::main;

namespace lbug {
namespace function {

struct StorageInfoLocalState final : TableFuncLocalState {
    std::unique_ptr<DataChunkCollection> dataChunkCollection;
    idx_t currChunkIdx;

    explicit StorageInfoLocalState(MemoryManager* mm) : currChunkIdx{0} {
        dataChunkCollection = std::make_unique<DataChunkCollection>(mm);
    }
};

struct StorageInfoBindData final : TableFuncBindData {
    TableCatalogEntry* tableEntry;
    const ClientContext* context;

    StorageInfoBindData(binder::expression_vector columns, TableCatalogEntry* tableEntry,
        const ClientContext* context)
        : TableFuncBindData{std::move(columns), 1 /*maxOffset*/}, tableEntry{tableEntry},
          context{context} {}

    std::unique_ptr<TableFuncBindData> copy() const override {
        return std::make_unique<StorageInfoBindData>(columns, tableEntry, context);
    }
};

static std::unique_ptr<TableFuncLocalState> initLocalState(
    const TableFuncInitLocalStateInput& input) {
    return std::make_unique<StorageInfoLocalState>(MemoryManager::Get(*input.clientContext));
}

struct StorageInfoOutputData {
    node_group_idx_t nodeGroupIdx = INVALID_NODE_GROUP_IDX;
    node_group_idx_t chunkIdx = INVALID_NODE_GROUP_IDX;
    std::string tableType;
    uint32_t columnIdx = INVALID_COLUMN_ID;
    std::vector<const Column*> columns;
};

static void resetOutputIfNecessary(const StorageInfoLocalState* localState,
    DataChunk& outputChunk) {
    if (outputChunk.state->getSelVector().getSelSize() == DEFAULT_VECTOR_CAPACITY) {
        localState->dataChunkCollection->append(outputChunk);
        outputChunk.resetAuxiliaryBuffer();
        outputChunk.state->getSelVectorUnsafe().setSelSize(0);
    }
}

static void appendStorageInfoForChunkData(StorageInfoLocalState* localState, DataChunk& outputChunk,
    StorageInfoOutputData& outputData, const Column& column, const ColumnChunkData& chunkData,
    bool ignoreNull = false) {
    resetOutputIfNecessary(localState, outputChunk);
    auto vectorPos = outputChunk.state->getSelVector().getSelSize();
    auto residency = chunkData.getResidencyState();
    ColumnChunkMetadata metadata;
    switch (residency) {
    case ResidencyState::IN_MEMORY: {
        metadata = chunkData.getMetadataToFlush();
    } break;
    case ResidencyState::ON_DISK: {
        metadata = chunkData.getMetadata();
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
    auto& columnType = chunkData.getDataType();
    outputChunk.getValueVectorMutable(0).setValue(vectorPos, outputData.tableType);
    outputChunk.getValueVectorMutable(1).setValue<uint64_t>(vectorPos, outputData.nodeGroupIdx);
    outputChunk.getValueVectorMutable(2).setValue<uint64_t>(vectorPos, outputData.chunkIdx);
    outputChunk.getValueVectorMutable(3).setValue(vectorPos,
        ResidencyStateUtils::toString(residency));
    outputChunk.getValueVectorMutable(4).setValue(vectorPos, column.getName());
    outputChunk.getValueVectorMutable(5).setValue(vectorPos, columnType.toString());
    outputChunk.getValueVectorMutable(6).setValue<uint64_t>(vectorPos, metadata.getStartPageIdx());
    outputChunk.getValueVectorMutable(7).setValue<uint64_t>(vectorPos, metadata.getNumPages());
    outputChunk.getValueVectorMutable(8).setValue<uint64_t>(vectorPos, metadata.numValues);

    auto customToString = [&]<typename T>(T) {
        outputChunk.getValueVectorMutable(9).setValue(vectorPos,
            std::to_string(metadata.compMeta.min.get<T>()));
        outputChunk.getValueVectorMutable(10).setValue(vectorPos,
            std::to_string(metadata.compMeta.max.get<T>()));
    };
    auto physicalType = columnType.getPhysicalType();
    TypeUtils::visit(
        physicalType, [&](ku_string_t) { customToString(uint32_t()); },
        [&](list_entry_t) { customToString(uint64_t()); },
        [&](internalID_t) { customToString(uint64_t()); },
        [&]<typename T>(T)
            requires(std::integral<T> || std::floating_point<T>)
        {
            auto min = metadata.compMeta.min.get<T>();
            auto max = metadata.compMeta.max.get<T>();
            outputChunk.getValueVectorMutable(9).setValue(vectorPos,
                TypeUtils::entryToString(columnType, (uint8_t*)&min,
                    &outputChunk.getValueVectorMutable(9)));
            outputChunk.getValueVectorMutable(10).setValue(vectorPos,
                TypeUtils::entryToString(columnType, (uint8_t*)&max,
                    &outputChunk.getValueVectorMutable(10)));
        },
        // Types which don't support statistics.
        // types not supported by TypeUtils::visit can
        // also be ignored since we don't track statistics for them
        [](int128_t) {}, [](struct_entry_t) {}, [](interval_t) {}, [](uint128_t) {});
    outputChunk.getValueVectorMutable(11).setValue(vectorPos,
        metadata.compMeta.toString(physicalType));
    outputChunk.state->getSelVectorUnsafe().incrementSelSize();
    if (columnType.getPhysicalType() == PhysicalTypeID::INTERNAL_ID) {
        ignoreNull = true;
    }
    if (!ignoreNull && chunkData.hasNullData()) {
        appendStorageInfoForChunkData(localState, outputChunk, outputData, *column.getNullColumn(),
            *chunkData.getNullData());
    }
    switch (columnType.getPhysicalType()) {
    case PhysicalTypeID::STRUCT: {
        auto& structChunk = chunkData.cast<StructChunkData>();
        const auto& structColumn = ku_dynamic_cast<const StructColumn&>(column);
        auto numChildren = structChunk.getNumChildren();
        for (auto i = 0u; i < numChildren; i++) {
            appendStorageInfoForChunkData(localState, outputChunk, outputData,
                *structColumn.getChild(i), structChunk.getChild(i));
        }
    } break;
    case PhysicalTypeID::STRING: {
        auto& stringChunk = chunkData.cast<StringChunkData>();
        auto& dictionaryChunk = stringChunk.getDictionaryChunk();
        const auto& stringColumn = ku_dynamic_cast<const StringColumn&>(column);
        appendStorageInfoForChunkData(localState, outputChunk, outputData,
            *stringColumn.getIndexColumn(), *stringChunk.getIndexColumnChunk());
        appendStorageInfoForChunkData(localState, outputChunk, outputData,
            *stringColumn.getDictionary().getDataColumn(), *dictionaryChunk.getStringDataChunk());
        appendStorageInfoForChunkData(localState, outputChunk, outputData,
            *stringColumn.getDictionary().getOffsetColumn(), *dictionaryChunk.getOffsetChunk());
    } break;
    case PhysicalTypeID::ARRAY:
    case PhysicalTypeID::LIST: {
        auto& listChunk = chunkData.cast<ListChunkData>();
        const auto& listColumn = ku_dynamic_cast<const ListColumn&>(column);
        appendStorageInfoForChunkData(localState, outputChunk, outputData,
            *listColumn.getOffsetColumn(), *listChunk.getOffsetColumnChunk());
        appendStorageInfoForChunkData(localState, outputChunk, outputData,
            *listColumn.getSizeColumn(), *listChunk.getSizeColumnChunk());
        appendStorageInfoForChunkData(localState, outputChunk, outputData,
            *listColumn.getDataColumn(), *listChunk.getDataColumnChunk());
    } break;
    default: {
        // DO NOTHING.
    }
    }
}

static void appendStorageInfoForChunkedGroup(StorageInfoLocalState* localState,
    DataChunk& outputChunk, StorageInfoOutputData& outputData, ChunkedNodeGroup* chunkedGroup) {
    auto numColumns = chunkedGroup->getNumColumns();
    outputData.columnIdx = 0;
    for (auto i = 0u; i < numColumns; i++) {
        for (auto* segment : chunkedGroup->getColumnChunk(i).getSegments()) {
            appendStorageInfoForChunkData(localState, outputChunk, outputData,
                *outputData.columns[i], *segment);
        }
    }
    if (chunkedGroup->getFormat() == NodeGroupDataFormat::CSR) {
        auto& chunkedCSRGroup = chunkedGroup->cast<ChunkedCSRNodeGroup>();
        for (auto* segment : chunkedCSRGroup.getCSRHeader().offset->getSegments()) {
            appendStorageInfoForChunkData(localState, outputChunk, outputData,
                *outputData.columns[numColumns], *segment, true);
        }
        for (auto* segment : chunkedCSRGroup.getCSRHeader().length->getSegments()) {
            appendStorageInfoForChunkData(localState, outputChunk, outputData,
                *outputData.columns[numColumns + 1], *segment, true);
        }
    }
}

static void appendStorageInfoForNodeGroup(StorageInfoLocalState* localState, DataChunk& outputChunk,
    StorageInfoOutputData& outputData, NodeGroup* nodeGroup) {
    auto numChunks = nodeGroup->getNumChunkedGroups();
    for (auto chunkIdx = 0ul; chunkIdx < numChunks; chunkIdx++) {
        outputData.chunkIdx = chunkIdx;
        appendStorageInfoForChunkedGroup(localState, outputChunk, outputData,
            nodeGroup->getChunkedNodeGroup(chunkIdx));
    }
    if (nodeGroup->getFormat() == NodeGroupDataFormat::CSR) {
        auto& csrNodeGroup = nodeGroup->cast<CSRNodeGroup>();
        auto persistentChunk = csrNodeGroup.getPersistentChunkedGroup();
        if (persistentChunk) {
            outputData.chunkIdx = INVALID_NODE_GROUP_IDX;
            appendStorageInfoForChunkedGroup(localState, outputChunk, outputData,
                csrNodeGroup.getPersistentChunkedGroup());
        }
    }
}

static offset_t tableFunc(const TableFuncInput& input, TableFuncOutput& output) {
    auto& dataChunk = output.dataChunk;
    auto localState = ku_dynamic_cast<StorageInfoLocalState*>(input.localState);
    KU_ASSERT(dataChunk.state->getSelVector().isUnfiltered());
    auto storageManager = StorageManager::Get(*input.context->clientContext);
    while (true) {
        if (localState->currChunkIdx < localState->dataChunkCollection->getNumChunks()) {
            // Copy from local state chunk.
            const auto& chunk =
                localState->dataChunkCollection->getChunkUnsafe(localState->currChunkIdx);
            const auto numValuesToOutput = chunk.state->getSelVector().getSelSize();
            for (auto columnIdx = 0u; columnIdx < dataChunk.getNumValueVectors(); columnIdx++) {
                const auto& localVector = chunk.getValueVector(columnIdx);
                auto& outputVector = dataChunk.getValueVectorMutable(columnIdx);
                for (auto i = 0u; i < numValuesToOutput; i++) {
                    outputVector.copyFromVectorData(i, &localVector, i);
                }
            }
            dataChunk.state->getSelVectorUnsafe().setToUnfiltered(numValuesToOutput);
            localState->currChunkIdx++;
            return numValuesToOutput;
        }
        auto morsel = input.sharedState->ptrCast<SimpleTableFuncSharedState>()->getMorsel();
        if (!morsel.hasMoreToOutput()) {
            return 0;
        }
        const auto bindData = input.bindData->constPtrCast<StorageInfoBindData>();
        StorageInfoOutputData outputData;
        node_group_idx_t numNodeGroups = 0;
        switch (bindData->tableEntry->getTableType()) {
        case TableType::NODE: {
            outputData.tableType = "NODE";
            auto table = storageManager->getTable(bindData->tableEntry->getTableID());
            auto& nodeTable = table->cast<NodeTable>();
            std::vector<const Column*> columns;
            for (auto columnID = 0u; columnID < nodeTable.getNumColumns(); columnID++) {
                columns.push_back(&nodeTable.getColumn(columnID));
            }
            outputData.columns = std::move(columns);
            numNodeGroups = nodeTable.getNumNodeGroups();
            for (auto i = 0ul; i < numNodeGroups; i++) {
                outputData.nodeGroupIdx = i;
                appendStorageInfoForNodeGroup(localState, dataChunk, outputData,
                    nodeTable.getNodeGroup(i));
            }
        } break;
        case TableType::REL: {
            outputData.tableType = "REL";
            for (auto innerEntryInfo :
                bindData->tableEntry->cast<RelGroupCatalogEntry>().getRelEntryInfos()) {
                auto& relTable = storageManager->getTable(innerEntryInfo.oid)->cast<RelTable>();
                auto appendDirectedStorageInfo = [&](RelDataDirection direction) {
                    auto directedRelTableData = relTable.getDirectedTableData(direction);
                    std::vector<const Column*> columns;
                    for (auto columnID = 0u; columnID < relTable.getNumColumns(); columnID++) {
                        columns.push_back(directedRelTableData->getColumn(columnID));
                    }
                    columns.push_back(directedRelTableData->getCSROffsetColumn());
                    columns.push_back(directedRelTableData->getCSRLengthColumn());
                    outputData.columns = std::move(columns);
                    numNodeGroups = directedRelTableData->getNumNodeGroups();
                    for (auto i = 0ul; i < numNodeGroups; i++) {
                        outputData.nodeGroupIdx = i;
                        appendStorageInfoForNodeGroup(localState, dataChunk, outputData,
                            directedRelTableData->getNodeGroup(i));
                    }
                };
                for (auto direction : relTable.getStorageDirections()) {
                    appendDirectedStorageInfo(direction);
                }
            }
        } break;
        default: {
            KU_UNREACHABLE;
        }
        }
        localState->dataChunkCollection->append(dataChunk);
        dataChunk.resetAuxiliaryBuffer();
        dataChunk.state->getSelVectorUnsafe().setSelSize(0);
    }
}

static std::unique_ptr<TableFuncBindData> bindFunc(const ClientContext* context,
    const TableFuncBindInput* input) {
    std::vector<std::string> columnNames = {"table_type", "node_group_id", "node_chunk_id",
        "residency", "column_name", "data_type", "start_page_idx", "num_pages", "num_values", "min",
        "max", "compression"};
    std::vector<LogicalType> columnTypes;
    columnTypes.emplace_back(LogicalType::STRING());
    columnTypes.emplace_back(LogicalType::INT64());
    columnTypes.emplace_back(LogicalType::INT64());
    columnTypes.emplace_back(LogicalType::STRING());
    columnTypes.emplace_back(LogicalType::STRING());
    columnTypes.emplace_back(LogicalType::STRING());
    columnTypes.emplace_back(LogicalType::INT64());
    columnTypes.emplace_back(LogicalType::INT64());
    columnTypes.emplace_back(LogicalType::INT64());
    columnTypes.emplace_back(LogicalType::STRING());
    columnTypes.emplace_back(LogicalType::STRING());
    columnTypes.emplace_back(LogicalType::STRING());
    auto tableName = input->getLiteralVal<std::string>(0);
    auto catalog = Catalog::Get(*context);
    if (!catalog->containsTable(transaction::Transaction::Get(*context), tableName)) {
        throw BinderException{"Table " + tableName + " does not exist!"};
    }
    auto tableEntry =
        catalog->getTableCatalogEntry(transaction::Transaction::Get(*context), tableName);
    columnNames = TableFunction::extractYieldVariables(columnNames, input->yieldVariables);
    auto columns = input->binder->createVariables(columnNames, columnTypes);
    return std::make_unique<StorageInfoBindData>(columns, tableEntry, context);
}

function_set StorageInfoFunction::getFunctionSet() {
    function_set functionSet;
    auto function = std::make_unique<TableFunction>(name, std::vector{LogicalTypeID::STRING});
    function->tableFunc = tableFunc;
    function->bindFunc = bindFunc;
    function->initSharedStateFunc = SimpleTableFunc::initSharedState;
    function->initLocalStateFunc = initLocalState;
    functionSet.push_back(std::move(function));
    return functionSet;
}

} // namespace function
} // namespace lbug
