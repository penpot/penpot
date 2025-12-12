#include "storage/table/column_chunk_data.h"

#include <algorithm>

#include "common/data_chunk/sel_vector.h"
#include "common/exception/copy.h"
#include "common/null_mask.h"
#include "common/serializer/deserializer.h"
#include "common/serializer/serializer.h"
#include "common/system_config.h"
#include "common/type_utils.h"
#include "common/types/types.h"
#include "common/vector/value_vector.h"
#include "expression_evaluator/expression_evaluator.h"
#include "storage/buffer_manager/buffer_manager.h"
#include "storage/buffer_manager/memory_manager.h"
#include "storage/buffer_manager/spill_result.h"
#include "storage/buffer_manager/spiller.h"
#include "storage/compression/compression.h"
#include "storage/compression/float_compression.h"
#include "storage/enums/residency_state.h"
#include "storage/stats/column_stats.h"
#include "storage/table/column.h"
#include "storage/table/column_chunk_metadata.h"
#include "storage/table/compression_flush_buffer.h"
#include "storage/table/list_chunk_data.h"
#include "storage/table/string_chunk_data.h"
#include "storage/table/struct_chunk_data.h"

using namespace lbug::common;
using namespace lbug::evaluator;
using namespace lbug::transaction;

namespace lbug {
namespace storage {

void SegmentState::reclaimAllocatedPages(PageAllocator& pageAllocator) const {
    const auto& entry = metadata.pageRange;
    if (entry.startPageIdx != INVALID_PAGE_IDX) {
        pageAllocator.freePageRange(entry);
    }
    if (nullState) {
        nullState->reclaimAllocatedPages(pageAllocator);
    }
    for (const auto& child : childrenStates) {
        child.reclaimAllocatedPages(pageAllocator);
    }
}

static std::shared_ptr<CompressionAlg> getCompression(const LogicalType& dataType,
    bool enableCompression) {
    if (!enableCompression) {
        return std::make_shared<Uncompressed>(dataType);
    }
    switch (dataType.getPhysicalType()) {
    case PhysicalTypeID::INT128: {
        return std::make_shared<IntegerBitpacking<int128_t>>();
    }
    case PhysicalTypeID::INT64: {
        return std::make_shared<IntegerBitpacking<int64_t>>();
    }
    case PhysicalTypeID::INT32: {
        return std::make_shared<IntegerBitpacking<int32_t>>();
    }
    case PhysicalTypeID::INT16: {
        return std::make_shared<IntegerBitpacking<int16_t>>();
    }
    case PhysicalTypeID::INT8: {
        return std::make_shared<IntegerBitpacking<int8_t>>();
    }
    case PhysicalTypeID::INTERNAL_ID:
    case PhysicalTypeID::UINT64: {
        return std::make_shared<IntegerBitpacking<uint64_t>>();
    }
    case PhysicalTypeID::UINT32: {
        return std::make_shared<IntegerBitpacking<uint32_t>>();
    }
    case PhysicalTypeID::UINT16: {
        return std::make_shared<IntegerBitpacking<uint16_t>>();
    }
    case PhysicalTypeID::UINT8: {
        return std::make_shared<IntegerBitpacking<uint8_t>>();
    }
    case PhysicalTypeID::FLOAT: {
        return std::make_shared<FloatCompression<float>>();
    }
    case PhysicalTypeID::DOUBLE: {
        return std::make_shared<FloatCompression<double>>();
    }
    default: {
        return std::make_shared<Uncompressed>(dataType);
    }
    }
}

ColumnChunkData::ColumnChunkData(MemoryManager& mm, LogicalType dataType, uint64_t capacity,
    bool enableCompression, ResidencyState residencyState, bool hasNullData, bool initializeToZero)
    : residencyState{residencyState}, dataType{std::move(dataType)},
      enableCompression{enableCompression},
      numBytesPerValue{getDataTypeSizeInChunk(this->dataType)}, capacity{capacity}, numValues{0},
      inMemoryStats() {
    if (hasNullData) {
        nullData = std::make_unique<NullChunkData>(mm, capacity, enableCompression, residencyState);
    }
    initializeBuffer(this->dataType.getPhysicalType(), mm, initializeToZero);
    initializeFunction();
}

ColumnChunkData::ColumnChunkData(MemoryManager& mm, LogicalType dataType, bool enableCompression,
    const ColumnChunkMetadata& metadata, bool hasNullData, bool initializeToZero)
    : residencyState(ResidencyState::ON_DISK), dataType{std::move(dataType)},
      enableCompression{enableCompression},
      numBytesPerValue{getDataTypeSizeInChunk(this->dataType)}, capacity{0},
      numValues{metadata.numValues}, metadata{metadata} {
    if (hasNullData) {
        nullData = std::make_unique<NullChunkData>(mm, enableCompression, metadata);
    }
    initializeBuffer(this->dataType.getPhysicalType(), mm, initializeToZero);
    initializeFunction();
}

ColumnChunkData::ColumnChunkData(MemoryManager& mm, PhysicalTypeID dataType, bool enableCompression,
    const ColumnChunkMetadata& metadata, bool hasNullData, bool initializeToZero)
    : ColumnChunkData(mm, LogicalType::ANY(dataType), enableCompression, metadata, hasNullData,
          initializeToZero) {}

void ColumnChunkData::initializeBuffer(PhysicalTypeID physicalType, MemoryManager& mm,
    bool initializeToZero) {
    numBytesPerValue = getDataTypeSizeInChunk(physicalType);

    // Some columnChunks are much smaller than the 256KB minimum size used by allocateBuffer
    // Which would lead to excessive memory use, particularly in the partitioner
    buffer = mm.allocateBuffer(initializeToZero, getBufferSize(capacity));
}

void ColumnChunkData::initializeFunction() {
    const auto compression = getCompression(dataType, enableCompression);
    getMetadataFunction = GetCompressionMetadata(compression, dataType);
    flushBufferFunction = initializeFlushBufferFunction(compression);
}

ColumnChunkData::flush_buffer_func_t ColumnChunkData::initializeFlushBufferFunction(
    std::shared_ptr<CompressionAlg> compression) const {
    switch (dataType.getPhysicalType()) {
    case PhysicalTypeID::BOOL: {
        // Since we compress into memory, storage is the same as fixed-sized
        // values, but we need to mark it as being boolean compressed.
        return uncompressedFlushBuffer;
    }
    case PhysicalTypeID::STRING:
    case PhysicalTypeID::INT64:
    case PhysicalTypeID::INT32:
    case PhysicalTypeID::INT16:
    case PhysicalTypeID::INT8:
    case PhysicalTypeID::INTERNAL_ID:
    case PhysicalTypeID::ARRAY:
    case PhysicalTypeID::LIST:
    case PhysicalTypeID::UINT64:
    case PhysicalTypeID::UINT32:
    case PhysicalTypeID::UINT16:
    case PhysicalTypeID::UINT8:
    case PhysicalTypeID::INT128: {
        return CompressedFlushBuffer(compression, dataType);
    }
    case PhysicalTypeID::DOUBLE: {
        return CompressedFloatFlushBuffer<double>(compression, dataType);
    }
    case PhysicalTypeID::FLOAT: {
        return CompressedFloatFlushBuffer<float>(compression, dataType);
    }
    default: {
        return uncompressedFlushBuffer;
    }
    }
}

void ColumnChunkData::resetToAllNull() {
    KU_ASSERT(residencyState != ResidencyState::ON_DISK);
    if (nullData) {
        nullData->resetToAllNull();
    }
    resetInMemoryStats();
}

void ColumnChunkData::resetToEmpty() {
    KU_ASSERT(residencyState != ResidencyState::ON_DISK);
    if (nullData) {
        nullData->resetToEmpty();
    }
    KU_ASSERT(getBufferSize() == getBufferSize(capacity));
    memset(getData<uint8_t>(), 0x00, getBufferSize());
    numValues = 0;
    resetInMemoryStats();
}

static void updateInMemoryStats(ColumnChunkStats& stats, const ValueVector& values,
    uint64_t offset = 0, uint64_t numValues = std::numeric_limits<uint64_t>::max()) {
    const auto physicalType = values.dataType.getPhysicalType();
    const auto numValuesToCheck = std::min(numValues, values.state->getSelSize());
    stats.update(values, offset, numValuesToCheck, physicalType);
}

static void updateInMemoryStats(ColumnChunkStats& stats, const ColumnChunkData* values,
    uint64_t offset = 0, uint64_t numValues = std::numeric_limits<uint64_t>::max()) {
    const auto physicalType = values->getDataType().getPhysicalType();
    const auto numValuesToCheck = std::min(values->getNumValues(), numValues);
    const auto nullMask = values->getNullMask();
    stats.update(*values, offset, numValuesToCheck, physicalType);
}

MergedColumnChunkStats ColumnChunkData::getMergedColumnChunkStats() const {
    const CompressionMetadata& onDiskMetadata = metadata.compMeta;
    ColumnChunkStats stats = inMemoryStats;
    const auto physicalType = getDataType().getPhysicalType();
    const bool isStorageValueType =
        TypeUtils::visit(physicalType, []<typename T>(T) { return StorageValueType<T>; });
    if (isStorageValueType) {
        stats.update(onDiskMetadata.min, onDiskMetadata.max, physicalType);
    }
    return MergedColumnChunkStats{stats, !nullData || nullData->haveNoNullsGuaranteed(),
        nullData && nullData->haveAllNullsGuaranteed()};
}

void ColumnChunkData::updateStats(const ValueVector* vector, const SelectionView& selView) {
    if (selView.isUnfiltered()) {
        updateInMemoryStats(inMemoryStats, *vector);
    } else {
        TypeUtils::visit(
            getDataType().getPhysicalType(),
            [&]<StorageValueType T>(T) {
                std::optional<T> firstValue;
                // ValueVector::firstNonNull uses the vector's builtin selection vector, not the one
                // passed as an argument
                selView.forEachBreakWhenFalse([&](auto i) {
                    if (vector->isNull(i)) {
                        return true;
                    } else {
                        firstValue = vector->getValue<T>(i);
                        return false;
                    }
                });
                if (!firstValue) {
                    return;
                }
                T min = *firstValue, max = *firstValue;
                auto update = [&](sel_t pos) {
                    const auto val = vector->getValue<T>(pos);
                    if (val < min) {
                        min = val;
                    } else if (val > max) {
                        max = val;
                    }
                };
                if (vector->hasNoNullsGuarantee()) {
                    selView.forEach(update);
                } else {
                    selView.forEach([&](auto pos) {
                        if (!vector->isNull(pos)) {
                            update(pos);
                        }
                    });
                }
                inMemoryStats.update(StorageValue(min), StorageValue(max),
                    getDataType().getPhysicalType());
            },
            []<typename T>(T) { static_assert(!StorageValueType<T>); });
    }
}

void ColumnChunkData::resetInMemoryStats() {
    inMemoryStats.reset();
}

ColumnChunkMetadata ColumnChunkData::getMetadataToFlush() const {
    KU_ASSERT(numValues <= capacity);
    StorageValue minValue = {}, maxValue = {};
    if (capacity > 0) {
        std::optional<NullMask> nullMask;
        if (nullData) {
            nullMask = nullData->getNullMask();
        }
        auto [min, max] =
            getMinMaxStorageValue(getData(), 0 /*offset*/, numValues, dataType.getPhysicalType(),
                nullMask.has_value() ? &*nullMask : nullptr, true /*valueRequiredIfUnsupported*/);
        minValue = min.value_or(StorageValue());
        maxValue = max.value_or(StorageValue());
    }
    KU_ASSERT(getBufferSize() == getBufferSize(capacity));
    return getMetadataFunction(buffer->getBuffer(), numValues, minValue, maxValue);
}

void ColumnChunkData::append(ValueVector* vector, const SelectionView& selView) {
    KU_ASSERT(vector->dataType.getPhysicalType() == dataType.getPhysicalType());
    copyVectorToBuffer(vector, numValues, selView);
    numValues += selView.getSelSize();
    updateStats(vector, selView);
}

void ColumnChunkData::append(const ColumnChunkData* other, offset_t startPosInOtherChunk,
    uint32_t numValuesToAppend) {
    KU_ASSERT(other->dataType.getPhysicalType() == dataType.getPhysicalType());
    if (nullData) {
        KU_ASSERT(nullData->getNumValues() == getNumValues());
        nullData->append(other->nullData.get(), startPosInOtherChunk, numValuesToAppend);
    }
    KU_ASSERT(numValues + numValuesToAppend <= capacity);
    memcpy(getData<uint8_t>() + numValues * numBytesPerValue,
        other->getData<uint8_t>() + startPosInOtherChunk * numBytesPerValue,
        numValuesToAppend * numBytesPerValue);
    numValues += numValuesToAppend;
    updateInMemoryStats(inMemoryStats, other, startPosInOtherChunk, numValuesToAppend);
}

void ColumnChunkData::flush(PageAllocator& pageAllocator) {
    const auto preScanMetadata = getMetadataToFlush();
    auto allocatedEntry = pageAllocator.allocatePageRange(preScanMetadata.getNumPages());
    const auto flushedMetadata = flushBuffer(pageAllocator, allocatedEntry, preScanMetadata);
    setToOnDisk(flushedMetadata);
    if (nullData) {
        nullData->flush(pageAllocator);
    }
}

// Note: This function is not setting child/null chunk data recursively.
void ColumnChunkData::setToOnDisk(const ColumnChunkMetadata& otherMetadata) {
    residencyState = ResidencyState::ON_DISK;
    capacity = 0;
    // Note: We don't need to set the buffer to nullptr, as it allows ColumnChunkData to be resized.
    buffer = buffer->getMemoryManager()->allocateBuffer(true, 0 /*size*/);
    this->metadata = otherMetadata;
    this->numValues = otherMetadata.numValues;
    resetInMemoryStats();
}

ColumnChunkMetadata ColumnChunkData::flushBuffer(PageAllocator& pageAllocator,
    const PageRange& entry, const ColumnChunkMetadata& otherMetadata) const {
    const auto bufferSizeToFlush = getBufferSize(numValues);
    if (!otherMetadata.compMeta.isConstant() && bufferSizeToFlush != 0) {
        KU_ASSERT(bufferSizeToFlush <= buffer->getBuffer().size_bytes());
        const auto bufferToFlush = buffer->getBuffer().subspan(0, bufferSizeToFlush);
        return flushBufferFunction(bufferToFlush, pageAllocator.getDataFH(), entry, otherMetadata);
    }
    KU_ASSERT(otherMetadata.getNumPages() == 0);
    return otherMetadata;
}

uint64_t ColumnChunkData::getBufferSize(uint64_t capacity_) const {
    switch (dataType.getLogicalTypeID()) {
    case LogicalTypeID::BOOL: {
        // 8 values per byte, and we need a buffer size which is a
        // multiple of 8 bytes.
        return ceil(capacity_ / 8.0 / 8.0) * 8;
    }
    default: {
        return numBytesPerValue * capacity_;
    }
    }
}

void ColumnChunkData::initializeScanState(SegmentState& state, const Column* column) const {
    if (nullData) {
        KU_ASSERT(state.nullState);
        nullData->initializeScanState(*state.nullState, column->getNullColumn());
    }
    state.column = column;
    if (residencyState == ResidencyState::ON_DISK) {
        state.metadata = metadata;
        state.numValuesPerPage = state.metadata.compMeta.numValues(LBUG_PAGE_SIZE, dataType);

        state.column->populateExtraChunkState(state);
    }
}

void ColumnChunkData::scan(ValueVector& output, offset_t offset, length_t length,
    sel_t posInOutputVector) const {
    KU_ASSERT(offset + length <= numValues);
    if (nullData) {
        nullData->scan(output, offset, length, posInOutputVector);
    }
    memcpy(output.getData() + posInOutputVector * numBytesPerValue,
        getData() + offset * numBytesPerValue, numBytesPerValue * length);
}

void ColumnChunkData::lookup(offset_t offsetInChunk, ValueVector& output,
    sel_t posInOutputVector) const {
    KU_ASSERT(offsetInChunk < capacity);
    output.setNull(posInOutputVector, isNull(offsetInChunk));
    if (!output.isNull(posInOutputVector)) {
        memcpy(output.getData() + posInOutputVector * numBytesPerValue,
            getData() + offsetInChunk * numBytesPerValue, numBytesPerValue);
    }
}

void ColumnChunkData::write(ColumnChunkData* chunk, ColumnChunkData* dstOffsets,
    RelMultiplicity multiplicity) {
    KU_ASSERT(chunk->dataType.getPhysicalType() == dataType.getPhysicalType() &&
              dstOffsets->getDataType().getPhysicalType() == PhysicalTypeID::INTERNAL_ID &&
              chunk->getNumValues() == dstOffsets->getNumValues());
    for (auto i = 0u; i < dstOffsets->getNumValues(); i++) {
        const auto dstOffset = dstOffsets->getValue<offset_t>(i);
        KU_ASSERT(dstOffset < capacity);
        memcpy(getData() + dstOffset * numBytesPerValue, chunk->getData() + i * numBytesPerValue,
            numBytesPerValue);
        numValues = dstOffset >= numValues ? dstOffset + 1 : numValues;
    }
    if (nullData || multiplicity == RelMultiplicity::ONE) {
        for (auto i = 0u; i < dstOffsets->getNumValues(); i++) {
            const auto dstOffset = dstOffsets->getValue<offset_t>(i);
            if (multiplicity == RelMultiplicity::ONE && isNull(dstOffset)) {
                throw CopyException(
                    stringFormat("Node with offset: {} can only have one neighbour due "
                                 "to the MANY-ONE/ONE-ONE relationship constraint.",
                        dstOffset));
            }
            if (nullData) {
                nullData->setNull(dstOffset, chunk->isNull(i));
            }
        }
    }
    updateInMemoryStats(inMemoryStats, chunk);
}

// NOTE: This function is only called in LocalTable right now when
// performing out-of-place committing. LIST has a different logic for
// handling out-of-place committing as it has to be slided. However,
// this is unsafe, as this function can also be used for other purposes
// later. Thus, an assertion is added at the first line.
void ColumnChunkData::write(const ValueVector* vector, offset_t offsetInVector,
    offset_t offsetInChunk) {
    KU_ASSERT(dataType.getPhysicalType() != PhysicalTypeID::BOOL &&
              dataType.getPhysicalType() != PhysicalTypeID::LIST &&
              dataType.getPhysicalType() != PhysicalTypeID::ARRAY);
    if (nullData) {
        nullData->setNull(offsetInChunk, vector->isNull(offsetInVector));
    }
    if (offsetInChunk >= numValues) {
        numValues = offsetInChunk + 1;
    }
    if (!vector->isNull(offsetInVector)) {
        memcpy(getData() + offsetInChunk * numBytesPerValue,
            vector->getData() + offsetInVector * numBytesPerValue, numBytesPerValue);
    }
    static constexpr uint64_t numValuesToWrite = 1;
    updateInMemoryStats(inMemoryStats, *vector, offsetInVector, numValuesToWrite);
}

void ColumnChunkData::write(const ColumnChunkData* srcChunk, offset_t srcOffsetInChunk,
    offset_t dstOffsetInChunk, offset_t numValuesToCopy) {
    KU_ASSERT(srcChunk->dataType.getPhysicalType() == dataType.getPhysicalType());
    if ((dstOffsetInChunk + numValuesToCopy) >= numValues) {
        numValues = dstOffsetInChunk + numValuesToCopy;
    }
    memcpy(getData() + dstOffsetInChunk * numBytesPerValue,
        srcChunk->getData() + srcOffsetInChunk * numBytesPerValue,
        numValuesToCopy * numBytesPerValue);
    if (nullData) {
        KU_ASSERT(srcChunk->getNullData());
        nullData->write(srcChunk->getNullData(), srcOffsetInChunk, dstOffsetInChunk,
            numValuesToCopy);
    }
    updateInMemoryStats(inMemoryStats, srcChunk, srcOffsetInChunk, numValuesToCopy);
}

void ColumnChunkData::resetNumValuesFromMetadata() {
    KU_ASSERT(residencyState == ResidencyState::ON_DISK);
    numValues = metadata.numValues;
    if (nullData) {
        nullData->resetNumValuesFromMetadata();
        // FIXME(bmwinger): not always working
        // KU_ASSERT(numValues == nullData->numValues);
    }
}

void ColumnChunkData::setToInMemory() {
    KU_ASSERT(residencyState == ResidencyState::ON_DISK);
    KU_ASSERT(capacity == 0 && getBufferSize() == 0);
    residencyState = ResidencyState::IN_MEMORY;
    numValues = 0;
    if (nullData) {
        nullData->setToInMemory();
    }
}

void ColumnChunkData::resize(uint64_t newCapacity) {
    const auto numBytesAfterResize = getBufferSize(newCapacity);
    if (numBytesAfterResize > getBufferSize()) {
        auto resizedBuffer = buffer->getMemoryManager()->allocateBuffer(false, numBytesAfterResize);
        auto bufferSize = getBufferSize();
        auto resizedBufferData = resizedBuffer->getBuffer().data();
        memcpy(resizedBufferData, buffer->getBuffer().data(), bufferSize);
        memset(resizedBufferData + bufferSize, 0, numBytesAfterResize - bufferSize);
        buffer = std::move(resizedBuffer);
    }
    if (nullData) {
        nullData->resize(newCapacity);
    }
    if (newCapacity > capacity) {
        capacity = newCapacity;
    }
}

void ColumnChunkData::resizeWithoutPreserve(uint64_t newCapacity) {
    const auto numBytesAfterResize = getBufferSize(newCapacity);
    if (numBytesAfterResize > getBufferSize()) {
        auto resizedBuffer = buffer->getMemoryManager()->allocateBuffer(false, numBytesAfterResize);
        buffer = std::move(resizedBuffer);
    }
    if (nullData) {
        nullData->resize(newCapacity);
    }
    if (newCapacity > capacity) {
        capacity = newCapacity;
    }
}

void ColumnChunkData::populateWithDefaultVal(ExpressionEvaluator& defaultEvaluator,
    uint64_t& numValues_, ColumnStats* newColumnStats) {
    auto numValuesAppended = 0u;
    const auto numValuesToPopulate = numValues_;
    while (numValuesAppended < numValuesToPopulate) {
        const auto numValuesToAppend =
            std::min(DEFAULT_VECTOR_CAPACITY, numValuesToPopulate - numValuesAppended);
        defaultEvaluator.evaluate(numValuesToAppend);
        auto resultVector = defaultEvaluator.resultVector.get();
        KU_ASSERT(resultVector->state->getSelVector().getSelSize() == numValuesToAppend);
        append(resultVector, resultVector->state->getSelVector());
        if (newColumnStats) {
            newColumnStats->update(resultVector);
        }
        numValuesAppended += numValuesToAppend;
    }
}

void ColumnChunkData::copyVectorToBuffer(ValueVector* vector, offset_t startPosInChunk,
    const SelectionView& selView) {
    auto bufferToWrite = buffer->getBuffer().data() + startPosInChunk * numBytesPerValue;
    KU_ASSERT(startPosInChunk + selView.getSelSize() <= capacity);
    const auto vectorDataToWriteFrom = vector->getData();
    if (nullData) {
        nullData->appendNulls(vector, selView, startPosInChunk);
    }
    if (selView.isUnfiltered()) {
        memcpy(bufferToWrite, vectorDataToWriteFrom, selView.getSelSize() * numBytesPerValue);
    } else {
        selView.forEach([&](auto pos) {
            memcpy(bufferToWrite, vectorDataToWriteFrom + pos * numBytesPerValue, numBytesPerValue);
            bufferToWrite += numBytesPerValue;
        });
    }
}

void ColumnChunkData::setNumValues(uint64_t numValues_) {
    KU_ASSERT(numValues_ <= capacity);
    numValues = numValues_;
    if (nullData) {
        nullData->setNumValues(numValues_);
    }
}

bool ColumnChunkData::numValuesSanityCheck() const {
    if (nullData) {
        return numValues == nullData->getNumValues();
    }
    return numValues <= capacity;
}

bool ColumnChunkData::sanityCheck() const {
    if (nullData) {
        return nullData->sanityCheck() && numValuesSanityCheck();
    }
    return numValues <= capacity;
}

uint64_t ColumnChunkData::getEstimatedMemoryUsage() const {
    return buffer->getBuffer().size() + (nullData ? nullData->getEstimatedMemoryUsage() : 0);
}

void ColumnChunkData::serialize(Serializer& serializer) const {
    KU_ASSERT(residencyState == ResidencyState::ON_DISK);
    serializer.writeDebuggingInfo("data_type");
    dataType.serialize(serializer);
    serializer.writeDebuggingInfo("metadata");
    metadata.serialize(serializer);
    serializer.writeDebuggingInfo("enable_compression");
    serializer.write<bool>(enableCompression);
    serializer.writeDebuggingInfo("has_null");
    serializer.write<bool>(nullData != nullptr);
    if (nullData) {
        serializer.writeDebuggingInfo("null_data");
        nullData->serialize(serializer);
    }
}

std::unique_ptr<ColumnChunkData> ColumnChunkData::deserialize(MemoryManager& memoryManager,
    Deserializer& deSer) {
    std::string key;
    ColumnChunkMetadata metadata;
    bool enableCompression = false;
    bool hasNull = false;
    bool initializeToZero = true;
    deSer.validateDebuggingInfo(key, "data_type");
    const auto dataType = LogicalType::deserialize(deSer);
    deSer.validateDebuggingInfo(key, "metadata");
    metadata = decltype(metadata)::deserialize(deSer);
    deSer.validateDebuggingInfo(key, "enable_compression");
    deSer.deserializeValue<bool>(enableCompression);
    deSer.validateDebuggingInfo(key, "has_null");
    deSer.deserializeValue<bool>(hasNull);
    auto chunkData = ColumnChunkFactory::createColumnChunkData(memoryManager, dataType.copy(),
        enableCompression, metadata, hasNull, initializeToZero);
    if (hasNull) {
        deSer.validateDebuggingInfo(key, "null_data");
        chunkData->nullData = NullChunkData::deserialize(memoryManager, deSer);
    }

    switch (dataType.getPhysicalType()) {
    case PhysicalTypeID::STRUCT: {
        StructChunkData::deserialize(deSer, *chunkData);
    } break;
    case PhysicalTypeID::STRING: {
        StringChunkData::deserialize(deSer, *chunkData);
    } break;
    case PhysicalTypeID::ARRAY:
    case PhysicalTypeID::LIST: {
        ListChunkData::deserialize(deSer, *chunkData);
    } break;
    default: {
        // DO NOTHING.
    }
    }

    return chunkData;
}

void BoolChunkData::append(ValueVector* vector, const SelectionView& selView) {
    KU_ASSERT(vector->dataType.getPhysicalType() == PhysicalTypeID::BOOL);
    for (auto i = 0u; i < selView.getSelSize(); i++) {
        const auto pos = selView[i];
        NullMask::setNull(getData<uint64_t>(), numValues + i, vector->getValue<bool>(pos));
    }
    if (nullData) {
        nullData->appendNulls(vector, selView, numValues);
    }
    numValues += selView.getSelSize();
    updateStats(vector, selView);
}

void BoolChunkData::append(const ColumnChunkData* other, offset_t startPosInOtherChunk,
    uint32_t numValuesToAppend) {
    NullMask::copyNullMask(other->getData<uint64_t>(), startPosInOtherChunk, getData<uint64_t>(),
        numValues, numValuesToAppend);
    if (nullData) {
        nullData->append(other->getNullData(), startPosInOtherChunk, numValuesToAppend);
    }
    numValues += numValuesToAppend;
    updateInMemoryStats(inMemoryStats, other, startPosInOtherChunk, numValuesToAppend);
}

void BoolChunkData::scan(ValueVector& output, offset_t offset, length_t length,
    sel_t posInOutputVector) const {
    KU_ASSERT(offset + length <= numValues);
    if (nullData) {
        nullData->scan(output, offset, length, posInOutputVector);
    }
    for (auto i = 0u; i < length; i++) {
        output.setValue<bool>(posInOutputVector + i,
            NullMask::isNull(getData<uint64_t>(), offset + i));
    }
}

void BoolChunkData::lookup(offset_t offsetInChunk, ValueVector& output,
    sel_t posInOutputVector) const {
    KU_ASSERT(offsetInChunk < capacity);
    output.setNull(posInOutputVector, nullData->isNull(offsetInChunk));
    if (!output.isNull(posInOutputVector)) {
        output.setValue<bool>(posInOutputVector,
            NullMask::isNull(getData<uint64_t>(), offsetInChunk));
    }
}

void BoolChunkData::write(ColumnChunkData* chunk, ColumnChunkData* dstOffsets, RelMultiplicity) {
    KU_ASSERT(chunk->getDataType().getPhysicalType() == PhysicalTypeID::BOOL &&
              dstOffsets->getDataType().getPhysicalType() == PhysicalTypeID::INTERNAL_ID &&
              chunk->getNumValues() == dstOffsets->getNumValues());
    for (auto i = 0u; i < dstOffsets->getNumValues(); i++) {
        const auto dstOffset = dstOffsets->getValue<offset_t>(i);
        KU_ASSERT(dstOffset < capacity);
        NullMask::setNull(getData<uint64_t>(), dstOffset, chunk->getValue<bool>(i));
        if (nullData) {
            nullData->setNull(dstOffset, chunk->getNullData()->isNull(i));
        }
        numValues = dstOffset >= numValues ? dstOffset + 1 : numValues;
    }
    updateInMemoryStats(inMemoryStats, chunk);
}

void BoolChunkData::write(const ValueVector* vector, offset_t offsetInVector,
    offset_t offsetInChunk) {
    KU_ASSERT(vector->dataType.getPhysicalType() == PhysicalTypeID::BOOL);
    KU_ASSERT(offsetInChunk < capacity);
    const auto valueToSet = vector->getValue<bool>(offsetInVector);
    setValue(valueToSet, offsetInChunk);
    if (nullData) {
        nullData->write(vector, offsetInVector, offsetInChunk);
    }
    numValues = offsetInChunk >= numValues ? offsetInChunk + 1 : numValues;
    if (!vector->isNull(offsetInVector)) {
        inMemoryStats.update(StorageValue{valueToSet}, dataType.getPhysicalType());
    }
}

void BoolChunkData::write(const ColumnChunkData* srcChunk, offset_t srcOffsetInChunk,
    offset_t dstOffsetInChunk, offset_t numValuesToCopy) {
    if (nullData) {
        nullData->write(srcChunk->getNullData(), srcOffsetInChunk, dstOffsetInChunk,
            numValuesToCopy);
    }
    if ((dstOffsetInChunk + numValuesToCopy) >= numValues) {
        numValues = dstOffsetInChunk + numValuesToCopy;
    }
    NullMask::copyNullMask(srcChunk->getData<uint64_t>(), srcOffsetInChunk, getData<uint64_t>(),
        dstOffsetInChunk, numValuesToCopy);
    updateInMemoryStats(inMemoryStats, srcChunk, srcOffsetInChunk, numValuesToCopy);
}

NullMask NullChunkData::getNullMask() const {
    return NullMask(
        std::span(getData<uint64_t>(), ceilDiv(capacity, NullMask::NUM_BITS_PER_NULL_ENTRY)),
        !noNullsGuaranteedInMem());
}

void NullChunkData::setNull(offset_t pos, bool isNull) {
    setValue(isNull, pos);
    // TODO(Guodong): Better let NullChunkData also support `append` a
    // vector.
}

void NullChunkData::write(const ValueVector* vector, offset_t offsetInVector,
    offset_t offsetInChunk) {
    const bool isNull = vector->isNull(offsetInVector);
    setValue(isNull, offsetInChunk);
}

void NullChunkData::write(const ColumnChunkData* srcChunk, offset_t srcOffsetInChunk,
    offset_t dstOffsetInChunk, offset_t numValuesToCopy) {
    if (numValuesToCopy == 0) {
        return;
    }
    KU_ASSERT(srcChunk->getBufferSize() >= sizeof(uint64_t));
    copyFromBuffer(srcChunk->getData<uint64_t>(), srcOffsetInChunk, dstOffsetInChunk,
        numValuesToCopy);
}

void NullChunkData::append(const ColumnChunkData* other, offset_t startOffsetInOtherChunk,
    uint32_t numValuesToAppend) {
    write(other, startOffsetInOtherChunk, numValues, numValuesToAppend);
}

bool NullChunkData::haveNoNullsGuaranteed() const {
    return noNullsGuaranteedInMem() && !metadata.compMeta.max.get<bool>();
}

bool NullChunkData::haveAllNullsGuaranteed() const {
    return allNullsGuaranteedInMem() && metadata.compMeta.min.get<bool>();
}

void NullChunkData::serialize(Serializer& serializer) const {
    KU_ASSERT(residencyState == ResidencyState::ON_DISK);
    serializer.writeDebuggingInfo("null_chunk_metadata");
    metadata.serialize(serializer);
}

std::unique_ptr<NullChunkData> NullChunkData::deserialize(MemoryManager& memoryManager,
    Deserializer& deSer) {
    std::string key;
    ColumnChunkMetadata metadata;
    deSer.validateDebuggingInfo(key, "null_chunk_metadata");
    metadata = decltype(metadata)::deserialize(deSer);
    // TODO: FIX-ME. enableCompression.
    return std::make_unique<NullChunkData>(memoryManager, true, metadata);
}

void NullChunkData::scan(ValueVector& output, offset_t offset, length_t length,
    sel_t posInOutputVector) const {
    output.setNullFromBits(getNullMask().getData(), offset, posInOutputVector, length);
}

void NullChunkData::appendNulls(const ValueVector* vector, const SelectionView& selView,
    offset_t startPosInChunk) {
    if (selView.isUnfiltered()) {
        copyFromBuffer(vector->getNullMask().getData(), 0, startPosInChunk, selView.getSelSize());
    } else {
        for (auto i = 0u; i < selView.getSelSize(); i++) {
            const auto pos = selView[i];
            setNull(startPosInChunk + i, vector->isNull(pos));
        }
    }
}

void InternalIDChunkData::append(ValueVector* vector, const SelectionView& selView) {
    switch (vector->dataType.getPhysicalType()) {
    case PhysicalTypeID::INTERNAL_ID: {
        copyVectorToBuffer(vector, numValues, selView);
    } break;
    case PhysicalTypeID::INT64: {
        copyInt64VectorToBuffer(vector, numValues, selView);
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
    numValues += selView.getSelSize();
}

void InternalIDChunkData::copyVectorToBuffer(ValueVector* vector, offset_t startPosInChunk,
    const SelectionView& selView) {
    KU_ASSERT(vector->dataType.getPhysicalType() == PhysicalTypeID::INTERNAL_ID);
    const auto relIDsInVector = reinterpret_cast<internalID_t*>(vector->getData());
    if (commonTableID == INVALID_TABLE_ID) {
        commonTableID = relIDsInVector[selView[0]].tableID;
    }
    for (auto i = 0u; i < selView.getSelSize(); i++) {
        const auto pos = selView[i];
        if (vector->isNull(pos)) {
            continue;
        }
        KU_ASSERT(relIDsInVector[pos].tableID == commonTableID);
        memcpy(getData() + (startPosInChunk + i) * numBytesPerValue, &relIDsInVector[pos].offset,
            numBytesPerValue);
    }
}

void InternalIDChunkData::copyInt64VectorToBuffer(ValueVector* vector, offset_t startPosInChunk,
    const SelectionView& selView) const {
    KU_ASSERT(vector->dataType.getPhysicalType() == PhysicalTypeID::INT64);
    for (auto i = 0u; i < selView.getSelSize(); i++) {
        const auto pos = selView[i];
        if (vector->isNull(pos)) {
            continue;
        }
        memcpy(getData() + (startPosInChunk + i) * numBytesPerValue,
            &vector->getValue<offset_t>(pos), numBytesPerValue);
    }
}

void InternalIDChunkData::scan(ValueVector& output, offset_t offset, length_t length,
    sel_t posInOutputVector) const {
    KU_ASSERT(offset + length <= numValues);
    KU_ASSERT(commonTableID != INVALID_TABLE_ID);
    internalID_t relID;
    relID.tableID = commonTableID;
    for (auto i = 0u; i < length; i++) {
        relID.offset = getValue<offset_t>(offset + i);
        output.setValue<internalID_t>(posInOutputVector + i, relID);
    }
}

void InternalIDChunkData::lookup(offset_t offsetInChunk, ValueVector& output,
    sel_t posInOutputVector) const {
    KU_ASSERT(offsetInChunk < capacity);
    internalID_t relID;
    relID.offset = getValue<offset_t>(offsetInChunk);
    KU_ASSERT(commonTableID != INVALID_TABLE_ID);
    relID.tableID = commonTableID;
    output.setValue<internalID_t>(posInOutputVector, relID);
}

void InternalIDChunkData::write(const ValueVector* vector, offset_t offsetInVector,
    offset_t offsetInChunk) {
    KU_ASSERT(vector->dataType.getPhysicalType() == PhysicalTypeID::INTERNAL_ID);
    const auto relIDsInVector = reinterpret_cast<internalID_t*>(vector->getData());
    if (commonTableID == INVALID_TABLE_ID) {
        commonTableID = relIDsInVector[offsetInVector].tableID;
    }
    KU_ASSERT(commonTableID == relIDsInVector[offsetInVector].tableID);
    if (!vector->isNull(offsetInVector)) {
        memcpy(getData() + offsetInChunk * numBytesPerValue, &relIDsInVector[offsetInVector].offset,
            numBytesPerValue);
    }
    if (offsetInChunk >= numValues) {
        numValues = offsetInChunk + 1;
    }
}

void InternalIDChunkData::append(const ColumnChunkData* other, offset_t startPosInOtherChunk,
    uint32_t numValuesToAppend) {
    ColumnChunkData::append(other, startPosInOtherChunk, numValuesToAppend);
    commonTableID = other->cast<InternalIDChunkData>().commonTableID;
}

std::optional<NullMask> ColumnChunkData::getNullMask() const {
    return nullData ? std::optional(nullData->getNullMask()) : std::nullopt;
}

std::unique_ptr<ColumnChunkData> ColumnChunkFactory::createColumnChunkData(MemoryManager& mm,
    LogicalType dataType, bool enableCompression, uint64_t capacity, ResidencyState residencyState,
    bool hasNullData, bool initializeToZero) {
    switch (dataType.getPhysicalType()) {
    case PhysicalTypeID::BOOL: {
        return std::make_unique<BoolChunkData>(mm, capacity, enableCompression, residencyState,
            hasNullData);
    }
    case PhysicalTypeID::INT64:
    case PhysicalTypeID::INT32:
    case PhysicalTypeID::INT16:
    case PhysicalTypeID::INT8:
    case PhysicalTypeID::UINT64:
    case PhysicalTypeID::UINT32:
    case PhysicalTypeID::UINT16:
    case PhysicalTypeID::UINT8:
    case PhysicalTypeID::INT128:
    case PhysicalTypeID::UINT128:
    case PhysicalTypeID::DOUBLE:
    case PhysicalTypeID::FLOAT:
    case PhysicalTypeID::INTERVAL: {
        return std::make_unique<ColumnChunkData>(mm, std::move(dataType), capacity,
            enableCompression, residencyState, hasNullData, initializeToZero);
    }
    case PhysicalTypeID::INTERNAL_ID: {
        return std::make_unique<InternalIDChunkData>(mm, capacity, enableCompression,
            residencyState);
    }
    case PhysicalTypeID::STRING: {
        return std::make_unique<StringChunkData>(mm, std::move(dataType), capacity,
            enableCompression, residencyState);
    }
    case PhysicalTypeID::ARRAY:
    case PhysicalTypeID::LIST: {
        return std::make_unique<ListChunkData>(mm, std::move(dataType), capacity, enableCompression,
            residencyState);
    }
    case PhysicalTypeID::STRUCT: {
        return std::make_unique<StructChunkData>(mm, std::move(dataType), capacity,
            enableCompression, residencyState);
    }
    default:
        KU_UNREACHABLE;
    }
}

std::unique_ptr<ColumnChunkData> ColumnChunkFactory::createColumnChunkData(MemoryManager& mm,
    LogicalType dataType, bool enableCompression, ColumnChunkMetadata& metadata, bool hasNullData,
    bool initializeToZero) {
    switch (dataType.getPhysicalType()) {
    case PhysicalTypeID::BOOL: {
        return std::make_unique<BoolChunkData>(mm, enableCompression, metadata, hasNullData);
    }
    case PhysicalTypeID::INT64:
    case PhysicalTypeID::INT32:
    case PhysicalTypeID::INT16:
    case PhysicalTypeID::INT8:
    case PhysicalTypeID::UINT64:
    case PhysicalTypeID::UINT32:
    case PhysicalTypeID::UINT16:
    case PhysicalTypeID::UINT8:
    case PhysicalTypeID::INT128:
    case PhysicalTypeID::UINT128:
    case PhysicalTypeID::DOUBLE:
    case PhysicalTypeID::FLOAT:
    case PhysicalTypeID::INTERVAL: {
        return std::make_unique<ColumnChunkData>(mm, std::move(dataType), enableCompression,
            metadata, hasNullData, initializeToZero);
    }
        // Physically, we only materialize offset of INTERNAL_ID, which is same as INT64,
    case PhysicalTypeID::INTERNAL_ID: {
        // INTERNAL_ID should never have nulls.
        return std::make_unique<InternalIDChunkData>(mm, enableCompression, metadata);
    }
    case PhysicalTypeID::STRING: {
        return std::make_unique<StringChunkData>(mm, enableCompression, metadata);
    }
    case PhysicalTypeID::ARRAY:
    case PhysicalTypeID::LIST: {
        return std::make_unique<ListChunkData>(mm, std::move(dataType), enableCompression,
            metadata);
    }
    case PhysicalTypeID::STRUCT: {
        return std::make_unique<StructChunkData>(mm, std::move(dataType), enableCompression,
            metadata);
    }
    default:
        KU_UNREACHABLE;
    }
}

bool ColumnChunkData::isNull(offset_t pos) const {
    return nullData && nullData->isNull(pos);
}

MemoryManager& ColumnChunkData::getMemoryManager() const {
    return *buffer->getMemoryManager();
}

uint8_t* ColumnChunkData::getData() const {
    return buffer->getBuffer().data();
}
uint64_t ColumnChunkData::getBufferSize() const {
    return buffer->getBuffer().size_bytes();
}

void ColumnChunkData::loadFromDisk() {
    buffer->getMemoryManager()->getBufferManager()->getSpillerOrSkip(
        [&](auto& spiller) { spiller.loadFromDisk(*this); });
}

SpillResult ColumnChunkData::spillToDisk() {
    SpillResult spilled{};
    buffer->getMemoryManager()->getBufferManager()->getSpillerOrSkip(
        [&](auto& spiller) { spilled = spiller.spillToDisk(*this); });
    return spilled;
}

void ColumnChunkData::reclaimStorage(PageAllocator& pageAllocator) {
    if (nullData) {
        nullData->reclaimStorage(pageAllocator);
    }
    if (residencyState == ResidencyState::ON_DISK) {
        if (metadata.getStartPageIdx() != INVALID_PAGE_IDX) {
            pageAllocator.freePageRange(metadata.pageRange);
        }
    }
}

uint64_t ColumnChunkData::getSizeOnDisk() const {
    // Probably could just return the actual size from the metadata if it's on-disk, but it's not
    // currently needed for on-disk segments
    KU_ASSERT(ResidencyState::IN_MEMORY == residencyState);
    auto metadata = getMetadataToFlush();
    uint64_t nullSize = 0;
    if (nullData) {
        nullSize = nullData->getSizeOnDisk();
    }
    return metadata.getNumDataPages(dataType.getPhysicalType()) * common::LBUG_PAGE_SIZE + nullSize;
}

uint64_t ColumnChunkData::getSizeOnDiskInMemoryStats() const {
    // Probably could just return the actual size from the metadata if it's on-disk, but it's not
    // currently needed for on-disk segments
    KU_ASSERT(ResidencyState::IN_MEMORY == residencyState);
    uint64_t nullSize = 0;
    if (nullData) {
        nullSize = nullData->getSizeOnDiskInMemoryStats();
    }
    auto metadata = getMetadataFunction(buffer->getBuffer(), numValues,
        inMemoryStats.min.value_or(StorageValue{}), inMemoryStats.max.value_or(StorageValue{}));
    return metadata.getNumDataPages(dataType.getPhysicalType()) * common::LBUG_PAGE_SIZE + nullSize;
}

std::vector<std::unique_ptr<ColumnChunkData>> ColumnChunkData::split(bool targetMaxSize) const {
    // FIXME(bmwinger): we either need to split recursively, or detect individual values which bring
    // the size above MAX_SEGMENT_SIZE, since this will still sometimes produce segments larger than
    // MAX_SEGMENT_SIZE
    auto maxSegmentSize = std::max(getMinimumSizeOnDisk(), common::StorageConfig::MAX_SEGMENT_SIZE);
    auto targetSize =
        targetMaxSize ? maxSegmentSize : std::min(getSizeOnDisk() / 2, maxSegmentSize);
    std::vector<std::unique_ptr<ColumnChunkData>> newSegments;
    uint64_t pos = 0;
    const uint64_t chunkSize = 64;
    uint64_t initialCapacity = std::min(chunkSize, numValues);
    while (pos < numValues) {
        std::unique_ptr<ColumnChunkData> newSegment =
            ColumnChunkFactory::createColumnChunkData(getMemoryManager(), getDataType().copy(),
                isCompressionEnabled(), initialCapacity, ResidencyState::IN_MEMORY, hasNullData());

        while (pos < numValues && newSegment->getSizeOnDiskInMemoryStats() <= targetSize) {
            if (newSegment->getNumValues() == newSegment->getCapacity()) {
                newSegment->resize(newSegment->getCapacity() * 2);
            }
            auto numValuesToAppendInChunk = std::min(numValues - pos, chunkSize);
            newSegment->append(this, pos, numValuesToAppendInChunk);
            pos += numValuesToAppendInChunk;
        }
        if (pos < numValues && newSegment->getNumValues() > chunkSize) {
            // Size exceeded target size, so we should drop the last batch added (unless they are
            // the only values)
            pos -= chunkSize;
            newSegment->truncate(newSegment->getNumValues() - chunkSize);
        }
        newSegments.push_back(std::move(newSegment));
    }
    return newSegments;
}

ColumnChunkData::~ColumnChunkData() = default;

uint64_t ColumnChunkData::getMinimumSizeOnDisk() const {
    if (hasNullData() && nullData->getSizeOnDisk() > 0) {
        return 2 * LBUG_PAGE_SIZE;
    }
    return LBUG_PAGE_SIZE;
}

} // namespace storage
} // namespace lbug
