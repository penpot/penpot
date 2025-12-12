#include "processor/operator/persistent/reader/file_error_handler.h"

#include <algorithm>

#include "common/assert.h"
#include "common/exception/copy.h"
#include "common/string_format.h"
#include "main/client_context.h"

namespace lbug {
using namespace common;
namespace processor {

void LineContext::setNewLine(uint64_t start) {
    startByteOffset = start;
    isCompleteLine = false;
}

void LineContext::setEndOfLine(uint64_t end) {
    endByteOffset = end;
    isCompleteLine = true;
}

SharedFileErrorHandler::SharedFileErrorHandler(common::idx_t fileIdx, std::mutex* sharedMtx,
    populate_func_t populateErrorFunc)
    : mtx(sharedMtx), fileIdx(fileIdx), populateErrorFunc(std::move(populateErrorFunc)),
      headerNumRows(0) {}

uint64_t SharedFileErrorHandler::getNumCachedErrors() {
    auto lockGuard = lock();
    return cachedErrors.size();
}

void SharedFileErrorHandler::tryCacheError(CopyFromFileError error, const common::UniqLock&) {
    if (cachedErrors.size() < MAX_CACHED_ERROR_COUNT) {
        cachedErrors.push_back(std::move(error));
    }
}

void SharedFileErrorHandler::handleError(CopyFromFileError error) {
    auto lockGuard = lock();
    if (error.mustThrow) {
        throwError(error);
    }

    const auto blockIdx = error.warningData.getBlockIdx();
    if (blockIdx >= linesPerBlock.size()) {
        linesPerBlock.resize(blockIdx + 1);
    }

    // throwing of the error is not done when in the middle of parsing blocks
    // so we cache the error to be thrown later
    tryCacheError(std::move(error), lockGuard);
}

void SharedFileErrorHandler::throwCachedErrorsIfNeeded() {
    auto lockGuard = lock();
    tryThrowFirstCachedError();
}

void SharedFileErrorHandler::tryThrowFirstCachedError() {
    if (cachedErrors.empty()) {
        return;
    }

    // we sort the cached errors to report the one with the earliest line number
    std::sort(cachedErrors.begin(), cachedErrors.end());

    const auto error = *cachedErrors.cbegin();
    KU_ASSERT(!error.mustThrow);

    const bool errorIsThrowable = canGetLineNumber(error.warningData.getBlockIdx());
    if (errorIsThrowable) {
        throwError(error);
    }
}

namespace {
std::string getFilePathMessage(std::string_view filePath) {
    static constexpr std::string_view invalidFilePath = "";
    return filePath == invalidFilePath ? std::string{} :
                                         common::stringFormat(" in file {}", filePath);
}

std::string getLineNumberMessage(uint64_t lineNumber) {
    static constexpr uint64_t invalidLineNumber = 0;
    return lineNumber == invalidLineNumber ? std::string{} :
                                             common::stringFormat(" on line {}", lineNumber);
}

std::string getSkippedLineMessage(std::string_view skippedLineOrRecord) {
    static constexpr std::string_view emptySkippedLine = "";
    return skippedLineOrRecord == emptySkippedLine ?
               std::string{} :
               common::stringFormat(" Line/record containing the error: '{}'", skippedLineOrRecord);
}
} // namespace

std::string SharedFileErrorHandler::getErrorMessage(PopulatedCopyFromError populatedError) const {
    return common::stringFormat("Error{}{}: {}{}", getFilePathMessage(populatedError.filePath),
        getLineNumberMessage(populatedError.lineNumber), populatedError.message,
        getSkippedLineMessage(populatedError.skippedLineOrRecord));
}

void SharedFileErrorHandler::throwError(CopyFromFileError error) const {
    KU_ASSERT(populateErrorFunc);
    throw CopyException(getErrorMessage(populateErrorFunc(std::move(error), fileIdx)));
}

common::UniqLock SharedFileErrorHandler::lock() {
    if (mtx) {
        return common::UniqLock{*mtx};
    }
    return common::UniqLock{};
}

bool SharedFileErrorHandler::canGetLineNumber(uint64_t blockIdx) const {
    if (blockIdx > linesPerBlock.size()) {
        return false;
    }
    for (uint64_t i = 0; i < blockIdx; ++i) {
        //  the line count for a block is empty if it hasn't finished being parsed
        if (!linesPerBlock[i].doneParsingBlock) {
            return false;
        }
    }
    return true;
}

void SharedFileErrorHandler::setPopulateErrorFunc(populate_func_t newPopulateErrorFunc) {
    populateErrorFunc = newPopulateErrorFunc;
}

uint64_t SharedFileErrorHandler::getLineNumber(uint64_t blockIdx,
    uint64_t numRowsReadInBlock) const {
    // 1-indexed
    uint64_t res = numRowsReadInBlock + headerNumRows + 1;
    for (uint64_t i = 0; i < blockIdx; ++i) {
        KU_ASSERT(i < linesPerBlock.size());
        res += linesPerBlock[i].numLines;
    }
    return res;
}

void SharedFileErrorHandler::setHeaderNumRows(uint64_t numRows) {
    if (numRows == headerNumRows) {
        return;
    }
    auto lockGuard = lock();
    headerNumRows = numRows;
}

void SharedFileErrorHandler::updateLineNumberInfo(
    const std::map<uint64_t, LinesPerBlock>& newLinesPerBlock, bool canThrowCachedError) {
    const auto lockGuard = lock();

    if (!newLinesPerBlock.empty()) {
        const auto maxNewBlockIdx = newLinesPerBlock.rbegin()->first;
        if (maxNewBlockIdx >= linesPerBlock.size()) {
            linesPerBlock.resize(maxNewBlockIdx + 1);
        }

        for (const auto& [blockIdx, linesInBlock] : newLinesPerBlock) {
            auto& currentBlock = linesPerBlock[blockIdx];
            currentBlock.numLines += linesInBlock.numLines;
            currentBlock.doneParsingBlock =
                currentBlock.doneParsingBlock || linesInBlock.doneParsingBlock;
        }
    }

    if (canThrowCachedError) {
        tryThrowFirstCachedError();
    }
}

LocalFileErrorHandler::LocalFileErrorHandler(SharedFileErrorHandler* sharedErrorHandler,
    bool ignoreErrors, main::ClientContext* context, bool cacheErrors)
    : sharedErrorHandler(sharedErrorHandler), context(context),
      maxCachedErrorCount(
          std::min(this->context->getClientConfig()->warningLimit, LOCAL_WARNING_LIMIT)),
      ignoreErrors(ignoreErrors), cacheIgnoredErrors(cacheErrors) {}

void LocalFileErrorHandler::handleError(CopyFromFileError error) {
    if (error.mustThrow || !ignoreErrors) {
        // we delegate throwing to the shared error handler
        sharedErrorHandler->handleError(std::move(error));
        return;
    }

    KU_ASSERT(cachedErrors.size() <= maxCachedErrorCount);
    if (cachedErrors.size() == maxCachedErrorCount) {
        flushCachedErrors();
    }

    if (cacheIgnoredErrors) {
        cachedErrors.push_back(std::move(error));
    }
}

void LocalFileErrorHandler::reportFinishedBlock(uint64_t blockIdx, uint64_t numRowsRead) {
    linesPerBlock[blockIdx].numLines += numRowsRead;
    linesPerBlock[blockIdx].doneParsingBlock = true;
    if (linesPerBlock.size() >= maxCachedErrorCount) {
        flushCachedErrors();
    }
}

void LocalFileErrorHandler::setHeaderNumRows(uint64_t numRows) {
    sharedErrorHandler->setHeaderNumRows(numRows);
}

LocalFileErrorHandler::~LocalFileErrorHandler() {
    // we don't want to throw in the destructor
    // so we leave throwing for later in the parsing stage or during finalize
    flushCachedErrors(false);
}

void LocalFileErrorHandler::finalize(bool canThrowCachedError) {
    flushCachedErrors(canThrowCachedError);
}

void LocalFileErrorHandler::flushCachedErrors(bool canThrowCachedError) {
    if (!linesPerBlock.empty()) {
        // clear linesPerBlock first so that it is empty even if updateLineNumberInfo() throws
        decltype(linesPerBlock) oldLinesPerBlock;
        oldLinesPerBlock.swap(linesPerBlock);
        sharedErrorHandler->updateLineNumberInfo(oldLinesPerBlock, canThrowCachedError);
    }

    if (!cachedErrors.empty()) {
        WarningContext::Get(*context)->appendWarningMessages(cachedErrors);
        cachedErrors.clear();
    }
}

} // namespace processor
} // namespace lbug
