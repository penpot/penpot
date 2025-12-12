#include "common/task_system/progress_bar.h"

#include "common/task_system/terminal_progress_bar_display.h"
#include "main/client_context.h"

namespace lbug {
namespace common {

ProgressBar::ProgressBar(bool enableProgressBar) {
    display = DefaultProgressBarDisplay();
    numPipelines = 0;
    numPipelinesFinished = 0;
    trackProgress = enableProgressBar;
}

std::shared_ptr<ProgressBarDisplay> ProgressBar::DefaultProgressBarDisplay() {
    return std::make_shared<TerminalProgressBarDisplay>();
}

void ProgressBar::setDisplay(std::shared_ptr<ProgressBarDisplay> progressBarDipslay) {
    display = progressBarDipslay;
}

void ProgressBar::startProgress(uint64_t queryID) {
    if (!trackProgress) {
        return;
    }
    std::lock_guard<std::mutex> lock(progressBarLock);
    updateDisplay(queryID, 0.0);
}

void ProgressBar::endProgress(uint64_t queryID) {
    std::lock_guard<std::mutex> lock(progressBarLock);
    resetProgressBar(queryID);
}

void ProgressBar::addPipeline() {
    if (!trackProgress) {
        return;
    }
    numPipelines++;
    display->setNumPipelines(numPipelines);
}

void ProgressBar::finishPipeline(uint64_t queryID) {
    if (!trackProgress) {
        return;
    }
    numPipelinesFinished++;
    updateProgress(queryID, 0.0);
}

void ProgressBar::updateProgress(uint64_t queryID, double curPipelineProgress) {
    if (!trackProgress) {
        return;
    }
    updateDisplay(queryID, curPipelineProgress);
}

void ProgressBar::resetProgressBar(uint64_t queryID) {
    numPipelines = 0;
    numPipelinesFinished = 0;
    display->finishProgress(queryID);
}

void ProgressBar::updateDisplay(uint64_t queryID, double curPipelineProgress) {
    display->updateProgress(queryID, curPipelineProgress, numPipelinesFinished);
}

void ProgressBar::toggleProgressBarPrinting(bool enable) {
    trackProgress = enable;
}

ProgressBar* ProgressBar::Get(const main::ClientContext& context) {
    return context.progressBar.get();
}

} // namespace common
} // namespace lbug
