#pragma once

#include "common/types/uuid.h"
#include "storage/file_handle.h"

namespace lbug {
namespace storage {

struct ShadowPageRecord {
    common::file_idx_t originalFileIdx = common::INVALID_PAGE_IDX;
    common::page_idx_t originalPageIdx = common::INVALID_PAGE_IDX;

    void serialize(common::Serializer& serializer) const;
    static ShadowPageRecord deserialize(common::Deserializer& deserializer);
};

struct ShadowFileHeader {
    common::ku_uuid_t databaseID{0};
    common::page_idx_t numShadowPages = 0;
};
static_assert(std::is_trivially_copyable_v<ShadowFileHeader>);

class BufferManager;
// NOTE: This class is NOT thread-safe for now, as we are not checkpointing in parallel yet.
class ShadowFile {
public:
    ShadowFile(BufferManager& bm, common::VirtualFileSystem* vfs, const std::string& databasePath);

    // TODO(Guodong): Remove originalFile param.
    bool hasShadowPage(common::file_idx_t originalFile, common::page_idx_t originalPage) const {
        return shadowPagesMap.contains(originalFile) &&
               shadowPagesMap.at(originalFile).contains(originalPage);
    }
    void clearShadowPage(common::file_idx_t originalFile, common::page_idx_t originalPage);
    common::page_idx_t getShadowPage(common::file_idx_t originalFile,
        common::page_idx_t originalPage) const;
    common::page_idx_t getOrCreateShadowPage(common::file_idx_t originalFile,
        common::page_idx_t originalPage);

    FileHandle& getShadowingFH() const { return *shadowingFH; }

    void applyShadowPages(main::ClientContext& context) const;

    void flushAll(main::ClientContext& context) const;
    // Clear any buffer in the WAL writer. Also truncate the WAL file to 0 bytes.
    void clear(BufferManager& bm);
    // Reset the WAL writer to nullptr, and remove the WAL file if it exists.
    void reset();

    // Replay shadow page records from the shadow file to the original data file. This is used
    // during recovery.
    static void replayShadowPageRecords(main::ClientContext& context);

private:
    FileHandle* getOrCreateShadowingFH();

private:
    BufferManager& bm;
    std::string shadowFilePath;
    common::VirtualFileSystem* vfs;
    // This is the file handle for the shadow file. It is created lazily when the first shadow page
    // is created.
    FileHandle* shadowingFH;
    // The map caches shadow page idxes for pages in original files.
    std::unordered_map<common::file_idx_t,
        std::unordered_map<common::page_idx_t, common::page_idx_t>>
        shadowPagesMap;
    std::vector<ShadowPageRecord> shadowPageRecords;
};

} // namespace storage
} // namespace lbug
