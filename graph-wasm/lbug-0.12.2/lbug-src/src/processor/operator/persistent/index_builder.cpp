#include "processor/operator/persistent/index_builder.h"

#include <thread>

#include "common/assert.h"
#include "common/cast.h"
#include "common/exception/copy.h"
#include "common/exception/message.h"
#include "common/type_utils.h"
#include "common/types/ku_string.h"
#include "storage/index/hash_index_utils.h"
#include "storage/table/node_table.h"
#include "storage/table/string_chunk_data.h"

namespace lbug {
namespace processor {

using namespace lbug::common;
using namespace lbug::storage;

template<typename T>
bool IndexBufferWithWarningData<T>::full() const {
    return indexBuffer.full() || (warningDataBuffer != nullptr && warningDataBuffer->full());
}

template<typename T>
void IndexBufferWithWarningData<T>::append(T key, common::offset_t value,
    OptionalWarningSourceData&& warningData) {
    indexBuffer.push_back(std::make_pair(key, value));
    if (warningData.has_value()) {
        if (warningDataBuffer == nullptr) {
            warningDataBuffer = std::make_unique<OptionalWarningDataBuffer::element_type>();
        }
        warningDataBuffer->push_back(warningData.value());
    }
}

IndexBuilderGlobalQueues::IndexBuilderGlobalQueues(transaction::Transaction* transaction,
    NodeTable* nodeTable)
    : nodeTable(nodeTable), transaction{transaction} {
    TypeUtils::visit(
        pkTypeID(), [&](ku_string_t) { queues.emplace<Queue<std::string>>(); },
        [&]<HashablePrimitive T>(T) { queues.emplace<Queue<T>>(); }, [](auto) { KU_UNREACHABLE; });
}

PhysicalTypeID IndexBuilderGlobalQueues::pkTypeID() const {
    return nodeTable->getPKIndex()->keyTypeID();
}

void IndexBuilderGlobalQueues::consume(NodeBatchInsertErrorHandler& errorHandler) {
    for (auto index = 0u; index < NUM_HASH_INDEXES; index++) {
        maybeConsumeIndex(index, errorHandler);
    }
}

void IndexBuilderGlobalQueues::maybeConsumeIndex(size_t index,
    NodeBatchInsertErrorHandler& errorHandler) {
    auto& pkIndex = nodeTable->getPKIndex()->cast<PrimaryKeyIndex>();
    if (!pkIndex.tryLockTypedIndex(index)) {
        return;
    }

    std::visit(
        [&](auto&& queues) {
            using T = std::decay_t<decltype(queues.type)>;
            auto lck = pkIndex.adoptLockOfTypedIndex(index);
            IndexBufferWithWarningData<T> bufferWithWarningData;
            while (queues.array[index].pop(bufferWithWarningData)) {
                auto& buffer = bufferWithWarningData.indexBuffer;
                auto& warningDataBuffer = bufferWithWarningData.warningDataBuffer;
                uint64_t insertBufferOffset = 0;
                while (insertBufferOffset < buffer.size()) {
                    auto numValuesInserted = pkIndex.appendWithIndexPosNoLock(transaction, buffer,
                        insertBufferOffset, index,
                        [&](offset_t offset) { return nodeTable->isVisible(transaction, offset); });
                    if (numValuesInserted < buffer.size() - insertBufferOffset) {
                        const auto& erroneousEntry = buffer[insertBufferOffset + numValuesInserted];
                        OptionalWarningSourceData erroneousEntryWarningData;
                        if (warningDataBuffer != nullptr) {
                            erroneousEntryWarningData =
                                (*warningDataBuffer)[insertBufferOffset + numValuesInserted];
                        }
                        errorHandler.handleError(
                            IndexBuilderError<T>{.message = ExceptionMessage::duplicatePKException(
                                                     TypeUtils::toString(erroneousEntry.first)),
                                .key = erroneousEntry.first,
                                .nodeID =
                                    nodeID_t{
                                        erroneousEntry.second,
                                        nodeTable->getTableID(),
                                    },
                                .warningData = erroneousEntryWarningData});
                        insertBufferOffset += 1; // skip the erroneous index then continue
                    }
                    insertBufferOffset += numValuesInserted;
                }
            }
            return;
        },
        std::move(queues));
}

IndexBuilderLocalBuffers::IndexBuilderLocalBuffers(IndexBuilderGlobalQueues& globalQueues)
    : globalQueues(&globalQueues) {
    TypeUtils::visit(
        globalQueues.pkTypeID(),
        [&](ku_string_t) { buffers = std::make_unique<Buffers<std::string>>(); },
        [&]<HashablePrimitive T>(T) { buffers = std::make_unique<Buffers<T>>(); },
        [](auto) { KU_UNREACHABLE; });
}

void IndexBuilderLocalBuffers::flush(NodeBatchInsertErrorHandler& errorHandler) {
    std::visit(
        [&](auto&& buffers) {
            for (auto i = 0u; i < buffers->size(); i++) {
                globalQueues->insert(i, std::move((*buffers)[i]), errorHandler);
            }
        },
        buffers);
}

IndexBuilder::IndexBuilder(std::shared_ptr<IndexBuilderSharedState> sharedState)
    : sharedState(std::move(sharedState)), localBuffers(this->sharedState->globalQueues) {}

void IndexBuilderSharedState::quitProducer() {
    if (producers.fetch_sub(1, std::memory_order_relaxed) == 1) {
        done.store(true, std::memory_order_relaxed);
    }
}

static OptionalWarningSourceData getWarningDataFromChunks(
    const std::vector<storage::ColumnChunkData*>& chunks, common::idx_t posInChunk) {
    OptionalWarningSourceData ret;
    if (!chunks.empty()) {
        ret = WarningSourceData::constructFromData(chunks, posInChunk);
    }
    return ret;
}

void IndexBuilder::insert(const ColumnChunkData& chunk,
    const std::vector<ColumnChunkData*>& warningData, offset_t nodeOffset, offset_t numNodes,
    NodeBatchInsertErrorHandler& errorHandler) {
    TypeUtils::visit(
        chunk.getDataType().getPhysicalType(),
        [&]<HashablePrimitive T>(T) {
            for (auto i = 0u; i < numNodes; i++) {
                if (checkNonNullConstraint(chunk, warningData, nodeOffset, i, errorHandler)) {
                    auto value = chunk.getValue<T>(i);
                    localBuffers.insert(value, nodeOffset + i,
                        getWarningDataFromChunks(warningData, i), errorHandler);
                }
            }
        },
        [&](ku_string_t) {
            auto& stringColumnChunk = ku_dynamic_cast<const StringChunkData&>(chunk);
            for (auto i = 0u; i < numNodes; i++) {
                if (checkNonNullConstraint(chunk, warningData, nodeOffset, i, errorHandler)) {
                    auto value = stringColumnChunk.getValue<std::string>(i);
                    localBuffers.insert(std::move(value), nodeOffset + i,
                        getWarningDataFromChunks(warningData, i), errorHandler);
                }
            }
        },
        [&](auto) {
            throw CopyException(ExceptionMessage::invalidPKType(chunk.getDataType().toString()));
        });
}

void IndexBuilder::finishedProducing(NodeBatchInsertErrorHandler& errorHandler) {
    localBuffers.flush(errorHandler);
    sharedState->consume(errorHandler);
    while (!sharedState->isDone()) {
        std::this_thread::sleep_for(std::chrono::microseconds(500));
        sharedState->consume(errorHandler);
    }
}

void IndexBuilder::finalize(ExecutionContext* /*context*/,
    NodeBatchInsertErrorHandler& errorHandler) {
    // Flush anything added by last node group.
    localBuffers.flush(errorHandler);

    sharedState->consume(errorHandler);
}

bool IndexBuilder::checkNonNullConstraint(const ColumnChunkData& chunk,
    const std::vector<storage::ColumnChunkData*>& warningData, offset_t nodeOffset,
    offset_t chunkOffset, NodeBatchInsertErrorHandler& errorHandler) {
    const auto* nullChunk = chunk.getNullData();
    if (nullChunk->isNull(chunkOffset)) {
        TypeUtils::visit(
            chunk.getDataType().getPhysicalType(),
            [&](struct_entry_t) {
                // primary key cannot be struct
                KU_UNREACHABLE;
            },
            [&]<typename T>(T) {
                errorHandler.handleError<T>({.message = ExceptionMessage::nullPKException(),
                    .key = {},
                    .nodeID =
                        nodeID_t{nodeOffset + chunkOffset, sharedState->nodeTable->getTableID()},
                    .warningData = getWarningDataFromChunks(warningData, chunkOffset)});
            });
        return false;
    }
    return true;
}

} // namespace processor
} // namespace lbug
