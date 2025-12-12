#include "storage/overflow_file.h"

#include <memory>

#include "common/type_utils.h"
#include "common/types/types.h"
#include "storage/buffer_manager/memory_manager.h"
#include "storage/file_handle.h"
#include "storage/shadow_utils.h"
#include "storage/storage_utils.h"
#include "transaction/transaction.h"

using namespace lbug::transaction;
using namespace lbug::common;

namespace lbug {
namespace storage {

std::string OverflowFileHandle::readString(TransactionType trxType, const ku_string_t& str) const {
    if (ku_string_t::isShortString(str.len)) {
        return str.getAsShortString();
    }
    PageCursor cursor;
    TypeUtils::decodeOverflowPtr(str.overflowPtr, cursor.pageIdx, cursor.elemPosInPage);
    std::string retVal;
    retVal.reserve(str.len);
    int32_t remainingLength = str.len;
    while (remainingLength > 0) {
        auto numBytesToReadInPage =
            std::min(static_cast<uint32_t>(remainingLength), END_OF_PAGE - cursor.elemPosInPage);
        auto startPosInSrc = retVal.size();
        read(trxType, cursor.pageIdx, [&](uint8_t* frame) {
            // Replace rather than append, since optimistic read may call the function multiple
            // times
            retVal.replace(startPosInSrc, numBytesToReadInPage,
                std::string_view(reinterpret_cast<const char*>(frame) + cursor.elemPosInPage,
                    numBytesToReadInPage));
            cursor.pageIdx = *reinterpret_cast<page_idx_t*>(frame + END_OF_PAGE);
        });
        remainingLength -= numBytesToReadInPage;
        // After the first page we always start reading from the beginning of the page.
        cursor.elemPosInPage = 0;
    }
    return retVal;
}

bool OverflowFileHandle::equals(TransactionType trxType, std::string_view keyToLookup,
    const ku_string_t& keyInEntry) const {
    PageCursor cursor;
    TypeUtils::decodeOverflowPtr(keyInEntry.overflowPtr, cursor.pageIdx, cursor.elemPosInPage);
    auto lengthRead = 0u;
    while (lengthRead < keyInEntry.len) {
        auto numBytesToCheckInPage = std::min(static_cast<page_idx_t>(keyInEntry.len) - lengthRead,
            END_OF_PAGE - cursor.elemPosInPage);
        bool equal = true;
        read(trxType, cursor.pageIdx, [&](auto* frame) {
            equal = memcmp(keyToLookup.data() + lengthRead, frame + cursor.elemPosInPage,
                        numBytesToCheckInPage) == 0;
            // Update the next page index
            cursor.pageIdx = *reinterpret_cast<page_idx_t*>(frame + END_OF_PAGE);
        });
        if (!equal) {
            return false;
        }
        cursor.elemPosInPage = 0;
        lengthRead += numBytesToCheckInPage;
    }
    return true;
}

uint8_t* OverflowFileHandle::addANewPage(PageAllocator* pageAllocator) {
    page_idx_t newPageIdx = overflowFile.getNewPageIdx(pageAllocator);
    if (pageWriteCache.size() > 0) {
        memcpy(pageWriteCache[nextPosToWriteTo.pageIdx].buffer->getData() + END_OF_PAGE,
            &newPageIdx, sizeof(page_idx_t));
    }
    if (startPageIdx == INVALID_PAGE_IDX) {
        startPageIdx = newPageIdx;
    }
    pageWriteCache.emplace(newPageIdx,
        CachedPage{.buffer = overflowFile.memoryManager.allocateBuffer(true /*initializeToZero*/,
                       LBUG_PAGE_SIZE),
            .newPage = true});
    nextPosToWriteTo.elemPosInPage = 0;
    nextPosToWriteTo.pageIdx = newPageIdx;
    return pageWriteCache[newPageIdx].buffer->getData();
}

void OverflowFileHandle::setStringOverflow(PageAllocator* pageAllocator, const char* srcRawString,
    uint64_t len, ku_string_t& diskDstString) {
    if (len <= ku_string_t::SHORT_STR_LENGTH) {
        return;
    }
    overflowFile.headerChanged = true;
    uint8_t* pageToWrite = nullptr;
    if (nextPosToWriteTo.pageIdx == INVALID_PAGE_IDX) {
        pageToWrite = addANewPage(pageAllocator);
    } else {
        auto cached = pageWriteCache.find(nextPosToWriteTo.pageIdx);
        if (cached != pageWriteCache.end()) {
            pageToWrite = cached->second.buffer->getData();
        } else {
            overflowFile.readFromDisk(TransactionType::CHECKPOINT, nextPosToWriteTo.pageIdx,
                [&](auto* frame) {
                    auto page = overflowFile.memoryManager.allocateBuffer(
                        false /*initializeToZero*/, LBUG_PAGE_SIZE);
                    memcpy(page->getData(), frame, LBUG_PAGE_SIZE);
                    pageToWrite = page->getData();
                    pageWriteCache.emplace(nextPosToWriteTo.pageIdx,
                        CachedPage{.buffer = std::move(page), .newPage = false});
                });
        }
    }
    int32_t remainingLength = len;
    TypeUtils::encodeOverflowPtr(diskDstString.overflowPtr, nextPosToWriteTo.pageIdx,
        nextPosToWriteTo.elemPosInPage);
    while (remainingLength > 0) {
        auto bytesWritten = len - remainingLength;
        auto numBytesToWriteInPage = std::min(static_cast<uint32_t>(remainingLength),
            END_OF_PAGE - nextPosToWriteTo.elemPosInPage);
        memcpy(pageToWrite + nextPosToWriteTo.elemPosInPage, srcRawString + bytesWritten,
            numBytesToWriteInPage);
        remainingLength -= numBytesToWriteInPage;
        nextPosToWriteTo.elemPosInPage += numBytesToWriteInPage;
        if (nextPosToWriteTo.elemPosInPage >= END_OF_PAGE) {
            pageToWrite = addANewPage(pageAllocator);
        }
    }
}

ku_string_t OverflowFileHandle::writeString(PageAllocator* pageAllocator,
    std::string_view rawString) {
    ku_string_t result;
    result.len = rawString.length();
    auto shortStrLen = ku_string_t::SHORT_STR_LENGTH;
    auto inlineLen = std::min(shortStrLen, static_cast<uint64_t>(result.len));
    memcpy(result.prefix, rawString.data(), inlineLen);
    setStringOverflow(pageAllocator, rawString.data(), rawString.length(), result);
    return result;
}

void OverflowFileHandle::checkpoint() {
    for (auto& [pageIndex, page] : pageWriteCache) {
        overflowFile.writePageToDisk(pageIndex, page.buffer->getData(), page.newPage);
    }
}

void OverflowFileHandle::reclaimStorage(PageAllocator& pageAllocator) {
    if (startPageIdx == INVALID_PAGE_IDX) {
        return;
    }

    auto pageIdx = startPageIdx;
    while (true) {
        if (pageIdx == 0 || pageIdx == INVALID_PAGE_IDX) [[unlikely]] {
            throw RuntimeException(
                "The overflow file has been corrupted, this should never happen.");
        }
        pageAllocator.freePage(pageIdx);

        if (pageIdx == nextPosToWriteTo.pageIdx) {
            break;
        }

        // reclaimStorage() is only called after the hash index is checkpointed
        // so the page write cache should always be cleared
        KU_ASSERT(!pageWriteCache.contains(pageIdx));
        overflowFile.readFromDisk(TransactionType::CHECKPOINT, pageIdx, [&pageIdx](auto* frame) {
            pageIdx = *reinterpret_cast<page_idx_t*>(frame + END_OF_PAGE);
        });
    }
}

void OverflowFileHandle::read(TransactionType trxType, page_idx_t pageIdx,
    const std::function<void(uint8_t*)>& func) const {
    auto cachedPage = pageWriteCache.find(pageIdx);
    if (cachedPage != pageWriteCache.end()) {
        return func(cachedPage->second.buffer->getData());
    }
    overflowFile.readFromDisk(trxType, pageIdx, func);
}

OverflowFile::OverflowFile(FileHandle* fileHandle, MemoryManager& memoryManager,
    ShadowFile* shadowFile, page_idx_t headerPageIdx)
    : fileHandle{fileHandle}, shadowFile{shadowFile}, memoryManager{memoryManager},
      headerChanged{false}, headerPageIdx{headerPageIdx} {
    KU_ASSERT(shadowFile);
    if (headerPageIdx != INVALID_PAGE_IDX) {
        readFromDisk(TransactionType::READ_ONLY, headerPageIdx,
            [&](auto* frame) { memcpy(&header, frame, sizeof(header)); });
    } else {
        header = StringOverflowFileHeader();
    }
}

OverflowFile::OverflowFile(MemoryManager& memoryManager)
    : fileHandle{nullptr}, shadowFile{nullptr}, memoryManager{memoryManager}, headerChanged{false},
      headerPageIdx{INVALID_PAGE_IDX} {
    // Reserve a page for the header
    this->headerPageIdx = getNewPageIdx(nullptr);
    header = StringOverflowFileHeader();
}

common::page_idx_t OverflowFile::getNewPageIdx(PageAllocator* pageAllocator) {
    // If this isn't the first call reserving the page header, then the header flag must be set
    // prior to this
    if (pageAllocator) {
        return pageAllocator->allocatePage();
    } else {
        return pageCounter.fetch_add(1);
    }
}

void OverflowFile::readFromDisk(TransactionType trxType, page_idx_t pageIdx,
    const std::function<void(uint8_t*)>& func) const {
    KU_ASSERT(shadowFile);
    auto [fileHandleToPin, pageIdxToPin] = ShadowUtils::getFileHandleAndPhysicalPageIdxToPin(
        *fileHandle, pageIdx, *shadowFile, trxType);
    fileHandleToPin->optimisticReadPage(pageIdxToPin, func);
}

void OverflowFile::writePageToDisk(page_idx_t pageIdx, uint8_t* data, bool newPage) const {
    if (newPage) {
        KU_ASSERT(fileHandle);
        KU_ASSERT(!fileHandle->isInMemoryMode());
        fileHandle->writePageToFile(data, pageIdx);
    } else {
        KU_ASSERT(shadowFile);
        ShadowUtils::updatePage(*fileHandle, pageIdx, true /* overwriting entire page*/,
            *shadowFile, [&](auto* frame) { memcpy(frame, data, LBUG_PAGE_SIZE); });
    }
}

void OverflowFile::checkpoint(PageAllocator& pageAllocator) {
    KU_ASSERT(fileHandle);
    if (headerPageIdx == INVALID_PAGE_IDX) {
        // Reserve a page for the header
        this->headerPageIdx = getNewPageIdx(&pageAllocator);
        headerChanged = true;
    }
    // TODO(bmwinger): Ideally this could be done separately and in parallel by each HashIndex
    // However fileHandle->addNewPages needs to be called beforehand,
    // but after each HashIndex::prepareCommit has written to the in-memory pages
    for (auto& handle : handles) {
        handle->checkpoint();
    }
    if (headerChanged) {
        uint8_t page[LBUG_PAGE_SIZE];
        memcpy(page, &header, sizeof(header));
        // Zero free space at the end of the header page
        std::fill(page + sizeof(header), page + LBUG_PAGE_SIZE, 0);
        writePageToDisk(headerPageIdx + HEADER_PAGE_IDX, page, false /*newPage*/);
    }
}

void OverflowFile::checkpointInMemory() {
    headerChanged = false;
}

void OverflowFile::rollbackInMemory() {
    KU_ASSERT(getFileHandle()->getNumPages() <= INVALID_PAGE_IDX);
    if (getFileHandle()->getNumPages() > headerPageIdx) {
        readFromDisk(TransactionType::READ_ONLY, headerPageIdx,
            [&](auto* frame) { memcpy(&header, frame, sizeof(header)); });
    }
    for (auto i = 0u; i < handles.size(); i++) {
        auto& handle = handles[i];
        handle->rollbackInMemory(header.entries[i].cursor);
    }
}

void OverflowFile::reclaimStorage(PageAllocator& pageAllocator) const {
    for (auto& handle : handles) {
        handle->reclaimStorage(pageAllocator);
    }
    if (headerPageIdx != INVALID_PAGE_IDX) {
        pageAllocator.freePage(headerPageIdx);
    }
}

} // namespace storage
} // namespace lbug
