#include "processor/operator/order_by/top_k.h"

#include "binder/expression/expression_util.h"
#include "common/constants.h"
#include "common/system_config.h"
#include "common/type_utils.h"
#include "function/binary_function_executor.h"
#include "function/comparison/comparison_functions.h"
#include "processor/execution_context.h"
#include "storage/buffer_manager/memory_manager.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

std::string TopKPrintInfo::toString() const {
    std::string result = "Order By: ";
    result += binder::ExpressionUtil::toString(keys);
    result += ", Expressions: ";
    result += binder::ExpressionUtil::toString(payloads);
    result += ", Skip: ";
    result += std::to_string(skipNum);
    result += ", Limit: ";
    result += std::to_string(limitNum);
    return result;
}

TopKSortState::TopKSortState() : numTuples{0}, memoryManager{nullptr} {
    orderByLocalState = std::make_unique<SortLocalState>();
    orderBySharedState = std::make_unique<SortSharedState>();
}

void TopKSortState::init(const OrderByDataInfo& orderByDataInfo,
    storage::MemoryManager* memoryManager_) {
    this->memoryManager = memoryManager_;
    orderBySharedState->init(orderByDataInfo);
    orderByLocalState->init(orderByDataInfo, *orderBySharedState, memoryManager_);
    numTuples = 0;
}

void TopKSortState::append(const std::vector<common::ValueVector*>& keyVectors,
    const std::vector<common::ValueVector*>& payloadVectors) {
    numTuples += keyVectors[0]->state->getSelVector().getSelSize();
    orderByLocalState->append(keyVectors, payloadVectors);
}

void TopKSortState::finalize() {
    orderByLocalState->finalize(*orderBySharedState);
    auto merger = std::make_unique<KeyBlockMerger>(orderBySharedState->getPayloadTables(),
        orderBySharedState->getStrKeyColInfo(), orderBySharedState->getNumBytesPerTuple());
    auto dispatcher = std::make_unique<KeyBlockMergeTaskDispatcher>();
    dispatcher->init(memoryManager, orderBySharedState->getSortedKeyBlocks(),
        orderBySharedState->getPayloadTables(), orderBySharedState->getStrKeyColInfo(),
        orderBySharedState->getNumBytesPerTuple());
    while (!dispatcher->isDoneMerge()) {
        auto keyBlockMergeMorsel = dispatcher->getMorsel();
        merger->mergeKeyBlocks(*keyBlockMergeMorsel);
        dispatcher->doneMorsel(std::move(keyBlockMergeMorsel));
    }
}

void TopKBuffer::init(storage::MemoryManager* memoryManager_, uint64_t skipNumber,
    uint64_t limitNumber) {
    this->memoryManager = memoryManager_;
    sortState->init(*orderByDataInfo, memoryManager_);
    this->skip = skipNumber;
    this->limit = limitNumber;
    initVectors();
    initCompareFuncs();
}

void TopKBuffer::append(const std::vector<common::ValueVector*>& keyVectors,
    const std::vector<common::ValueVector*>& payloadVectors) {
    auto originalSelState = keyVectors[0]->state->getSelVectorShared();
    if (hasBoundaryValue && !compareBoundaryValue(keyVectors)) {
        keyVectors[0]->state->setSelVector(originalSelState);
        return;
    }
    sortState->append(keyVectors, payloadVectors);
    keyVectors[0]->state->setSelVector(originalSelState);
}

void TopKBuffer::reduce() {
    auto reduceThreshold = std::max(OrderByConfig::MIN_SIZE_TO_REDUCE,
        OrderByConstants::MIN_LIMIT_RATIO_TO_REDUCE * (limit + skip));
    if (sortState->getNumTuples() < reduceThreshold) {
        return;
    }
    sortState->finalize();
    auto newSortState = std::make_unique<TopKSortState>();
    newSortState->init(*orderByDataInfo, memoryManager);
    auto scanner = sortState->getScanner(0, skip + limit);
    while (true) {
        auto numTuplesScanned = scanner->scan(payloadVecsToScan);
        if (numTuplesScanned == 0) {
            setBoundaryValue();
            break;
        }
        newSortState->append(keyVecsToScan, payloadVecsToScan);
        std::swap(payloadVecsToScan, lastPayloadVecsToScan);
        std::swap(keyVecsToScan, lastKeyVecsToScan);
    }
    sortState = std::move(newSortState);
}

