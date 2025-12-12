#pragma once

#include <array>
#include <variant>

#include "common/copy_constructors.h"
#include "common/mpsc_queue.h"
#include "common/static_vector.h"
#include "common/types/int128_t.h"
#include "common/types/types.h"
#include "common/types/uint128_t.h"
#include "processor/operator/persistent/node_batch_insert_error_handler.h"
#include "storage/index/hash_index.h"
#include "storage/index/hash_index_utils.h"
#include "storage/table/column_chunk_data.h"

namespace lbug {
namespace transaction {
class Transaction;
};
namespace storage {
class NodeTable;
};
namespace processor {

constexpr size_t SHOULD_FLUSH_QUEUE_SIZE = 32;

constexpr size_t WARNING_DATA_BUFFER_SIZE = 64;
using OptionalWarningDataBuffer =
    std::unique_ptr<common::StaticVector<WarningSourceData, WARNING_DATA_BUFFER_SIZE>>;

using OptionalWarningSourceData = std::optional<WarningSourceData>;

template<typename T>
struct IndexBufferWithWarningData {
    storage::IndexBuffer<T> indexBuffer;
    OptionalWarningDataBuffer warningDataBuffer;

    bool full() const;
    void append(T key, common::offset_t value, OptionalWarningSourceData&& warningData);
};

class IndexBuilderGlobalQueues {
public:
    explicit IndexBuilderGlobalQueues(transaction::Transaction* transaction,
        storage::NodeTable* nodeTable);

    template<typename T>
    void insert(size_t index, IndexBufferWithWarningData<T> elem,
        NodeBatchInsertErrorHandler& errorHandler) {
        auto& typedQueues = std::get<Queue<T>>(queues).array;
        typedQueues[index].push(std::move(elem));
        if (typedQueues[index].approxSize() < SHOULD_FLUSH_QUEUE_SIZE) {
            return;
        }
        maybeConsumeIndex(index, errorHandler);
    }

    void consume(NodeBatchInsertErrorHandler& errorHandler);

    common::PhysicalTypeID pkTypeID() const;

private:
    void maybeConsumeIndex(size_t index, NodeBatchInsertErrorHandler& errorHandler);

    storage::NodeTable* nodeTable;

    template<typename T>
    // NOLINTNEXTLINE (cppcoreguidelines-pro-type-member-init)
    struct Queue {
        std::array<common::MPSCQueue<IndexBufferWithWarningData<T>>, storage::NUM_HASH_INDEXES>
            array;
        // Type information to help std::visit. Value is not used
        T type;
    };

    // Queues for distributing primary keys.
    std::variant<Queue<std::string>, Queue<int64_t>, Queue<int32_t>, Queue<int16_t>, Queue<int8_t>,
        Queue<uint64_t>, Queue<uint32_t>, Queue<uint16_t>, Queue<uint8_t>, Queue<common::int128_t>,
        Queue<common::uint128_t>, Queue<float>, Queue<double>>
        queues;
    transaction::Transaction* transaction;
};

class IndexBuilderLocalBuffers {
public:
    explicit IndexBuilderLocalBuffers(IndexBuilderGlobalQueues& globalQueues);

    void insert(std::string key, common::offset_t value, OptionalWarningSourceData&& warningData,
        NodeBatchInsertErrorHandler& errorHandler) {
        auto indexPos = storage::HashIndexUtils::getHashIndexPosition(std::string_view(key));
        auto& stringBuffer = (*std::get<UniqueBuffers<std::string>>(buffers))[indexPos];

        if (stringBuffer.full()) {
            // StaticVector's move constructor leaves the original vector valid and empty
            globalQueues->insert(indexPos, std::move(stringBuffer), errorHandler);
        }

        // moving the buffer clears it which is the expected behaviour
        // NOLINTNEXTLINE (bugprone-use-after-move)
        stringBuffer.append(std::move(key), value, std::move(warningData));
    }

