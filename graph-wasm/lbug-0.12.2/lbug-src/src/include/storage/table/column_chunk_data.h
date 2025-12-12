#pragma once

#include <cstdint>
#include <functional>
#include <optional>
#include <variant>

#include "common/data_chunk/sel_vector.h"
#include "common/enums/rel_multiplicity.h"
#include "common/null_mask.h"
#include "common/system_config.h"
#include "common/types/types.h"
#include "common/vector/value_vector.h"
#include "storage/buffer_manager/memory_manager.h"
#include "storage/compression/compression.h"
#include "storage/enums/residency_state.h"
#include "storage/table/column_chunk_metadata.h"
#include "storage/table/column_chunk_stats.h"
#include "storage/table/in_memory_exception_chunk.h"

namespace lbug::storage {
class PageManager;
}
namespace lbug {
namespace evaluator {
class ExpressionEvaluator;
} // namespace evaluator

namespace transaction {
class Transaction;
} // namespace transaction

namespace storage {

class Column;
class NullChunkData;
class ColumnStats;
class PageAllocator;
class FileHandle;

// TODO(bmwinger): Hide access to variables.
struct SegmentState {
    const Column* column;
    ColumnChunkMetadata metadata;
    uint64_t numValuesPerPage = UINT64_MAX;
    std::unique_ptr<SegmentState> nullState;

    // Used for struct/list/string columns.
    std::vector<SegmentState> childrenStates;

    // Used for floating point columns
    std::variant<std::unique_ptr<InMemoryExceptionChunk<double>>,
        std::unique_ptr<InMemoryExceptionChunk<float>>>
        alpExceptionChunk;

    explicit SegmentState(bool hasNull = true) : column{nullptr} {
        if (hasNull) {
            nullState = std::make_unique<SegmentState>(false /*hasNull*/);
        }
    }
    SegmentState(ColumnChunkMetadata metadata, uint64_t numValuesPerPage)
        : column{nullptr}, metadata{std::move(metadata)}, numValuesPerPage{numValuesPerPage} {
        nullState = std::make_unique<SegmentState>(false /*hasNull*/);
    }

    SegmentState& getChildState(common::idx_t childIdx) {
        KU_ASSERT(childIdx < childrenStates.size());
        return childrenStates[childIdx];
    }
    const SegmentState& getChildState(common::idx_t childIdx) const {
        KU_ASSERT(childIdx < childrenStates.size());
        return childrenStates[childIdx];
    }

    template<std::floating_point T>
    InMemoryExceptionChunk<T>* getExceptionChunk() {
        using GetType = std::unique_ptr<InMemoryExceptionChunk<T>>;
        KU_ASSERT(std::holds_alternative<GetType>(alpExceptionChunk));
        return std::get<GetType>(alpExceptionChunk).get();
    }

    template<std::floating_point T>
    const InMemoryExceptionChunk<T>* getExceptionChunkConst() const {
        using GetType = std::unique_ptr<InMemoryExceptionChunk<T>>;
        KU_ASSERT(std::holds_alternative<GetType>(alpExceptionChunk));
        return std::get<GetType>(alpExceptionChunk).get();
    }

    void reclaimAllocatedPages(PageAllocator& pageAllocator) const;

    // Used by rangeSegments in column_chunk.h to provide the same interface as the segments stored
    // in ColumnChunk inside unique_ptr
    SegmentState& operator*() { return *this; }
    const SegmentState& operator*() const { return *this; }
    uint64_t getNumValues() const { return metadata.numValues; }
};

class Spiller;
// Base data segment covers all fixed-sized data types.
class LBUG_API ColumnChunkData {
public:
    friend struct ColumnChunkFactory;
    // For spilling to disk, we need access to the underlying buffer
    friend class Spiller;

    ColumnChunkData(MemoryManager& mm, common::LogicalType dataType, uint64_t capacity,
        bool enableCompression, ResidencyState residencyState, bool hasNullData,
        bool initializeToZero = true);
    ColumnChunkData(MemoryManager& mm, common::LogicalType dataType, bool enableCompression,
        const ColumnChunkMetadata& metadata, bool hasNullData, bool initializeToZero = true);
    ColumnChunkData(MemoryManager& mm, common::PhysicalTypeID physicalType, bool enableCompression,
        const ColumnChunkMetadata& metadata, bool hasNullData, bool initializeToZero = true);
    virtual ~ColumnChunkData();

