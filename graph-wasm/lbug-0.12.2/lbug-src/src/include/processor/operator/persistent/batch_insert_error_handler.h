#pragma once

#include <optional>

#include "common/types/types.h"
#include "processor/execution_context.h"
#include "processor/operator/persistent/reader/copy_from_error.h"

namespace lbug {
namespace processor {
struct BatchInsertCachedError {
    explicit BatchInsertCachedError(std::string message,
        const std::optional<WarningSourceData>& warningData = {});
    BatchInsertCachedError() = default;

    std::string message;

    // CSV Reader data
    std::optional<WarningSourceData> warningData;
};

class BatchInsertErrorHandler {
public:
    BatchInsertErrorHandler(ExecutionContext* context, bool ignoreErrors,
        std::shared_ptr<common::row_idx_t> sharedErrorCounter = nullptr,
        std::mutex* sharedErrorCounterMtx = nullptr);

    void handleError(std::string message, const std::optional<WarningSourceData>& warningData = {});

    void handleError(BatchInsertCachedError error);

    void flushStoredErrors();
    bool getIgnoreErrors() const;

private:
    common::row_idx_t getNumErrors() const;
    void addNewVectorsIfNeeded();
    void clearErrors();

    static constexpr uint64_t LOCAL_WARNING_LIMIT = 1024;

    bool ignoreErrors;
    uint64_t warningLimit;
    ExecutionContext* context;
    uint64_t currentInsertIdx;

    std::mutex* sharedErrorCounterMtx;
    std::shared_ptr<common::row_idx_t> sharedErrorCounter;

    std::vector<BatchInsertCachedError> cachedErrors;
};
} // namespace processor
} // namespace lbug
