#include "storage/shadow_utils.h"

#include "storage/file_handle.h"
#include "storage/shadow_file.h"
#include "transaction/transaction.h"

using namespace lbug::common;

namespace lbug {
namespace storage {

ShadowPageAndFrame ShadowUtils::createShadowVersionIfNecessaryAndPinPage(page_idx_t originalPage,
    bool skipReadingOriginalPage, FileHandle& fileHandle, ShadowFile& shadowFile) {
    KU_ASSERT(!fileHandle.isInMemoryMode());
    const auto hasShadowPage = shadowFile.hasShadowPage(fileHandle.getFileIndex(), originalPage);
    auto shadowPage = shadowFile.getOrCreateShadowPage(fileHandle.getFileIndex(), originalPage);
    uint8_t* shadowFrame = nullptr;
    try {
        if (hasShadowPage) {
            shadowFrame =
                shadowFile.getShadowingFH().pinPage(shadowPage, PageReadPolicy::READ_PAGE);
        } else {
            shadowFrame =
                shadowFile.getShadowingFH().pinPage(shadowPage, PageReadPolicy::DONT_READ_PAGE);
            if (!skipReadingOriginalPage) {
                fileHandle.optimisticReadPage(originalPage, [&](const uint8_t* frame) -> void {
                    memcpy(shadowFrame, frame, LBUG_PAGE_SIZE);
                });
            }
        }
        // The shadow page existing already does not mean that it's already dirty
        // It may have been flushed to disk to free memory and then read again
        shadowFile.getShadowingFH().setLockedPageDirty(shadowPage);
    } catch (Exception&) {
        throw;
    }
    return {originalPage, shadowPage, shadowFrame};
}

std::pair<FileHandle*, page_idx_t> ShadowUtils::getFileHandleAndPhysicalPageIdxToPin(
    FileHandle& fileHandle, page_idx_t pageIdx, const ShadowFile& shadowFile,
    transaction::TransactionType trxType) {
    if (trxType == transaction::TransactionType::CHECKPOINT &&
        shadowFile.hasShadowPage(fileHandle.getFileIndex(), pageIdx)) {
        return std::make_pair(&shadowFile.getShadowingFH(),
            shadowFile.getShadowPage(fileHandle.getFileIndex(), pageIdx));
    }
    return std::make_pair(&fileHandle, pageIdx);
}

void unpinShadowPage(page_idx_t originalPageIdx, page_idx_t shadowPageIdx,
    const ShadowFile& shadowFile) {
    KU_ASSERT(originalPageIdx != INVALID_PAGE_IDX && shadowPageIdx != INVALID_PAGE_IDX);
    KU_UNUSED(originalPageIdx);
    shadowFile.getShadowingFH().unpinPage(shadowPageIdx);
}

void ShadowUtils::updatePage(FileHandle& fileHandle, page_idx_t originalPageIdx,
    bool skipReadingOriginalPage, ShadowFile& shadowFile,
    const std::function<void(uint8_t*)>& updateOp) {
    KU_ASSERT(!fileHandle.isInMemoryMode());
    const auto shadowPageIdxAndFrame = createShadowVersionIfNecessaryAndPinPage(originalPageIdx,
        skipReadingOriginalPage, fileHandle, shadowFile);
    try {
        updateOp(shadowPageIdxAndFrame.frame);
    } catch (Exception&) {
        unpinShadowPage(shadowPageIdxAndFrame.originalPage, shadowPageIdxAndFrame.shadowPage,
            shadowFile);
        throw;
    }
    unpinShadowPage(shadowPageIdxAndFrame.originalPage, shadowPageIdxAndFrame.shadowPage,
        shadowFile);
}

void ShadowUtils::readShadowVersionOfPage(const FileHandle& fileHandle, page_idx_t originalPageIdx,
    const ShadowFile& shadowFile, const std::function<void(uint8_t*)>& readOp) {
    KU_ASSERT(!fileHandle.isInMemoryMode());
    KU_ASSERT(shadowFile.hasShadowPage(fileHandle.getFileIndex(), originalPageIdx));
    const page_idx_t shadowPageIdx =
        shadowFile.getShadowPage(fileHandle.getFileIndex(), originalPageIdx);
    const auto frame =
        shadowFile.getShadowingFH().pinPage(shadowPageIdx, PageReadPolicy::READ_PAGE);
    readOp(frame);
    unpinShadowPage(originalPageIdx, shadowPageIdx, shadowFile);
}

} // namespace storage
} // namespace lbug