    template<typename T>
    T getValue(common::offset_t pos) const {
        KU_ASSERT(pos < numValues);
        KU_ASSERT(residencyState != ResidencyState::ON_DISK);
        return getData<T>()[pos];
    }
    template<typename T>
    void setValue(T val, common::offset_t pos) {
        KU_ASSERT(pos < capacity);
        KU_ASSERT(residencyState != ResidencyState::ON_DISK);
        getData<T>()[pos] = val;
        if (pos >= numValues) {
            numValues = pos + 1;
        }
        if constexpr (StorageValueType<T>) {
            inMemoryStats.update(StorageValue{val}, dataType.getPhysicalType());
        }
    }

    virtual bool isNull(common::offset_t pos) const;
    void setNullData(std::unique_ptr<NullChunkData> nullData_) { nullData = std::move(nullData_); }
    bool hasNullData() const { return nullData != nullptr; }
    NullChunkData* getNullData() { return nullData.get(); }
    const NullChunkData* getNullData() const { return nullData.get(); }
    std::optional<common::NullMask> getNullMask() const;
    std::unique_ptr<NullChunkData> moveNullData() { return std::move(nullData); }

    common::LogicalType& getDataType() { return dataType; }
    const common::LogicalType& getDataType() const { return dataType; }
    ResidencyState getResidencyState() const { return residencyState; }
    bool isCompressionEnabled() const { return enableCompression; }
    ColumnChunkMetadata& getMetadata() {
        KU_ASSERT(residencyState == ResidencyState::ON_DISK);
        return metadata;
    }
    const ColumnChunkMetadata& getMetadata() const {
        KU_ASSERT(residencyState == ResidencyState::ON_DISK);
        return metadata;
    }
    void setMetadata(const ColumnChunkMetadata& metadata_) {
        KU_ASSERT(residencyState == ResidencyState::ON_DISK);
        metadata = metadata_;
    }

    // Only have side effects on in-memory or temporary chunks.
    virtual void resetToAllNull();
    virtual void resetToEmpty();

    // Note that the startPageIdx is not known, so it will always be common::INVALID_PAGE_IDX
    virtual ColumnChunkMetadata getMetadataToFlush() const;

    virtual void append(common::ValueVector* vector, const common::SelectionView& selView);
    virtual void append(const ColumnChunkData* other, common::offset_t startPosInOtherChunk,
        uint32_t numValuesToAppend);

    virtual void flush(PageAllocator& pageAllocator);

    ColumnChunkMetadata flushBuffer(PageAllocator& pageAllocator, const PageRange& entry,
        const ColumnChunkMetadata& metadata) const;

    static common::page_idx_t getNumPagesForBytes(uint64_t numBytes) {
        return (numBytes + common::LBUG_PAGE_SIZE - 1) / common::LBUG_PAGE_SIZE;
    }

    uint64_t getNumBytesPerValue() const { return numBytesPerValue; }
    uint8_t* getData() const;
    template<typename T>
    T* getData() const {
        return reinterpret_cast<T*>(getData());
    }
    uint64_t getBufferSize() const;

    virtual void initializeScanState(SegmentState& state, const Column* column) const;
    virtual void scan(common::ValueVector& output, common::offset_t offset, common::length_t length,
        common::sel_t posInOutputVector = 0) const;
    virtual void lookup(common::offset_t offsetInChunk, common::ValueVector& output,
        common::sel_t posInOutputVector) const;

    // TODO(Guodong): In general, this is not a good interface. Instead of passing in
    // `offsetInVector`, we should flatten the vector to pos at `offsetInVector`.
    virtual void write(const common::ValueVector* vector, common::offset_t offsetInVector,
        common::offset_t offsetInChunk);
    virtual void write(ColumnChunkData* chunk, ColumnChunkData* offsetsInChunk,
        common::RelMultiplicity multiplicity);
    virtual void write(const ColumnChunkData* srcChunk, common::offset_t srcOffsetInChunk,
        common::offset_t dstOffsetInChunk, common::offset_t numValuesToCopy);

    virtual void setToInMemory();
    // numValues must be at least the number of values the ColumnChunk was first initialized
    // with
    // reverse data and zero the part exceeding the original size
    virtual void resize(uint64_t newCapacity);
    // the opposite of the resize method, just simple resize
    virtual void resizeWithoutPreserve(uint64_t newCapacity);

    void populateWithDefaultVal(evaluator::ExpressionEvaluator& defaultEvaluator,
        uint64_t& numValues_, ColumnStats* newColumnStats);
    virtual void finalize() {
        KU_ASSERT(residencyState != ResidencyState::ON_DISK);
        // DO NOTHING.
    }

