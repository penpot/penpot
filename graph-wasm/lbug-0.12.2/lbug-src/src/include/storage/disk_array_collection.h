#pragma once

#include <cstdint>

#include "common/types/types.h"
#include "disk_array.h"

namespace lbug {
namespace storage {

class FileHandle;

class DiskArrayCollection {
    struct HeaderPage {
        explicit HeaderPage(uint32_t numHeaders = 0)
            : nextHeaderPage{common::INVALID_PAGE_IDX}, numHeaders{numHeaders} {}
        static constexpr size_t NUM_HEADERS_PER_PAGE =
            (common::LBUG_PAGE_SIZE - sizeof(common::page_idx_t) - sizeof(uint32_t)) /
            sizeof(DiskArrayHeader);

        bool operator==(const HeaderPage&) const = default;

        std::array<DiskArrayHeader, NUM_HEADERS_PER_PAGE> headers;
        common::page_idx_t nextHeaderPage;
        uint32_t numHeaders;
    };
    static_assert(std::has_unique_object_representations_v<HeaderPage>);

public:
    DiskArrayCollection(FileHandle& fileHandle, ShadowFile& shadowFile,
        bool bypassShadowing = false);
    DiskArrayCollection(FileHandle& fileHandle, ShadowFile& shadowFile,
        common::page_idx_t firstHeaderPage, bool bypassShadowing = false);

    void checkpoint(common::page_idx_t firstHeaderPage, PageAllocator& pageAllocator);

    void checkpointInMemory() {
        for (size_t i = 0; i < headersForWriteTrx.size(); i++) {
            *headersForReadTrx[i] = *headersForWriteTrx[i];
        }
        headerPagesOnDisk = headersForReadTrx.size();
    }

    void rollbackCheckpoint() {
        for (size_t i = 0; i < headersForWriteTrx.size(); i++) {
            *headersForWriteTrx[i] = *headersForReadTrx[i];
        }
    }

    void reclaimStorage(PageAllocator& pageAllocator, common::page_idx_t firstHeaderPage) const;

    template<typename T>
    std::unique_ptr<DiskArray<T>> getDiskArray(uint32_t idx) {
        KU_ASSERT(idx < numHeaders);
        auto& readHeader = headersForReadTrx[idx / HeaderPage::NUM_HEADERS_PER_PAGE]
                               ->headers[idx % HeaderPage::NUM_HEADERS_PER_PAGE];
        auto& writeHeader = headersForWriteTrx[idx / HeaderPage::NUM_HEADERS_PER_PAGE]
                                ->headers[idx % HeaderPage::NUM_HEADERS_PER_PAGE];
        return std::make_unique<DiskArray<T>>(fileHandle, readHeader, writeHeader, &shadowFile,
            bypassShadowing);
    }

    size_t addDiskArray();

    void populateNextHeaderPage(PageAllocator& pageAllocator, common::page_idx_t indexInMemory);

private:
    FileHandle& fileHandle;
    ShadowFile& shadowFile;
    bool bypassShadowing;
    common::page_idx_t headerPagesOnDisk;
    std::vector<std::unique_ptr<HeaderPage>> headersForReadTrx;
    std::vector<std::unique_ptr<HeaderPage>> headersForWriteTrx;
    uint64_t numHeaders;
};

} // namespace storage
} // namespace lbug
