#pragma once

#include "storage/wal/wal_record.h"

namespace lbug {
namespace main {
class ClientContext;
} // namespace main

namespace storage {
class WALReplayer {
public:
    explicit WALReplayer(main::ClientContext& clientContext);

    void replay(bool throwOnWalReplayFailure, bool enableChecksums) const;

private:
    struct WALReplayInfo {
        uint64_t offsetDeserialized = 0;
        bool isLastRecordCheckpoint = false;
    };

    void replayWALRecord(WALRecord& walRecord) const;
    void replayCreateCatalogEntryRecord(WALRecord& walRecord) const;
    void replayDropCatalogEntryRecord(const WALRecord& walRecord) const;
    void replayAlterTableEntryRecord(const WALRecord& walRecord) const;
    void replayTableInsertionRecord(const WALRecord& walRecord) const;
    void replayNodeDeletionRecord(const WALRecord& walRecord) const;
    void replayNodeUpdateRecord(const WALRecord& walRecord) const;
    void replayRelDeletionRecord(const WALRecord& walRecord) const;
    void replayRelDetachDeletionRecord(const WALRecord& walRecord) const;
    void replayRelUpdateRecord(const WALRecord& walRecord) const;
    void replayCopyTableRecord(const WALRecord& walRecord) const;
    void replayUpdateSequenceRecord(const WALRecord& walRecord) const;

    void replayNodeTableInsertRecord(const WALRecord& walRecord) const;
    void replayRelTableInsertRecord(const WALRecord& walRecord) const;

    void replayLoadExtensionRecord(const WALRecord& walRecord) const;

    // This function is used to deserialize the WAL records without actually applying them to the
    // storage.
    WALReplayInfo dryReplay(common::FileInfo& fileInfo, bool throwOnWalReplayFailure,
        bool enableChecksums) const;

    void removeWALAndShadowFiles() const;
    void removeFileIfExists(const std::string& path) const;

    std::unique_ptr<common::FileInfo> openWALFile() const;
    void syncWALFile(const common::FileInfo& fileInfo) const;
    void truncateWALFile(common::FileInfo& fileInfo, uint64_t size) const;

private:
    main::ClientContext& clientContext;
    std::string walPath;
    std::string shadowFilePath;
};

} // namespace storage
} // namespace lbug
