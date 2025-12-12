#pragma once

#include <optional>

#include "common/types/uuid.h"
#include "storage/page_range.h"

namespace lbug {
namespace storage {
class PageManager;

struct DatabaseHeader {
    PageRange catalogPageRange;
    PageRange metadataPageRange;

    // An ID that is unique between lbug databases
    // Used to ensure that files such as the WAL match the current database
    common::ku_uuid_t databaseID{0};

    void updateCatalogPageRange(PageManager& pageManager, PageRange newPageRange);
    void freeMetadataPageRange(PageManager& pageManager) const;
    void serialize(common::Serializer& ser) const;

    static DatabaseHeader deserialize(common::Deserializer& deSer);
    static DatabaseHeader createInitialHeader(common::RandomEngine* randomEngine);

    // If we haven't written a header to the database file yet (e.g. if the file is empty) this
    // function will return a nullopt
    static std::optional<DatabaseHeader> readDatabaseHeader(common::FileInfo& dataFileInfo);
};
} // namespace storage
} // namespace lbug
