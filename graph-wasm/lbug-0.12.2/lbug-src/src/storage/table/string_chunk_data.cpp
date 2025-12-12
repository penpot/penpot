#include "storage/table/string_chunk_data.h"

#include "common/data_chunk/sel_vector.h"
#include "common/serializer/deserializer.h"
#include "common/serializer/serializer.h"
#include "common/types/types.h"
#include "common/vector/value_vector.h"
#include "storage/buffer_manager/memory_manager.h"
#include "storage/table/column_chunk_data.h"
#include "storage/table/dictionary_chunk.h"
#include "storage/table/string_column.h"

using namespace lbug::common;

namespace lbug {
namespace storage {

StringChunkData::StringChunkData(MemoryManager& mm, LogicalType dataType, uint64_t capacity,
    bool enableCompression, ResidencyState residencyState)
    : ColumnChunkData{mm, std::move(dataType), capacity, enableCompression, residencyState,
          true /*hasNullData*/},
      indexColumnChunk{ColumnChunkFactory::createColumnChunkData(mm, LogicalType::UINT32(),
          enableCompression, capacity, residencyState, false /*hasNullData*/)},
      dictionaryChunk{
          std::make_unique<DictionaryChunk>(mm, capacity, enableCompression, residencyState)},
      needFinalize{false} {}

StringChunkData::StringChunkData(MemoryManager& mm, bool enableCompression,
    const ColumnChunkMetadata& metadata)
    : ColumnChunkData{mm, LogicalType::STRING(), enableCompression, metadata, true /*hasNullData*/},
      dictionaryChunk{
          std::make_unique<DictionaryChunk>(mm, 0, enableCompression, ResidencyState::IN_MEMORY)},
      needFinalize{false} {
    // create index chunk
    indexColumnChunk = ColumnChunkFactory::createColumnChunkData(mm, LogicalType::UINT32(),
        enableCompression, capacity, ResidencyState::ON_DISK);
}

ColumnChunkData* StringChunkData::getIndexColumnChunk() {
    return indexColumnChunk.get();
}

const ColumnChunkData* StringChunkData::getIndexColumnChunk() const {
    return indexColumnChunk.get();
}

void StringChunkData::updateNumValues(size_t newValue) {
    numValues = newValue;
    indexColumnChunk->setNumValues(newValue);
}

void StringChunkData::setToInMemory() {
    ColumnChunkData::setToInMemory();
    indexColumnChunk->setToInMemory();
    dictionaryChunk->setToInMemory();
}

void StringChunkData::resize(uint64_t newCapacity) {
    ColumnChunkData::resize(newCapacity);
    indexColumnChunk->resize(newCapacity);
}

void StringChunkData::resizeWithoutPreserve(uint64_t newCapacity) {
    ColumnChunkData::resizeWithoutPreserve(newCapacity);
    indexColumnChunk->resizeWithoutPreserve(newCapacity);
}

void StringChunkData::resetToEmpty() {
    ColumnChunkData::resetToEmpty();
    indexColumnChunk->resetToEmpty();
    dictionaryChunk->resetToEmpty();
}

void StringChunkData::append(ValueVector* vector, const SelectionView& selView) {
    selView.forEach([&](auto pos) {
        // index is stored in main chunk, data is stored in the data chunk
        KU_ASSERT(vector->dataType.getPhysicalType() == PhysicalTypeID::STRING);
        // index is stored in main chunk, data is stored in the data chunk
        nullData->setNull(numValues, vector->isNull(pos));
        auto dstPos = numValues;
        updateNumValues(numValues + 1);
        if (!vector->isNull(pos)) {
            auto kuString = vector->getValue<ku_string_t>(pos);
            setValueFromString(kuString.getAsStringView(), dstPos);
        }
    });
}

void StringChunkData::append(const ColumnChunkData* other, offset_t startPosInOtherChunk,
    uint32_t numValuesToAppend) {
    const auto& otherChunk = other->cast<StringChunkData>();
    nullData->append(otherChunk.getNullData(), startPosInOtherChunk, numValuesToAppend);
    switch (dataType.getLogicalTypeID()) {
    case LogicalTypeID::BLOB:
    case LogicalTypeID::STRING: {
        appendStringColumnChunk(&otherChunk, startPosInOtherChunk, numValuesToAppend);
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
}

void StringChunkData::scan(ValueVector& output, offset_t offset, length_t length,
    sel_t posInOutputVector) const {
    KU_ASSERT(offset + length <= numValues && nullData);
    nullData->scan(output, offset, length, posInOutputVector);
    if (!nullData->noNullsGuaranteedInMem()) {
        for (auto i = 0u; i < length; i++) {
            if (!nullData->isNull(offset + i)) {
                output.setValue<std::string_view>(posInOutputVector + i,
                    getValue<std::string_view>(offset + i));
            }
        }
    } else {
        for (auto i = 0u; i < length; i++) {
            output.setValue<std::string_view>(posInOutputVector + i,
                getValue<std::string_view>(offset + i));
        }
    }
}

void StringChunkData::lookup(offset_t offsetInChunk, ValueVector& output,
    sel_t posInOutputVector) const {
    KU_ASSERT(offsetInChunk < numValues);
    output.setNull(posInOutputVector, nullData->isNull(offsetInChunk));
    if (nullData->isNull(offsetInChunk)) {
        return;
    }
    auto str = getValue<std::string_view>(offsetInChunk);
    output.setValue<std::string_view>(posInOutputVector, str);
}

void StringChunkData::initializeScanState(SegmentState& state, const Column* column) const {
    ColumnChunkData::initializeScanState(state, column);

    auto* stringColumn = ku_dynamic_cast<const StringColumn*>(column);
    state.childrenStates.resize(CHILD_COLUMN_COUNT);
    indexColumnChunk->initializeScanState(state.childrenStates[INDEX_COLUMN_CHILD_READ_STATE_IDX],
        stringColumn->getIndexColumn());
    dictionaryChunk->getOffsetChunk()->initializeScanState(
        state.childrenStates[OFFSET_COLUMN_CHILD_READ_STATE_IDX],
        stringColumn->getDictionary().getOffsetColumn());
    dictionaryChunk->getStringDataChunk()->initializeScanState(
        state.childrenStates[DATA_COLUMN_CHILD_READ_STATE_IDX],
        stringColumn->getDictionary().getDataColumn());
}

void StringChunkData::write(const ValueVector* vector, offset_t offsetInVector,
    offset_t offsetInChunk) {
    KU_ASSERT(vector->dataType.getPhysicalType() == PhysicalTypeID::STRING);
    if (!needFinalize && offsetInChunk < numValues) [[unlikely]] {
        needFinalize = true;
    }
    nullData->setNull(offsetInChunk, vector->isNull(offsetInVector));
    if (offsetInChunk >= numValues) {
        updateNumValues(offsetInChunk + 1);
    }
    if (!vector->isNull(offsetInVector)) {
        auto kuStr = vector->getValue<ku_string_t>(offsetInVector);
        setValueFromString(kuStr.getAsStringView(), offsetInChunk);
    }
}

void StringChunkData::write(ColumnChunkData* chunk, ColumnChunkData* dstOffsets, RelMultiplicity) {
    KU_ASSERT(chunk->getDataType().getPhysicalType() == PhysicalTypeID::STRING &&
              dstOffsets->getDataType().getPhysicalType() == PhysicalTypeID::INTERNAL_ID &&
              chunk->getNumValues() == dstOffsets->getNumValues());
    auto& stringChunk = chunk->cast<StringChunkData>();
    for (auto i = 0u; i < chunk->getNumValues(); i++) {
        auto offsetInChunk = dstOffsets->getValue<offset_t>(i);
        if (!needFinalize && offsetInChunk < numValues) [[unlikely]] {
            needFinalize = true;
        }
        bool isNull = chunk->getNullData()->isNull(i);
        nullData->setNull(offsetInChunk, isNull);
        if (offsetInChunk >= numValues) {
            updateNumValues(offsetInChunk + 1);
        }
        if (!isNull) {
            setValueFromString(stringChunk.getValue<std::string_view>(i), offsetInChunk);
        }
    }
}

void StringChunkData::write(const ColumnChunkData* srcChunk, offset_t srcOffsetInChunk,
    offset_t dstOffsetInChunk, offset_t numValuesToCopy) {
    KU_ASSERT(srcChunk->getDataType().getPhysicalType() == PhysicalTypeID::STRING);
    if ((dstOffsetInChunk + numValuesToCopy) >= numValues) {
        updateNumValues(dstOffsetInChunk + numValuesToCopy);
    }
    auto& srcStringChunk = srcChunk->cast<StringChunkData>();
    for (auto i = 0u; i < numValuesToCopy; i++) {
        auto srcPos = srcOffsetInChunk + i;
        auto dstPos = dstOffsetInChunk + i;
        bool isNull = srcChunk->getNullData()->isNull(srcPos);
        nullData->setNull(dstPos, isNull);
        if (isNull) {
            continue;
        }
        setValueFromString(srcStringChunk.getValue<std::string_view>(srcPos), dstPos);
    }
}

void StringChunkData::appendStringColumnChunk(const StringChunkData* other,
    offset_t startPosInOtherChunk, uint32_t numValuesToAppend) {
    for (auto i = 0u; i < numValuesToAppend; i++) {
        auto posInChunk = numValues;
        auto posInOtherChunk = i + startPosInOtherChunk;
        updateNumValues(numValues + 1);
        if (nullData->isNull(posInChunk)) {
            indexColumnChunk->setValue<DictionaryChunk::string_index_t>(0, posInChunk);
            continue;
        }
        setValueFromString(other->getValue<std::string_view>(posInOtherChunk), posInChunk);
    }
}

void StringChunkData::setValueFromString(std::string_view value, uint64_t pos) {
    auto index = dictionaryChunk->appendString(value);
    indexColumnChunk->setValue<DictionaryChunk::string_index_t>(index, pos);
}

void StringChunkData::resetNumValuesFromMetadata() {
    ColumnChunkData::resetNumValuesFromMetadata();
    indexColumnChunk->resetNumValuesFromMetadata();
    dictionaryChunk->resetNumValuesFromMetadata();
}

void StringChunkData::finalize() {
    if (!needFinalize) {
        return;
    }
    // Prune unused entries in the dictionary before we flush
    // We already de-duplicate as we go, but when out of place updates occur new values will be
    // appended to the end and the original values may be able to be pruned before flushing them to
    // disk
    auto newDictionaryChunk = std::make_unique<DictionaryChunk>(getMemoryManager(), numValues,
        enableCompression, residencyState);
    // Each index is replaced by a new one for the de-duplicated data in the new dictionary.
    for (auto i = 0u; i < numValues; i++) {
        if (nullData->isNull(i)) {
            continue;
        }
        auto stringData = getValue<std::string_view>(i);
        auto index = newDictionaryChunk->appendString(stringData);
        indexColumnChunk->setValue<DictionaryChunk::string_index_t>(index, i);
    }
    dictionaryChunk = std::move(newDictionaryChunk);
}

void StringChunkData::flush(PageAllocator& pageAllocator) {
    ColumnChunkData::flush(pageAllocator);
    indexColumnChunk->flush(pageAllocator);
    dictionaryChunk->flush(pageAllocator);
}

void StringChunkData::reclaimStorage(PageAllocator& pageAllocator) {
    ColumnChunkData::reclaimStorage(pageAllocator);
    indexColumnChunk->reclaimStorage(pageAllocator);
    dictionaryChunk->getOffsetChunk()->reclaimStorage(pageAllocator);
    dictionaryChunk->getStringDataChunk()->reclaimStorage(pageAllocator);
}

uint64_t StringChunkData::getSizeOnDisk() const {
    return ColumnChunkData::getSizeOnDisk() + indexColumnChunk->getSizeOnDisk() +
           dictionaryChunk->getOffsetChunk()->getSizeOnDisk() +
           dictionaryChunk->getStringDataChunk()->getSizeOnDisk();
}
uint64_t StringChunkData::getMinimumSizeOnDisk() const {
    return ColumnChunkData::getMinimumSizeOnDisk() + indexColumnChunk->getMinimumSizeOnDisk() +
           dictionaryChunk->getOffsetChunk()->getMinimumSizeOnDisk() +
           dictionaryChunk->getStringDataChunk()->getMinimumSizeOnDisk();
}

uint64_t StringChunkData::getSizeOnDiskInMemoryStats() const {
    return ColumnChunkData::getSizeOnDiskInMemoryStats() +
           indexColumnChunk->getSizeOnDiskInMemoryStats() +
           dictionaryChunk->getOffsetChunk()->getSizeOnDiskInMemoryStats() +
           dictionaryChunk->getStringDataChunk()->getSizeOnDiskInMemoryStats();
}

uint64_t StringChunkData::getEstimatedMemoryUsage() const {
    return ColumnChunkData::getEstimatedMemoryUsage() + dictionaryChunk->getEstimatedMemoryUsage();
}

void StringChunkData::serialize(Serializer& serializer) const {
    ColumnChunkData::serialize(serializer);
    serializer.writeDebuggingInfo("index_column_chunk");
    indexColumnChunk->serialize(serializer);
    serializer.writeDebuggingInfo("dictionary_chunk");
    dictionaryChunk->serialize(serializer);
}

void StringChunkData::deserialize(Deserializer& deSer, ColumnChunkData& chunkData) {
    std::string key;
    deSer.validateDebuggingInfo(key, "index_column_chunk");
    chunkData.cast<StringChunkData>().indexColumnChunk =
        ColumnChunkData::deserialize(chunkData.getMemoryManager(), deSer);
    deSer.validateDebuggingInfo(key, "dictionary_chunk");
    chunkData.cast<StringChunkData>().dictionaryChunk =
        DictionaryChunk::deserialize(chunkData.getMemoryManager(), deSer);
}

template<>
ku_string_t StringChunkData::getValue<ku_string_t>(offset_t) const {
    KU_UNREACHABLE;
}

// STRING
template<>
std::string_view StringChunkData::getValue<std::string_view>(offset_t pos) const {
    KU_ASSERT(pos < numValues);
    KU_ASSERT(!nullData->isNull(pos));
    auto index = indexColumnChunk->getValue<DictionaryChunk::string_index_t>(pos);
    return dictionaryChunk->getString(index);
}

template<>
std::string StringChunkData::getValue<std::string>(offset_t pos) const {
    return std::string(getValue<std::string_view>(pos));
}

} // namespace storage
} // namespace lbug
