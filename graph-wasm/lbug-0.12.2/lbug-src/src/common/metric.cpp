#include "common/metric.h"

namespace lbug {
namespace common {

TimeMetric::TimeMetric(bool enable) : Metric(enable) {
    accumulatedTime = 0;
    isStarted = false;
    timer = Timer();
}

void TimeMetric::start() {
    if (!enabled) {
        return;
    }
    isStarted = true;
    timer.start();
}

void TimeMetric::stop() {
    if (!enabled) {
        return;
    }
    if (!isStarted) {
        throw Exception("Timer metric has not started.");
    }
    timer.stop();
    accumulatedTime += timer.getDuration();
    isStarted = false;
}

double TimeMetric::getElapsedTimeMS() const {
    return accumulatedTime / 1000;
}

NumericMetric::NumericMetric(bool enable) : Metric(enable) {
    accumulatedValue = 0u;
}

void NumericMetric::increase(uint64_t value) {
    if (!enabled) {
        return;
    }
    accumulatedValue += value;
}

void NumericMetric::incrementByOne() {
    if (!enabled) {
        return;
    }
    accumulatedValue++;
}

} // namespace common
} // namespace lbug
