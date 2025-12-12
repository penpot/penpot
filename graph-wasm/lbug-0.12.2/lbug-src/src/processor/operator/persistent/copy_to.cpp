#include "processor/operator/persistent/copy_to.h"

#include "processor/execution_context.h"

namespace lbug {
namespace processor {

std::string CopyToPrintInfo::toString() const {
    std::string result = "";
    result += "Export: ";
    for (auto& name : columnNames) {
        result += name + ", ";
    }
    result += "To: " + fileName;
    return result;
}

void CopyTo::initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) {
    localState.exportFuncLocalState =
        info.exportFunc.initLocalState(*context->clientContext, *info.bindData, info.isFlatVec);
    localState.inputVectors.reserve(info.inputVectorPoses.size());
    for (auto& inputVectorPos : info.inputVectorPoses) {
        localState.inputVectors.push_back(resultSet->getValueVector(inputVectorPos));
    }
}

void CopyTo::initGlobalStateInternal(lbug::processor::ExecutionContext* context) {
    sharedState->init(*context->clientContext, *info.bindData);
}

void CopyTo::finalize(ExecutionContext* /*context*/) {
    info.exportFunc.finalize(*sharedState);
}

void CopyTo::executeInternal(processor::ExecutionContext* context) {
    while (children[0]->getNextTuple(context)) {
        info.exportFunc.sink(*sharedState, *localState.exportFuncLocalState, *info.bindData,
            localState.inputVectors);
    }
    info.exportFunc.combine(*sharedState, *localState.exportFuncLocalState);
}

} // namespace processor
} // namespace lbug
