#pragma once

#include "catalog_entry.h"
#include "common/copier_config/file_scan_info.h"
#include "common/serializer/buffer_reader.h"
#include "common/serializer/deserializer.h"
#include "table_catalog_entry.h"

namespace lbug::common {
struct BufferReader;
}
namespace lbug::common {
class BufferWriter;
}
namespace lbug {
namespace catalog {

struct LBUG_API IndexToCypherInfo : ToCypherInfo {
    const main::ClientContext* context;
    const common::FileScanInfo& exportFileInfo;

    IndexToCypherInfo(const main::ClientContext* context,
        const common::FileScanInfo& exportFileInfo)
        : context{context}, exportFileInfo{exportFileInfo} {}
};

class IndexCatalogEntry;
struct LBUG_API IndexAuxInfo {
    virtual ~IndexAuxInfo() = default;
    virtual std::shared_ptr<common::BufferWriter> serialize() const;

    virtual std::unique_ptr<IndexAuxInfo> copy() = 0;

    template<typename TARGET>
    TARGET& cast() {
        return dynamic_cast<TARGET&>(*this);
    }
    template<typename TARGET>
    const TARGET& cast() const {
        return dynamic_cast<const TARGET&>(*this);
    }

    virtual std::string toCypher(const IndexCatalogEntry& indexEntry,
        const ToCypherInfo& info) const = 0;

    virtual TableCatalogEntry* getTableEntryToExport(const main::ClientContext* /*context*/) const {
        return nullptr;
    }
};

class LBUG_API IndexCatalogEntry final : public CatalogEntry {
public:
    static std::string getInternalIndexName(common::table_id_t tableID, std::string indexName) {
        return common::stringFormat("{}_{}", tableID, std::move(indexName));
    }

    IndexCatalogEntry(std::string type, common::table_id_t tableID, std::string indexName,
        std::vector<common::property_id_t> properties, std::unique_ptr<IndexAuxInfo> auxInfo)
        : CatalogEntry{CatalogEntryType::INDEX_ENTRY,
              common::stringFormat("{}_{}", tableID, indexName)},
          type{std::move(type)}, tableID{tableID}, indexName{std::move(indexName)},
          propertyIDs{std::move(properties)}, auxInfo{std::move(auxInfo)} {}

    std::string getIndexType() const { return type; }

    common::table_id_t getTableID() const { return tableID; }

    std::string getIndexName() const { return indexName; }

    std::vector<common::property_id_t> getPropertyIDs() const { return propertyIDs; }
    bool containsPropertyID(common::property_id_t propertyID) const;

    // When serializing index entries to disk, we first write the fields of the base class,
    // followed by the size (in bytes) of the auxiliary data and its content.
    void serialize(common::Serializer& serializer) const override;
    // During deserialization of index entries from disk, we first read the base class
    // (IndexCatalogEntry). The auxiliary data is stored in auxBuffer, with its size in
    // auxBufferSize. Once the extension is loaded, the corresponding indexes are reconstructed
    // using the auxBuffer.
    static std::unique_ptr<IndexCatalogEntry> deserialize(common::Deserializer& deserializer);

    std::string toCypher(const ToCypherInfo& info) const override {
        return isLoaded() ? auxInfo->toCypher(*this, info) : "";
    }

    void copyFrom(const CatalogEntry& other) override;

    std::unique_ptr<common::BufferReader> getAuxBufferReader() const;

    void setAuxInfo(std::unique_ptr<IndexAuxInfo> auxInfo_);
    const IndexAuxInfo& getAuxInfo() const { return *auxInfo; }
    IndexAuxInfo& getAuxInfoUnsafe() { return *auxInfo; }

    bool isLoaded() const { return auxBuffer == nullptr; }

    TableCatalogEntry* getTableEntryToExport(main::ClientContext* context) const {
        return isLoaded() ? auxInfo->getTableEntryToExport(context) : nullptr;
    }

    std::unique_ptr<IndexCatalogEntry> copy() const {
        return std::make_unique<IndexCatalogEntry>(type, tableID, indexName, propertyIDs,
            auxInfo->copy());
    }

protected:
    std::string type;
    common::table_id_t tableID = common::INVALID_TABLE_ID;
    std::string indexName;
    std::vector<common::property_id_t> propertyIDs;
    std::unique_ptr<uint8_t[]> auxBuffer = nullptr;
    std::unique_ptr<IndexAuxInfo> auxInfo;
    uint64_t auxBufferSize = 0;
};

} // namespace catalog
} // namespace lbug
