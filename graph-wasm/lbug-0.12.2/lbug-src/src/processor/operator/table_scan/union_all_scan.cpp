#include "processor/operator/table_scan/union_all_scan.h"

#include <mutex>

#include "binder/expression/expression_util.h"
#include "common/metric.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

std::string UnionAllScanPrintInfo::toString() const {
    std::string result = "Expressions: ";
    result += binder::ExpressionUtil::toString(expressions);
    return result;
}

std::unique_ptr<UnionAllScanMorsel> UnionAllScanSharedState::getMorsel() {
    std::unique_lock lck{mtx};
    if (tableIdx == tables.size()) { // No more to scan.
        return std::make_unique<UnionAllScanMorsel>(nullptr /* table */, 0, 0);
    }
    auto morsel = getMorselNoLock(tables[tableIdx].get());
    // Fetch next table if current table has nothing to scan.
    while (morsel->numTuples == 0) {
        tableIdx++;
        nextTupleIdxToScan = 0;
        if (tableIdx == tables.size()) { // No more to scan.
            return std::make_unique<UnionAllScanMorsel>(nullptr /* table */, 0, 0);
        }
        morsel = getMorselNoLock(tables[tableIdx].get());
    }
    return morsel;
}

std::unique_ptr<UnionAllScanMorsel> UnionAllScanSharedState::getMorselNoLock(
    FactorizedTable* table) {
    auto numTuplesToScan = std::min(maxMorselSize, table->getNumTuples() - nextTupleIdxToScan);
    auto morsel = std::make_unique<UnionAllScanMorsel>(table, nextTupleIdxToScan, numTuplesToScan);
    nextTupleIdxToScan += numTuplesToScan;
    return morsel;
}

void UnionAllScan::initLocalStateInternal(ResultSet* /*resultSet_*/,
    ExecutionContext* /*context*/) {
    for (auto& dataPos : info.outputPositions) {
        vectors.push_back(resultSet->getValueVector(dataPos).get());
    }
}

bool UnionAllScan::getNextTuplesInternal(ExecutionContext* /*context*/) {
    auto morsel = sharedState->getMorsel();
    if (morsel->numTuples == 0) {
        return false;
    }
    morsel->table->scan(vectors, morsel->startTupleIdx, morsel->numTuples, info.columnIndices);
    metrics->numOutputTuple.increase(morsel->numTuples);
    return true;
}

} // namespace processor
} // namespace lbug