void TopKBuffer::merge(TopKBuffer* other) {
    other->finalize();
    if (other->sortState->getSharedState()->getSortedKeyBlocks()->empty()) {
        return;
    }
    auto scanner = other->sortState->getScanner(0, skip + limit);
    while (scanner->scan(payloadVecsToScan) > 0) {
        sortState->append(keyVecsToScan, payloadVecsToScan);
    }
    reduce();
}

void TopKBuffer::initVectors() {
    auto payloadUnflatState = std::make_shared<common::DataChunkState>();
    auto payloadFlatState = common::DataChunkState::getSingleValueDataChunkState();
    auto lastPayloadUnflatState = std::make_shared<common::DataChunkState>();
    auto lastPayloadFlatState = common::DataChunkState::getSingleValueDataChunkState();
    for (auto i = 0u; i < orderByDataInfo->payloadTypes.size(); i++) {
        auto type = &orderByDataInfo->payloadTypes[i];
        auto payloadVec = std::make_unique<common::ValueVector>(type->copy(), memoryManager);
        auto lastPayloadVec = std::make_unique<common::ValueVector>(type->copy(), memoryManager);
        if (orderByDataInfo->payloadTableSchema.getColumn(i)->isFlat()) {
            payloadVec->setState(payloadFlatState);
            lastPayloadVec->setState(lastPayloadFlatState);
        } else {
            payloadVec->setState(payloadUnflatState);
            lastPayloadVec->setState(lastPayloadUnflatState);
        }
        payloadVecsToScan.push_back(payloadVec.get());
        lastPayloadVecsToScan.push_back(lastPayloadVec.get());
        tmpVectors.push_back(std::move(payloadVec));
        tmpVectors.push_back(std::move(lastPayloadVec));
    }
    auto boundaryState = common::DataChunkState::getSingleValueDataChunkState();
    for (auto i = 0u; i < orderByDataInfo->keyTypes.size(); ++i) {
        auto type = &orderByDataInfo->keyTypes[i];
        auto boundaryVec = std::make_unique<common::ValueVector>(type->copy(), memoryManager);
        boundaryVec->setState(boundaryState);
        boundaryVecs.push_back(std::move(boundaryVec));
        auto posInPayload = orderByDataInfo->keyInPayloadPos[i];
        keyVecsToScan.push_back(payloadVecsToScan[posInPayload]);
        lastKeyVecsToScan.push_back(lastPayloadVecsToScan[posInPayload]);
    }
}

template<typename FUNC>
void TopKBuffer::getSelectComparisonFunction(common::PhysicalTypeID typeID,
    vector_select_comparison_func& selectFunc) {
    common::TypeUtils::visit(
        typeID,
        [&selectFunc]<ComparableTypes T>(
            T) { selectFunc = function::BinaryFunctionExecutor::selectComparison<T, T, FUNC>; },
        [](auto) { KU_UNREACHABLE; });
}

void TopKBuffer::initCompareFuncs() {
    compareFuncs.reserve(orderByDataInfo->isAscOrder.size());
    equalsFuncs.reserve(orderByDataInfo->isAscOrder.size());
    vector_select_comparison_func compareFunc;
    vector_select_comparison_func equalsFunc;
    for (auto i = 0u; i < orderByDataInfo->isAscOrder.size(); i++) {
        auto physicalType = orderByDataInfo->keyTypes[i].getPhysicalType();
        if (orderByDataInfo->isAscOrder[i]) {
            getSelectComparisonFunction<function::LessThan>(physicalType, compareFunc);
        } else {
            getSelectComparisonFunction<function::GreaterThan>(physicalType, compareFunc);
        }
        getSelectComparisonFunction<function::Equals>(physicalType, equalsFunc);
        compareFuncs.push_back(compareFunc);
        equalsFuncs.push_back(equalsFunc);
    }
}

void TopKBuffer::setBoundaryValue() {
    for (auto i = 0u; i < boundaryVecs.size(); i++) {
        auto boundaryVec = boundaryVecs[i].get();
        auto dstData = boundaryVec->getData() +
                       boundaryVec->getNumBytesPerValue() * boundaryVec->state->getSelVector()[0];
        auto srcVector = lastKeyVecsToScan[i];
        auto srcData =
            srcVector->getData() +
            srcVector->getNumBytesPerValue() *
                srcVector->state->getSelVector()[srcVector->state->getSelVector().getSelSize() - 1];
        boundaryVec->copyFromVectorData(dstData, srcVector, srcData);
        hasBoundaryValue = true;
    }
}

bool TopKBuffer::compareBoundaryValue(const std::vector<common::ValueVector*>& keyVectors) {
    if (keyVectors[0]->state->isFlat()) {
        return compareFlatKeys(0 /* startKeyVectorIdxToCompare */, keyVectors);
    } else {
        compareUnflatKeys(0 /* startKeyVectorIdxToCompare */, keyVectors);
        return keyVectors[0]->state->getSelVector().getSelSize() > 0;
    }
}

