#pragma once

#include <map>
#include <string>
#include <vector>

#include "common/uniq_lock.h"
#include "processor/operator/persistent/reader/copy_from_error.h"
#include "processor/warning_context.h"

namespace lbug::processor {

class BaseCSVReader;
class SerialCSVReader;

struct LinesPerBlock {
    uint64_t numLines;
    bool doneParsingBlock;
};

class SharedFileErrorHandler;

class LBUG_API SharedFileErrorHandler {
public:
    explicit SharedFileErrorHandler(common::idx_t fileIdx, std::mutex* sharedMtx,
        populate_func_t populateErrorFunc = {});

    void handleError(CopyFromFileError error);
    void throwCachedErrorsIfNeeded();

    void setHeaderNumRows(uint64_t numRows);

    void updateLineNumberInfo(const std::map<uint64_t, LinesPerBlock>& linesPerBlock,
        bool canThrowCachedError);
    uint64_t getNumCachedErrors();
    uint64_t getLineNumber(uint64_t blockIdx, uint64_t numRowsReadInBlock) const;

    void setPopulateErrorFunc(populate_func_t newPopulateErrorFunc);

private:
    // this number can be small as we only cache errors if we wish to throw them later
    static constexpr uint64_t MAX_CACHED_ERROR_COUNT = 64;

    common::UniqLock lock();
    void tryThrowFirstCachedError();

    std::string getErrorMessage(PopulatedCopyFromError populatedError) const;
    void throwError(CopyFromFileError error) const;
    bool canGetLineNumber(uint64_t blockIdx) const;
    void tryCacheError(CopyFromFileError error, const common::UniqLock&);

    std::mutex* mtx; // can be nullptr, in which case mutual exclusion is guaranteed by the caller
    common::idx_t fileIdx;
    std::vector<LinesPerBlock> linesPerBlock;
    std::vector<CopyFromFileError> cachedErrors;
    populate_func_t populateErrorFunc;

    uint64_t headerNumRows;
};

class LBUG_API LocalFileErrorHandler {
public:
    ~LocalFileErrorHandler();

    LocalFileErrorHandler(SharedFileErrorHandler* sharedErrorHandler, bool ignoreErrors,
        main::ClientContext* context, bool cacheIgnoredErrors = true);

    void handleError(CopyFromFileError error);
    void reportFinishedBlock(uint64_t blockIdx, uint64_t numRowsRead);
    void setHeaderNumRows(uint64_t numRows);
    void finalize(bool canThrowCachedError = true);
    bool getIgnoreErrorsOption() const { return ignoreErrors; }

private:
    static constexpr uint64_t LOCAL_WARNING_LIMIT = 256;
    void flushCachedErrors(bool canThrowCachedError = true);

    std::map<uint64_t, LinesPerBlock> linesPerBlock;
    std::vector<CopyFromFileError> cachedErrors;
    SharedFileErrorHandler* sharedErrorHandler;
    main::ClientContext* context;

    uint64_t maxCachedErrorCount;
    bool ignoreErrors;
    bool cacheIgnoredErrors;
};

} // namespace lbug::processor
