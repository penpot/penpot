#pragma once

#include "storage/database_header.h"
#include "storage/page_range.h"

namespace lbug {
namespace transaction {
class Transaction;
}
namespace catalog {
class Catalog;
}
namespace common {
class VirtualFileSystem;
} // namespace common
namespace testing {
struct FSMLeakChecker;
}
namespace main {
class AttachedLbugDatabase;
} // namespace main

namespace storage {
class StorageManager;

class Checkpointer {
    friend class main::AttachedLbugDatabase;
    friend struct testing::FSMLeakChecker;

public:
    explicit Checkpointer(main::ClientContext& clientContext);
    virtual ~Checkpointer();

    void writeCheckpoint();
    void rollback();

    void readCheckpoint();

    static bool canAutoCheckpoint(const main::ClientContext& clientContext,
        const transaction::Transaction& transaction);

protected:
    virtual bool checkpointStorage();
    virtual void serializeCatalogAndMetadata(DatabaseHeader& databaseHeader,
        bool hasStorageChanges);
    virtual void writeDatabaseHeader(const DatabaseHeader& header);
    virtual void logCheckpointAndApplyShadowPages();

private:
    static void readCheckpoint(main::ClientContext* context, catalog::Catalog* catalog,
        StorageManager* storageManager);

    PageRange serializeCatalog(const catalog::Catalog& catalog, StorageManager& storageManager);
    PageRange serializeMetadata(const catalog::Catalog& catalog, StorageManager& storageManager);

protected:
    main::ClientContext& clientContext;
    bool isInMemory;
};

} // namespace storage
} // namespace lbug
