#pragma once

#include <stdint.h>

#include <atomic>

namespace lbug {
namespace common {

/**
 * @brief Interface for displaying progress of a pipeline and a query.
 */
class ProgressBarDisplay {
public:
    ProgressBarDisplay() : pipelineProgress{0}, numPipelines{0}, numPipelinesFinished{0} {};

    virtual ~ProgressBarDisplay() = default;

    // Update the progress of the pipeline and the number of finished pipelines. queryID is used to
    // identify the query when we track progress of multiple queries asynchronously
    // This function should work even if called concurrently by multiple threads
    virtual void updateProgress(uint64_t queryID, double newPipelineProgress,
        uint32_t newNumPipelinesFinished) = 0;

    // Finish the progress display. queryID is used to identify the query when we track progress of
    // multiple queries asynchronously
    virtual void finishProgress(uint64_t queryID) = 0;

    void setNumPipelines(uint32_t newNumPipelines) { numPipelines = newNumPipelines; };

protected:
    std::atomic<double> pipelineProgress;
    uint32_t numPipelines;
    std::atomic<uint32_t> numPipelinesFinished;
};

} // namespace common
} // namespace lbug