    uint64_t getCapacity() const { return capacity; }
    uint64_t getNumValues() const { return numValues; }
    // TODO(Guodong): Alternatively, we can let `getNumValues` read from metadata when ON_DISK.
    virtual void resetNumValuesFromMetadata();
    virtual void setNumValues(uint64_t numValues_);
    // Just to provide the same interface for handleAppendException
    inline void truncate(uint64_t numValues_) { setNumValues(numValues_); }
    virtual void syncNumValues() {}
    virtual bool numValuesSanityCheck() const;

    virtual bool sanityCheck() const;

    virtual uint64_t getEstimatedMemoryUsage() const;
    bool shouldSplit() const {
        // TODO(bmwinger): this should use the inMemoryStats to avoid scanning the data, however not
        // all functions update them
        return numValues > 1 && getSizeOnDisk() > std::max(getMinimumSizeOnDisk(),
                                                      common::StorageConfig::MAX_SEGMENT_SIZE);
    }
    const ColumnChunkStats& getInMemoryStats() const;

    // The minimum size is a function of the type's complexity and the page size
    // If the page size is large, or the type is very complex, this could be larger than the max
    // segment size (in which case we will treat the minimum size as the max segment size) E.g. if
    // LBUG_PAGE_SIZE == MAX_SEGMENT_SIZE, even a normal column with non-constant-compressed nulls
    // would have two pages and be detected as needing to split, even if the pages are nowhere near
    // full.
    //
    // TODO(bmwinger): This was added to work around the issue of complex nested types having a
    // larger initial size than the max segment size
    // It should ideally be removed
    virtual uint64_t getMinimumSizeOnDisk() const;
    virtual uint64_t getSizeOnDisk() const;
    // Not guaranteed to be accurate; not all functions keep the in memory statistics up to date!
    virtual uint64_t getSizeOnDiskInMemoryStats() const;

    virtual void serialize(common::Serializer& serializer) const;
    static std::unique_ptr<ColumnChunkData> deserialize(MemoryManager& mm,
        common::Deserializer& deSer);

    template<typename TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
    template<typename TARGET>
    const TARGET& cast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }
    MemoryManager& getMemoryManager() const;

    void loadFromDisk();
    SpillResult spillToDisk();

    MergedColumnChunkStats getMergedColumnChunkStats() const;

    void updateStats(const common::ValueVector* vector, const common::SelectionView& selVector);

    virtual void reclaimStorage(PageAllocator& pageAllocator);

    std::vector<std::unique_ptr<ColumnChunkData>> split(bool targetMaxSize = false) const;

protected:
    // Initializes the data buffer and functions. They are (and should be) only called in
    // constructor.
    void initializeBuffer(common::PhysicalTypeID physicalType, MemoryManager& mm,
        bool initializeToZero);
    void initializeFunction();

    // Note: This function is not setting child/null chunk data recursively.
    void setToOnDisk(const ColumnChunkMetadata& metadata);

    virtual void copyVectorToBuffer(common::ValueVector* vector, common::offset_t startPosInChunk,
        const common::SelectionView& selView);

    void resetInMemoryStats();

private:
    using flush_buffer_func_t = std::function<ColumnChunkMetadata(const std::span<uint8_t>,
        FileHandle*, const PageRange&, const ColumnChunkMetadata&)>;
    flush_buffer_func_t initializeFlushBufferFunction(
        std::shared_ptr<CompressionAlg> compression) const;
    uint64_t getBufferSize(uint64_t capacity_) const;

protected:
    using get_metadata_func_t = std::function<ColumnChunkMetadata(const std::span<uint8_t>,
        uint64_t, StorageValue, StorageValue)>;
    using get_min_max_func_t =
        std::function<std::pair<StorageValue, StorageValue>(const uint8_t*, uint64_t)>;

    ResidencyState residencyState;
    common::LogicalType dataType;
    bool enableCompression;
    uint32_t numBytesPerValue;
    uint64_t capacity;
    std::unique_ptr<MemoryBuffer> buffer;
    std::unique_ptr<NullChunkData> nullData;
    uint64_t numValues;
    flush_buffer_func_t flushBufferFunction;
    get_metadata_func_t getMetadataFunction;

    // On-disk metadata for column chunk.
    ColumnChunkMetadata metadata;

