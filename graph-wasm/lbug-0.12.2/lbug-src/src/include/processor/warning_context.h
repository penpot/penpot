#pragma once

#include <functional>
#include <mutex>
#include <vector>

#include "common/api.h"
#include "common/types/types.h"
#include "processor/operator/persistent/reader/copy_from_error.h"

namespace lbug {
namespace common {
class ValueVector;
}
namespace storage {
class ColumnChunkData;
}
namespace main {
struct ClientConfig;
}
namespace processor {

class SerialCSVReader;

struct WarningInfo {
    uint64_t queryID;
    PopulatedCopyFromError warning;

    WarningInfo(PopulatedCopyFromError warning, uint64_t queryID)
        : queryID(queryID), warning(std::move(warning)) {}
};

using populate_func_t = std::function<PopulatedCopyFromError(CopyFromFileError, common::idx_t)>;
using get_file_idx_func_t = std::function<common::idx_t(const CopyFromFileError&)>;

class LBUG_API WarningContext {
public:
    explicit WarningContext(main::ClientConfig* clientConfig);

    void appendWarningMessages(const std::vector<CopyFromFileError>& messages);

    void populateWarnings(uint64_t queryID, populate_func_t populateFunc = {},
        get_file_idx_func_t getFileIdxFunc = {});
    void defaultPopulateAllWarnings(uint64_t queryID);

    const std::vector<WarningInfo>& getPopulatedWarnings() const;
    uint64_t getWarningCount(uint64_t queryID);
    void clearPopulatedWarnings();

    void setIgnoreErrorsForCurrentQuery(bool ignoreErrors);
    // NOTE: this function only works if the logical operator is COPY FROM
    // for other operators setIgnoreErrorsForCurrentQuery() is not called
    bool getIgnoreErrorsOption() const;

    static WarningContext* Get(const main::ClientContext& context);

private:
    std::mutex mtx;
    main::ClientConfig* clientConfig;
    std::vector<CopyFromFileError> unpopulatedWarnings;
    std::vector<WarningInfo> populatedWarnings;
    uint64_t queryWarningCount;
    uint64_t numStoredWarnings;
    bool ignoreErrorsOption;
};

} // namespace processor
} // namespace lbug
