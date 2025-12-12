#include "processor/operator/persistent/batch_insert_error_handler.h"

#include "common/exception/copy.h"
#include "common/uniq_lock.h"
#include "main/client_context.h"
#include "processor/execution_context.h"
#include "processor/warning_context.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

BatchInsertCachedError::BatchInsertCachedError(std::string message,
    const std::optional<WarningSourceData>& warningData)
    : message(std::move(message)), warningData(warningData) {}

BatchInsertErrorHandler::BatchInsertErrorHandler(ExecutionContext* context, bool ignoreErrors,
    std::shared_ptr<common::row_idx_t> sharedErrorCounter, std::mutex* sharedErrorCounterMtx)
    : ignoreErrors(ignoreErrors),
      warningLimit(
          std::min(context->clientContext->getClientConfig()->warningLimit, LOCAL_WARNING_LIMIT)),
      context(context), currentInsertIdx(0), sharedErrorCounterMtx(sharedErrorCounterMtx),
      sharedErrorCounter(std::move(sharedErrorCounter)) {}

void BatchInsertErrorHandler::addNewVectorsIfNeeded() {
    KU_ASSERT(currentInsertIdx <= cachedErrors.size());
    if (currentInsertIdx == cachedErrors.size()) {
        cachedErrors.emplace_back();
    }
}

bool BatchInsertErrorHandler::getIgnoreErrors() const {
    return ignoreErrors;
}

void BatchInsertErrorHandler::handleError(std::string message,
    const std::optional<WarningSourceData>& warningData) {
    handleError(BatchInsertCachedError{std::move(message), warningData});
}

void BatchInsertErrorHandler::handleError(BatchInsertCachedError error) {
    if (!ignoreErrors) {
        throw common::CopyException(error.message);
    }

    if (getNumErrors() >= warningLimit) {
        flushStoredErrors();
    }

    addNewVectorsIfNeeded();
    cachedErrors[currentInsertIdx] = std::move(error);
    ++currentInsertIdx;
}

void BatchInsertErrorHandler::flushStoredErrors() {
    std::vector<CopyFromFileError> unpopulatedErrors;

    for (row_idx_t i = 0; i < getNumErrors(); ++i) {
        auto& error = cachedErrors[i];
        CopyFromFileError warningToAdd{std::move(error.message), {}, false};
        if (error.warningData.has_value()) {
            warningToAdd.completedLine = true;
            warningToAdd.warningData = error.warningData.value();
        }
        unpopulatedErrors.push_back(warningToAdd);
    }

    if (!unpopulatedErrors.empty()) {
        KU_ASSERT(ignoreErrors);
        WarningContext::Get(*context->clientContext)->appendWarningMessages(unpopulatedErrors);
    }

    if (!unpopulatedErrors.empty() && sharedErrorCounter != nullptr) {
        KU_ASSERT(sharedErrorCounterMtx);
        common::UniqLock lockGuard{*sharedErrorCounterMtx};
        *sharedErrorCounter += unpopulatedErrors.size();
    }

    clearErrors();
}

void BatchInsertErrorHandler::clearErrors() {
    currentInsertIdx = 0;
}

row_idx_t BatchInsertErrorHandler::getNumErrors() const {
    return currentInsertIdx;
}

} // namespace processor
} // namespace lbug