    template<common::HashablePrimitive T>
    void insert(T key, common::offset_t value, OptionalWarningSourceData&& warningData,
        NodeBatchInsertErrorHandler& errorHandler) {
        auto indexPos = storage::HashIndexUtils::getHashIndexPosition(key);
        auto& buffer = (*std::get<UniqueBuffers<T>>(buffers))[indexPos];

        if (buffer.full()) {
            globalQueues->insert(indexPos, std::move(buffer), errorHandler);
        }

        // moving the buffer clears it which is the expected behaviour
        // NOLINTNEXTLINE (bugprone-use-after-move)
        buffer.append(key, value, std::move(warningData));
    }

    void flush(NodeBatchInsertErrorHandler& errorHandler);

private:
    IndexBuilderGlobalQueues* globalQueues;

    // These arrays are much too large to be inline.
    template<typename T>
    using Buffers = std::array<IndexBufferWithWarningData<T>, storage::NUM_HASH_INDEXES>;
    template<typename T>
    using UniqueBuffers = std::unique_ptr<Buffers<T>>;
    std::variant<UniqueBuffers<std::string>, UniqueBuffers<int64_t>, UniqueBuffers<int32_t>,
        UniqueBuffers<int16_t>, UniqueBuffers<int8_t>, UniqueBuffers<uint64_t>,
        UniqueBuffers<uint32_t>, UniqueBuffers<uint16_t>, UniqueBuffers<uint8_t>,
        UniqueBuffers<common::int128_t>, UniqueBuffers<common::uint128_t>, UniqueBuffers<float>,
        UniqueBuffers<double>>
        buffers;
};

class IndexBuilderSharedState {
    friend class IndexBuilder;

public:
    explicit IndexBuilderSharedState(transaction::Transaction* transaction,
        storage::NodeTable* nodeTable)
        : globalQueues{transaction, nodeTable}, nodeTable(nodeTable) {}
    void consume(NodeBatchInsertErrorHandler& errorHandler) {
        return globalQueues.consume(errorHandler);
    }

    void addProducer() { producers.fetch_add(1, std::memory_order_relaxed); }
    void quitProducer();
    bool isDone() const { return done.load(std::memory_order_relaxed); }

private:
    IndexBuilderGlobalQueues globalQueues;
    storage::NodeTable* nodeTable;

    std::atomic<size_t> producers;
    std::atomic<bool> done;
};

// RAII for producer counting.
class ProducerToken {
public:
    explicit ProducerToken(std::shared_ptr<IndexBuilderSharedState> sharedState)
        : sharedState(std::move(sharedState)) {
        this->sharedState->addProducer();
    }
    DELETE_COPY_DEFAULT_MOVE(ProducerToken);

    void quit() {
        sharedState->quitProducer();
        sharedState.reset();
    }
    ~ProducerToken() {
        if (sharedState) {
            quit();
        }
    }

private:
    std::shared_ptr<IndexBuilderSharedState> sharedState;
};

class IndexBuilder {

public:
    DELETE_COPY_DEFAULT_MOVE(IndexBuilder);
    explicit IndexBuilder(std::shared_ptr<IndexBuilderSharedState> sharedState);

    IndexBuilder clone() { return IndexBuilder(sharedState); }

    void insert(const storage::ColumnChunkData& chunk,
        const std::vector<storage::ColumnChunkData*>& warningData, common::offset_t nodeOffset,
        common::offset_t numNodes, NodeBatchInsertErrorHandler& errorHandler);

    ProducerToken getProducerToken() const { return ProducerToken(sharedState); }

    void finishedProducing(NodeBatchInsertErrorHandler& errorHandler);
    void finalize(ExecutionContext* context, NodeBatchInsertErrorHandler& errorHandler);

private:
    bool checkNonNullConstraint(const storage::ColumnChunkData& chunk,
        const std::vector<storage::ColumnChunkData*>& warningData, common::offset_t nodeOffset,
        common::offset_t chunkOffset, NodeBatchInsertErrorHandler& errorHandler);
    std::shared_ptr<IndexBuilderSharedState> sharedState;

    IndexBuilderLocalBuffers localBuffers;
};

} // namespace processor
} // namespace lbug
