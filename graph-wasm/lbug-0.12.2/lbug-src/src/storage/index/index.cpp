#include "storage/index/index.h"

#include "common/exception/runtime.h"
#include "common/serializer/deserializer.h"
#include "common/serializer/serializer.h"
#include "main/client_context.h"
#include "storage/storage_manager.h"

namespace lbug {
namespace storage {

IndexStorageInfo::~IndexStorageInfo() = default;

Index::InsertState::~InsertState() = default;

Index::UpdateState::~UpdateState() = default;

Index::DeleteState::~DeleteState() = default;

Index::~Index() = default;

bool Index::isBuiltOnColumn(common::column_id_t columnID) const {
    auto it = std::find(indexInfo.columnIDs.begin(), indexInfo.columnIDs.end(), columnID);
    return it != indexInfo.columnIDs.end();
}

void IndexInfo::serialize(common::Serializer& ser) const {
    ser.write<std::string>(name);
    ser.write<std::string>(indexType);
    ser.write<common::table_id_t>(tableID);
    ser.serializeVector(columnIDs);
    ser.serializeVector(keyDataTypes);
    ser.write<bool>(isPrimary);
    ser.write<bool>(isBuiltin);
}

IndexInfo IndexInfo::deserialize(common::Deserializer& deSer) {
    std::string name;
    std::string indexType;
    common::table_id_t tableID = common::INVALID_TABLE_ID;
    std::vector<common::column_id_t> columnIDs;
    std::vector<common::PhysicalTypeID> keyDataTypes;
    bool isPrimary = false;
    bool isBuiltin = false;
    deSer.deserializeValue(name);
    deSer.deserializeValue(indexType);
    deSer.deserializeValue<common::table_id_t>(tableID);
    deSer.deserializeVector(columnIDs);
    deSer.deserializeVector(keyDataTypes);
    deSer.deserializeValue<bool>(isPrimary);
    deSer.deserializeValue<bool>(isBuiltin);
    return IndexInfo{std::move(name), std::move(indexType), tableID, std::move(columnIDs),
        std::move(keyDataTypes), isPrimary, isBuiltin};
}

std::shared_ptr<common::BufferWriter> IndexStorageInfo::serialize() const {
    return std::make_shared<common::BufferWriter>(0 /*maximumSize*/);
}

void Index::serialize(common::Serializer& ser) const {
    indexInfo.serialize(ser);
    auto bufferedWriter = storageInfo->serialize();
    ser.write<uint64_t>(bufferedWriter->getSize());
    ser.write(bufferedWriter->getData().data.get(), bufferedWriter->getSize());
}

IndexHolder::IndexHolder(std::unique_ptr<Index> loadedIndex)
    : indexInfo{loadedIndex->getIndexInfo()}, storageInfoBuffer{nullptr}, storageInfoBufferSize{0},
      loaded{true}, index{std::move(loadedIndex)} {}

IndexHolder::IndexHolder(IndexInfo indexInfo, std::unique_ptr<uint8_t[]> storageInfoBuffer,
    uint32_t storageInfoBufferSize)
    : indexInfo{std::move(indexInfo)}, storageInfoBuffer{std::move(storageInfoBuffer)},
      storageInfoBufferSize{storageInfoBufferSize}, loaded{false}, index{nullptr} {}

void IndexHolder::serialize(common::Serializer& ser) const {
    if (loaded) {
        KU_ASSERT(index);
        index->serialize(ser);
    } else {
        indexInfo.serialize(ser);
        ser.write<uint64_t>(storageInfoBufferSize);
        if (storageInfoBufferSize > 0) {
            KU_ASSERT(storageInfoBuffer);
            ser.write(storageInfoBuffer.get(), storageInfoBufferSize);
        }
    }
}

void IndexHolder::load(main::ClientContext* context, StorageManager* storageManager) {
    if (loaded) {
        return;
    }
    KU_ASSERT(!index);
    KU_ASSERT(storageInfoBuffer);
    auto indexTypeOptional = StorageManager::Get(*context)->getIndexType(indexInfo.indexType);
    if (!indexTypeOptional.has_value()) {
        throw common::RuntimeException("No index type with name: " + indexInfo.indexType);
    }
    index = indexTypeOptional.value().get().loadFunc(context, storageManager, indexInfo,
        std::span(storageInfoBuffer.get(), storageInfoBufferSize));
    loaded = true;
}

} // namespace storage
} // namespace lbug