    // Stats for any in-memory updates applied to the column chunk
    // This will be merged with the on-disk metadata to get the overall stats
    ColumnChunkStats inMemoryStats;
};

template<>
inline void ColumnChunkData::setValue(bool val, common::offset_t pos) {
    KU_ASSERT(pos < capacity);
    KU_ASSERT(residencyState != ResidencyState::ON_DISK);
    // Buffer is rounded up to the nearest 8 bytes so that this cast is safe
    common::NullMask::setNull(getData<uint64_t>(), pos, val);
    if (pos >= numValues) {
        numValues = pos + 1;
    }
    inMemoryStats.update(StorageValue{val}, dataType.getPhysicalType());
}

template<>
inline bool ColumnChunkData::getValue(common::offset_t pos) const {
    // Buffer is rounded up to the nearest 8 bytes so that this cast is safe
    return common::NullMask::isNull(getData<uint64_t>(), pos);
}

// Stored as bitpacked booleans in-memory and on-disk
class BoolChunkData : public ColumnChunkData {
public:
    BoolChunkData(MemoryManager& mm, uint64_t capacity, bool enableCompression, ResidencyState type,
        bool hasNullChunk)
        : ColumnChunkData(mm, common::LogicalType::BOOL(), capacity,
              // Booleans are always bitpacked, but this can also enable constant compression
              enableCompression, type, hasNullChunk, true) {}
    BoolChunkData(MemoryManager& mm, bool enableCompression, const ColumnChunkMetadata& metadata,
        bool hasNullData)
        : ColumnChunkData{mm, common::LogicalType::BOOL(), enableCompression, metadata, hasNullData,
              true} {}

    void append(common::ValueVector* vector, const common::SelectionView& sel) final;
    void append(const ColumnChunkData* other, common::offset_t startPosInOtherChunk,
        uint32_t numValuesToAppend) override;

    void scan(common::ValueVector& output, common::offset_t offset, common::length_t length,
        common::sel_t posInOutputVector = 0) const override;
    void lookup(common::offset_t offsetInChunk, common::ValueVector& output,
        common::sel_t posInOutputVector) const override;

    void write(const common::ValueVector* vector, common::offset_t offsetInVector,
        common::offset_t offsetInChunk) override;
    void write(ColumnChunkData* chunk, ColumnChunkData* dstOffsets,
        common::RelMultiplicity multiplicity) final;
    void write(const ColumnChunkData* srcChunk, common::offset_t srcOffsetInChunk,
        common::offset_t dstOffsetInChunk, common::offset_t numValuesToCopy) override;
};

class NullChunkData final : public BoolChunkData {
public:
    NullChunkData(MemoryManager& mm, uint64_t capacity, bool enableCompression, ResidencyState type)
        : BoolChunkData(mm, capacity, enableCompression, type, false /*hasNullData*/) {}
    NullChunkData(MemoryManager& mm, bool enableCompression, const ColumnChunkMetadata& metadata)
        : BoolChunkData{mm, enableCompression, metadata, false /*hasNullData*/} {}

    // Maybe this should be combined with BoolChunkData if the only difference is these
    // functions?
    bool isNull(common::offset_t pos) const override { return getValue<bool>(pos); }
    void setNull(common::offset_t pos, bool isNull);

    bool noNullsGuaranteedInMem() const {
        return !inMemoryStats.max || !inMemoryStats.max->get<bool>();
    }
    bool allNullsGuaranteedInMem() const {
        return !inMemoryStats.min || inMemoryStats.min->get<bool>();
    }
    bool haveNoNullsGuaranteed() const;
    bool haveAllNullsGuaranteed() const;

    void resetToEmpty() override {
        memset(getData(), 0 /* non null */, getBufferSize());
        numValues = 0;
        inMemoryStats.min = inMemoryStats.max = std::nullopt;
    }
    void resetToNoNull() {
        memset(getData(), 0 /* non null */, getBufferSize());
        inMemoryStats.min = inMemoryStats.max = false;
    }
    void resetToAllNull() override {
        memset(getData(), 0xFF /* null */, getBufferSize());
        inMemoryStats.min = inMemoryStats.max = true;
    }

