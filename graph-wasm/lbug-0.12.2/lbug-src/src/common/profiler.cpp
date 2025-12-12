#include "common/profiler.h"

namespace lbug {
namespace common {

TimeMetric* Profiler::registerTimeMetric(const std::string& key) {
    auto timeMetric = std::make_unique<TimeMetric>(enabled);
    auto metricPtr = timeMetric.get();
    addMetric(key, std::move(timeMetric));
    return metricPtr;
}

NumericMetric* Profiler::registerNumericMetric(const std::string& key) {
    auto numericMetric = std::make_unique<NumericMetric>(enabled);
    auto metricPtr = numericMetric.get();
    addMetric(key, std::move(numericMetric));
    return metricPtr;
}

double Profiler::sumAllTimeMetricsWithKey(const std::string& key) {
    auto sum = 0.0;
    if (!metrics.contains(key)) {
        return sum;
    }
    for (auto& metric : metrics.at(key)) {
        sum += ((TimeMetric*)metric.get())->getElapsedTimeMS();
    }
    return sum;
}

uint64_t Profiler::sumAllNumericMetricsWithKey(const std::string& key) {
    auto sum = 0ul;
    if (!metrics.contains(key)) {
        return sum;
    }
    for (auto& metric : metrics.at(key)) {
        sum += ((NumericMetric*)metric.get())->accumulatedValue;
    }
    return sum;
}

void Profiler::addMetric(const std::string& key, std::unique_ptr<Metric> metric) {
    std::lock_guard<std::mutex> lck(mtx);
    if (!metrics.contains(key)) {
        metrics.insert({key, std::vector<std::unique_ptr<Metric>>()});
    }
    metrics.at(key).push_back(std::move(metric));
}

} // namespace common
} // namespace lbug
