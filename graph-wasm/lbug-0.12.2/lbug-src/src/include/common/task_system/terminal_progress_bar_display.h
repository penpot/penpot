#pragma once

#include <iostream>

#include "progress_bar_display.h"

namespace lbug {
namespace common {

/**
 * @brief A class that displays a progress bar in the terminal.
 */
class TerminalProgressBarDisplay final : public ProgressBarDisplay {
public:
    void updateProgress(uint64_t queryID, double newPipelineProgress,
        uint32_t newNumPipelinesFinished) override;

    void finishProgress(uint64_t queryID) override;

private:
    void setGreenFont() const { std::cerr << "\033[1;32m"; }

    void setDefaultFont() const { std::cerr << "\033[0m"; }

    void printProgressBar();

private:
    bool printing = false;
    std::atomic<bool> currentlyPrintingProgress;
};

} // namespace common
} // namespace lbug