    void copyFromBuffer(const uint64_t* srcBuffer, uint64_t srcOffset, uint64_t dstOffset,
        uint64_t numBits) {
        KU_ASSERT(numBits > 0);
        common::NullMask::copyNullMask(srcBuffer, srcOffset, getData<uint64_t>(), dstOffset,
            numBits);
        auto [min, max] = common::NullMask::getMinMax(srcBuffer, srcOffset, numBits);
        if (!inMemoryStats.min.has_value() || min < inMemoryStats.min->get<bool>()) {
            inMemoryStats.min = min;
        }
        if (!inMemoryStats.max.has_value() || max > inMemoryStats.max->get<bool>()) {
            inMemoryStats.max = max;
        }
        if ((dstOffset + numBits) >= numValues) {
            numValues = dstOffset + numBits;
        }
    }

    // Appends the null data from the vector's null mask
    void appendNulls(const common::ValueVector* vector, const common::SelectionView& selView,
        common::offset_t startPosInChunk);

    // NullChunkData::scan updates the null mask of output vector
    void scan(common::ValueVector& output, common::offset_t offset, common::length_t length,
        common::sel_t posInOutputVector = 0) const override;

    void append(const ColumnChunkData* other, common::offset_t startPosInOtherChunk,
        uint32_t numValuesToAppend) override;

    void write(const common::ValueVector* vector, common::offset_t offsetInVector,
        common::offset_t offsetInChunk) override;
    void write(const ColumnChunkData* srcChunk, common::offset_t srcOffsetInChunk,
        common::offset_t dstOffsetInChunk, common::offset_t numValuesToCopy) override;

    void serialize(common::Serializer& serializer) const override;
    static std::unique_ptr<NullChunkData> deserialize(MemoryManager& mm,
        common::Deserializer& deSer);

    common::NullMask getNullMask() const;
};

class LBUG_API InternalIDChunkData final : public ColumnChunkData {
public:
    // TODO(Guodong): Should make InternalIDChunkData has no NULL.
    // Physically, we only materialize offset of INTERNAL_ID, which is same as UINT64,
    InternalIDChunkData(MemoryManager& mm, uint64_t capacity, bool enableCompression,
        ResidencyState residencyState)
        : ColumnChunkData(mm, common::LogicalType::INTERNAL_ID(), capacity, enableCompression,
              residencyState, false /*hasNullData*/),
          commonTableID{common::INVALID_TABLE_ID} {}
    InternalIDChunkData(MemoryManager& mm, bool enableCompression,
        const ColumnChunkMetadata& metadata)
        : ColumnChunkData{mm, common::LogicalType::INTERNAL_ID(), enableCompression, metadata,
              false /*hasNullData*/},
          commonTableID{common::INVALID_TABLE_ID} {}

    void append(common::ValueVector* vector, const common::SelectionView& selView) override;

    void copyVectorToBuffer(common::ValueVector* vector, common::offset_t startPosInChunk,
        const common::SelectionView& selView) override;

    void copyInt64VectorToBuffer(common::ValueVector* vector, common::offset_t startPosInChunk,
        const common::SelectionView& selView) const;

    void scan(common::ValueVector& output, common::offset_t offset, common::length_t length,
        common::sel_t posInOutputVector = 0) const override;
    void lookup(common::offset_t offsetInChunk, common::ValueVector& output,
        common::sel_t posInOutputVector) const override;

    void write(const common::ValueVector* vector, common::offset_t offsetInVector,
        common::offset_t offsetInChunk) override;

    void append(const ColumnChunkData* other, common::offset_t startPosInOtherChunk,
        uint32_t numValuesToAppend) override;

    void setTableID(common::table_id_t tableID) { commonTableID = tableID; }
    common::table_id_t getTableID() const { return commonTableID; }

    common::offset_t operator[](common::offset_t pos) const {
        return getValue<common::offset_t>(pos);
    }
    common::offset_t& operator[](common::offset_t pos) { return getData<common::offset_t>()[pos]; }

private:
    common::table_id_t commonTableID;
};

struct ColumnChunkFactory {
    static std::unique_ptr<ColumnChunkData> createColumnChunkData(MemoryManager& mm,
        common::LogicalType dataType, bool enableCompression, uint64_t capacity,
        ResidencyState residencyState, bool hasNullData = true, bool initializeToZero = true);
    static std::unique_ptr<ColumnChunkData> createColumnChunkData(MemoryManager& mm,
        common::LogicalType dataType, bool enableCompression, ColumnChunkMetadata& metadata,
        bool hasNullData, bool initializeToZero);

    static std::unique_ptr<ColumnChunkData> createNullChunkData(MemoryManager& mm,
        bool enableCompression, uint64_t capacity, ResidencyState type) {
        return std::make_unique<NullChunkData>(mm, capacity, enableCompression, type);
    }
};

} // namespace storage
} // namespace lbug
