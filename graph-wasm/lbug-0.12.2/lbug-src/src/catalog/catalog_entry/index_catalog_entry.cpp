#include "catalog/catalog_entry/index_catalog_entry.h"

#include "common/exception/runtime.h"
#include "common/serializer/buffer_writer.h"

namespace lbug {
namespace catalog {

std::shared_ptr<common::BufferWriter> IndexAuxInfo::serialize() const {
    return std::make_shared<common::BufferWriter>(0 /*maximumSize*/);
}

void IndexCatalogEntry::setAuxInfo(std::unique_ptr<IndexAuxInfo> auxInfo_) {
    auxInfo = std::move(auxInfo_);
    auxBuffer = nullptr;
    auxBufferSize = 0;
}

bool IndexCatalogEntry::containsPropertyID(common::property_id_t propertyID) const {
    for (auto id : propertyIDs) {
        if (id == propertyID) {
            return true;
        }
    }
    return false;
}

void IndexCatalogEntry::serialize(common::Serializer& serializer) const {
    CatalogEntry::serialize(serializer);
    serializer.write(type);
    serializer.write(tableID);
    serializer.write(indexName);
    serializer.serializeVector(propertyIDs);
    if (isLoaded()) {
        const auto bufferedWriter = auxInfo->serialize();
        serializer.write<uint64_t>(bufferedWriter->getSize());
        serializer.write(bufferedWriter->getData().data.get(), bufferedWriter->getSize());
    } else {
        serializer.write(auxBufferSize);
        serializer.write(auxBuffer.get(), auxBufferSize);
    }
}

std::unique_ptr<IndexCatalogEntry> IndexCatalogEntry::deserialize(
    common::Deserializer& deserializer) {
    std::string type;
    common::table_id_t tableID = common::INVALID_TABLE_ID;
    std::string indexName;
    std::vector<common::property_id_t> propertyIDs;
    deserializer.deserializeValue(type);
    deserializer.deserializeValue(tableID);
    deserializer.deserializeValue(indexName);
    deserializer.deserializeVector(propertyIDs);
    auto indexEntry = std::make_unique<IndexCatalogEntry>(type, tableID, std::move(indexName),
        std::move(propertyIDs), nullptr /* auxInfo */);
    uint64_t auxBufferSize = 0;
    deserializer.deserializeValue(auxBufferSize);
    indexEntry->auxBuffer = std::make_unique<uint8_t[]>(auxBufferSize);
    indexEntry->auxBufferSize = auxBufferSize;
    deserializer.read(indexEntry->auxBuffer.get(), auxBufferSize);
    return indexEntry;
}

void IndexCatalogEntry::copyFrom(const CatalogEntry& other) {
    CatalogEntry::copyFrom(other);
    auto& otherTable = other.constCast<IndexCatalogEntry>();
    tableID = otherTable.tableID;
    indexName = otherTable.indexName;
    if (auxInfo) {
        auxInfo = otherTable.auxInfo->copy();
    }
}
std::unique_ptr<common::BufferReader> IndexCatalogEntry::getAuxBufferReader() const {
    // LCOV_EXCL_START
    if (!auxBuffer) {
        throw common::RuntimeException(
            common::stringFormat("Auxiliary buffer for index \"{}\" is not set.", indexName));
    }
    // LCOV_EXCL_STOP
    return std::make_unique<common::BufferReader>(auxBuffer.get(), auxBufferSize);
}

} // namespace catalog
} // namespace lbug
