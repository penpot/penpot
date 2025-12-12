#include "processor/warning_context.h"

#include "common/assert.h"
#include "common/uniq_lock.h"
#include "main/client_context.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

static PopulatedCopyFromError defaultPopulateFunc(CopyFromFileError error, common::idx_t) {
    return PopulatedCopyFromError{
        .message = std::move(error.message),
        .filePath = "",
        .skippedLineOrRecord = "",
        .lineNumber = 0,
    };
}

static idx_t defaultGetFileIdxFunc(const CopyFromFileError&) {
    return 0;
}

WarningContext::WarningContext(main::ClientConfig* clientConfig)
    : clientConfig{clientConfig}, queryWarningCount{0}, numStoredWarnings{0},
      ignoreErrorsOption{false} {}

void WarningContext::appendWarningMessages(const std::vector<CopyFromFileError>& messages) {
    UniqLock lock{mtx};

    queryWarningCount += messages.size();

    for (const auto& message : messages) {
        if (numStoredWarnings >= clientConfig->warningLimit) {
            break;
        }
        unpopulatedWarnings.push_back(message);
        ++numStoredWarnings;
    }
}

const std::vector<WarningInfo>& WarningContext::getPopulatedWarnings() const {
    // if there are still unpopulated warnings when we try to get populated warnings something is
    // probably wrong
    KU_ASSERT(unpopulatedWarnings.empty());
    return populatedWarnings;
}

void WarningContext::defaultPopulateAllWarnings(uint64_t queryID) {
    populateWarnings(queryID);
}

void WarningContext::populateWarnings(uint64_t queryID, populate_func_t populateFunc,
    get_file_idx_func_t getFileIdxFunc) {
    if (!populateFunc) {
        // if no populate functor is provided we default to just copying the message over
        // and leaving the CSV fields unpopulated
        populateFunc = defaultPopulateFunc;
    }
    if (!getFileIdxFunc) {
        getFileIdxFunc = defaultGetFileIdxFunc;
    }
    for (auto& warning : unpopulatedWarnings) {
        const auto fileIdx = getFileIdxFunc(warning);
        populatedWarnings.emplace_back(populateFunc(std::move(warning), fileIdx), queryID);
    }
    unpopulatedWarnings.clear();
}

void WarningContext::clearPopulatedWarnings() {
    populatedWarnings.clear();
    numStoredWarnings = 0;
}

uint64_t WarningContext::getWarningCount(uint64_t) {
    auto ret = queryWarningCount;
    queryWarningCount = 0;
    return ret;
}

void WarningContext::setIgnoreErrorsForCurrentQuery(bool ignoreErrors) {
    ignoreErrorsOption = ignoreErrors;
}

bool WarningContext::getIgnoreErrorsOption() const {
    return ignoreErrorsOption;
}

WarningContext* WarningContext::Get(const main::ClientContext& context) {
    return context.warningContext.get();
}

} // namespace processor
} // namespace lbug