bool TopKBuffer::compareFlatKeys(idx_t vectorIdxToCompare, std::vector<ValueVector*> keyVectors) {
    KU_ASSERT(!keyVectors.empty());
    auto selVector = std::make_shared<common::SelectionVector>(common::DEFAULT_VECTOR_CAPACITY);
    selVector->setToFiltered();

    if (vectorIdxToCompare < keyVectors.size() - 1 &&
        equalsFuncs[vectorIdxToCompare](*keyVectors[vectorIdxToCompare],
            *boundaryVecs[vectorIdxToCompare], *selVector, nullptr /* dataPtr */)) {
        return compareFlatKeys(vectorIdxToCompare + 1, std::move(keyVectors));
    } else {
        return compareFuncs[vectorIdxToCompare](*keyVectors[vectorIdxToCompare],
            *boundaryVecs[vectorIdxToCompare], *selVector, nullptr /* dataPtr */);
    }
}

void TopKBuffer::compareUnflatKeys(idx_t vectorIdxToCompare, std::vector<ValueVector*> keyVectors) {
    auto compareSelVector =
        std::make_shared<common::SelectionVector>(common::DEFAULT_VECTOR_CAPACITY);
    compareSelVector->setToFiltered();
    compareFuncs[vectorIdxToCompare](*keyVectors[vectorIdxToCompare],
        *boundaryVecs[vectorIdxToCompare], *compareSelVector, nullptr /* dataPtr */);
    if (vectorIdxToCompare != keyVectors.size() - 1) {
        auto equalsSelVector =
            std::make_shared<common::SelectionVector>(common::DEFAULT_VECTOR_CAPACITY);
        equalsSelVector->setToFiltered();
        if (equalsFuncs[vectorIdxToCompare](*keyVectors[vectorIdxToCompare],
                *boundaryVecs[vectorIdxToCompare], *equalsSelVector, nullptr /* dataPtr */)) {
            keyVectors[vectorIdxToCompare]->state->setSelVector(equalsSelVector);
            compareUnflatKeys(vectorIdxToCompare + 1, keyVectors);
            appendSelState(compareSelVector.get(), equalsSelVector.get());
        }
    }
    keyVectors[vectorIdxToCompare]->state->setSelVector(std::move(compareSelVector));
}

void TopKBuffer::appendSelState(common::SelectionVector* selVector,
    common::SelectionVector* selVectorToAppend) {
    for (auto i = 0u; i < selVectorToAppend->getSelSize(); i++) {
        selVector->operator[](selVector->getSelSize() + i) = selVectorToAppend->operator[](i);
    }
    selVector->incrementSelSize(selVectorToAppend->getSelSize());
}

void TopKLocalState::init(const OrderByDataInfo& orderByDataInfo,
    storage::MemoryManager* memoryManager, ResultSet& /*resultSet*/, uint64_t skipNumber,
    uint64_t limitNumber) {
    buffer = std::make_unique<TopKBuffer>(orderByDataInfo);
    buffer->init(memoryManager, skipNumber, limitNumber);
}

// NOLINTNEXTLINE(readability-make-member-function-const): Semantically non-const.
void TopKLocalState::append(const std::vector<common::ValueVector*>& keyVectors,
    const std::vector<common::ValueVector*>& payloadVectors) {
    buffer->append(keyVectors, payloadVectors);
    buffer->reduce();
}

void TopK::initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) {
    localState = TopKLocalState();
    localState.init(info, storage::MemoryManager::Get(*context->clientContext), *resultSet,
        skipNumber, limitNumber);
    for (auto& dataPos : info.payloadsPos) {
        payloadVectors.push_back(resultSet->getValueVector(dataPos).get());
    }
    for (auto& dataPos : info.keysPos) {
        orderByVectors.push_back(resultSet->getValueVector(dataPos).get());
    }
}

void TopK::initGlobalStateInternal(ExecutionContext* context) {
    sharedState->init(info, storage::MemoryManager::Get(*context->clientContext), skipNumber,
        limitNumber);
}

void TopK::executeInternal(ExecutionContext* context) {
    while (children[0]->getNextTuple(context)) {
        for (auto i = 0u; i < resultSet->multiplicity; i++) {
            localState.append(orderByVectors, payloadVectors);
        }
    }
    localState.finalize();
    sharedState->mergeLocalState(&localState);
}

} // namespace processor
} // namespace lbug
