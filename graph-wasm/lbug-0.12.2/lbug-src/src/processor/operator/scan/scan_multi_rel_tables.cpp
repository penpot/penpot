#include "processor/operator/scan/scan_multi_rel_tables.h"

#include "processor/execution_context.h"
#include "storage/local_storage/local_storage.h"

using namespace lbug::common;
using namespace lbug::storage;
using namespace lbug::transaction;

namespace lbug {
namespace processor {

bool DirectionInfo::needFlip(RelDataDirection relDataDirection) const {
    if (extendFromSource && relDataDirection == RelDataDirection::BWD) {
        return true;
    }
    if (!extendFromSource && relDataDirection == RelDataDirection::FWD) {
        return true;
    }
    return false;
}

bool RelTableCollectionScanner::scan(main::ClientContext* context, RelTableScanState& scanState,
    const std::vector<ValueVector*>& outVectors) {
    auto transaction = Transaction::Get(*context);
    while (true) {
        auto& relInfo = relInfos[currentTableIdx];
        if (relInfo.table->scan(transaction, scanState)) {
            auto& selVector = scanState.outState->getSelVector();
            if (directionVector != nullptr) {
                for (auto i = 0u; i < selVector.getSelSize(); ++i) {
                    directionVector->setValue<bool>(selVector[i], directionValues[currentTableIdx]);
                }
            }
            if (selVector.getSelSize() > 0) {
                relInfo.castColumns();
                return true;
            }
        } else {
            currentTableIdx = nextTableIdx;
            if (currentTableIdx == relInfos.size()) {
                return false;
            }
            auto& currentInfo = relInfos[currentTableIdx];
            currentInfo.initScanState(scanState, outVectors, context);
            currentInfo.table->initScanState(transaction, scanState, currentTableIdx == 0);
            nextTableIdx++;
        }
    }
}

void ScanMultiRelTable::initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) {
    ScanTable::initLocalStateInternal(resultSet, context);
    auto clientContext = context->clientContext;
    boundNodeIDVector = resultSet->getValueVector(opInfo.nodeIDPos).get();
    auto nbrNodeIDVector = outVectors[0];
    scanState = std::make_unique<RelTableScanState>(*MemoryManager::Get(*clientContext),
        boundNodeIDVector, outVectors, nbrNodeIDVector->state);
    for (auto& [_, scanner] : scanners) {
        for (auto& relInfo : scanner.relInfos) {
            if (directionInfo.directionPos.isValid()) {
                scanner.directionVector =
                    resultSet->getValueVector(directionInfo.directionPos).get();
                scanner.directionValues.push_back(directionInfo.needFlip(relInfo.direction));
            }
        }
    }
    currentScanner = nullptr;
}

bool ScanMultiRelTable::getNextTuplesInternal(ExecutionContext* context) {
    while (true) {
        if (currentScanner != nullptr &&
            currentScanner->scan(context->clientContext, *scanState, outVectors)) {
            metrics->numOutputTuple.increase(scanState->outState->getSelVector().getSelSize());
            return true;
        }
        if (!children[0]->getNextTuple(context)) {
            resetState();
            return false;
        }
        const auto currentIdx = boundNodeIDVector->state->getSelVector()[0];
        if (boundNodeIDVector->isNull(currentIdx)) {
            currentScanner = nullptr;
            continue;
        }
        auto nodeID = boundNodeIDVector->getValue<nodeID_t>(currentIdx);
        initCurrentScanner(nodeID);
    }
}

void ScanMultiRelTable::resetState() {
    currentScanner = nullptr;
    for (auto& [_, scanner] : scanners) {
        scanner.resetState();
    }
}

void ScanMultiRelTable::initCurrentScanner(const nodeID_t& nodeID) {
    if (scanners.contains(nodeID.tableID)) {
        currentScanner = &scanners.at(nodeID.tableID);
        currentScanner->resetState();
    } else {
        currentScanner = nullptr;
    }
}

} // namespace processor
} // namespace lbug
