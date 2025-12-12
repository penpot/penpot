#include "processor/operator/persistent/reader/parquet/list_column_reader.h"

namespace lbug {
namespace processor {

ListColumnReader::ListColumnReader(ParquetReader& reader, common::LogicalType type,
    const lbug_parquet::format::SchemaElement& schema, uint64_t schemaIdx, uint64_t maxDefine,
    uint64_t maxRepeat, std::unique_ptr<ColumnReader> childColumnReader,
    storage::MemoryManager* memoryManager)
    : ColumnReader(reader, std::move(type), schema, schemaIdx, maxDefine, maxRepeat),
      childColumnReader(std::move(childColumnReader)), overflowChildCount(0) {
    childDefines.resize(common::DEFAULT_VECTOR_CAPACITY);
    childRepeats.resize(common::DEFAULT_VECTOR_CAPACITY);
    childDefinesPtr = (uint8_t*)childDefines.ptr;
    childRepeatsPtr = (uint8_t*)childRepeats.ptr;
    childFilter.set();
    vectorToRead = std::make_unique<common::ValueVector>(
        common::ListType::getChildType(this->type).copy(), memoryManager);
}

void ListColumnReader::applyPendingSkips(uint64_t numValues) {
    pendingSkips -= numValues;
    auto defineOut = std::unique_ptr<uint8_t[]>(new uint8_t[numValues]);
    auto repeatOut = std::unique_ptr<uint8_t[]>(new uint8_t[numValues]);
    uint64_t remaining = numValues;
    uint64_t numValuesRead = 0;
    while (remaining) {
        auto result_out = std::make_unique<common::ValueVector>(type.copy());
        parquet_filter_t filter;
        auto to_read = std::min<uint64_t>(remaining, common::DEFAULT_VECTOR_CAPACITY);
        numValuesRead += read(to_read, filter, defineOut.get(), repeatOut.get(), result_out.get());
        remaining -= to_read;
    }

    if (numValuesRead != numValues) {
        throw common::CopyException("Not all skips done!");
    }
}

uint64_t ListColumnReader::read(uint64_t numValues, parquet_filter_t& /*filter*/,
    uint8_t* defineOut, uint8_t* repeatOut, common::ValueVector* resultOut) {
    common::offset_t resultOffset = 0;
    auto resultPtr = reinterpret_cast<common::list_entry_t*>(resultOut->getData());

    if (pendingSkips > 0) {
        applyPendingSkips(pendingSkips);
    }

    // if an individual list is longer than STANDARD_VECTOR_SIZE we actually have to loop the child
    // read to fill it
    bool finished = false;
    while (!finished) {
        uint64_t childActualNumValues = 0;

        // check if we have any overflow from a previous read
        if (overflowChildCount == 0) {
            // we don't: read elements from the child reader
            childDefines.zero();
            childRepeats.zero();
            // we don't know in advance how many values to read because of the beautiful
            // repetition/definition setup we just read (up to) a vector from the child column, and
            // see if we have read enough if we have not read enough, we read another vector if we
            // have read enough, we leave any unhandled elements in the overflow vector for a
            // subsequent read
            auto childReqNumValues = std::min<uint64_t>(common::DEFAULT_VECTOR_CAPACITY,
                childColumnReader->getGroupRowsAvailable());
            childActualNumValues = childColumnReader->read(childReqNumValues, childFilter,
                childDefinesPtr, childRepeatsPtr, vectorToRead.get());
        } else {
            childActualNumValues = overflowChildCount;
            overflowChildCount = 0;
        }

        if (childActualNumValues == 0) {
            // no more elements available: we are done
            break;
        }
        auto currentChunkOffset = common::ListVector::getDataVectorSize(resultOut);

        // hard-won piece of code this, modify at your own risk
        // the intuition is that we have to only collapse values into lists that are repeated *on
        // this level* the rest is pretty much handed up as-is as a single-valued list or NULL
        uint64_t childIdx = 0;
        for (childIdx = 0; childIdx < childActualNumValues; childIdx++) {
            if (childRepeatsPtr[childIdx] == maxRepeat) {
                // value repeats on this level, append
                KU_ASSERT(resultOffset > 0);
                resultPtr[resultOffset - 1].size++;
                continue;
            }

            if (resultOffset >= numValues) {
                // we ran out of output space
                finished = true;
                break;
            }
            if (childDefinesPtr[childIdx] >= maxDefine) {
                resultOut->setNull(resultOffset, false);
                // value has been defined down the stack, hence its NOT NULL
                resultPtr[resultOffset].offset = childIdx + currentChunkOffset;
                resultPtr[resultOffset].size = 1;
            } else if (childDefinesPtr[childIdx] == maxDefine - 1) {
                resultOut->setNull(resultOffset, false);
                resultPtr[resultOffset].offset = childIdx + currentChunkOffset;
                resultPtr[resultOffset].size = 0;
            } else {
                resultOut->setNull(resultOffset, true);
                resultPtr[resultOffset].offset = 0;
                resultPtr[resultOffset].size = 0;
            }

            repeatOut[resultOffset] = childRepeatsPtr[childIdx];
            defineOut[resultOffset] = childDefinesPtr[childIdx];

            resultOffset++;
        }
        common::ListVector::appendDataVector(resultOut, vectorToRead.get(), childIdx);
        if (childIdx < childActualNumValues && resultOffset == numValues) {
            common::ListVector::sliceDataVector(vectorToRead.get(), childIdx, childActualNumValues);
            overflowChildCount = childActualNumValues - childIdx;
            for (auto repdefIdx = 0u; repdefIdx < overflowChildCount; repdefIdx++) {
                childDefinesPtr[repdefIdx] = childDefinesPtr[childIdx + repdefIdx];
                childRepeatsPtr[repdefIdx] = childRepeatsPtr[childIdx + repdefIdx];
            }
        }
    }
    return resultOffset;
}

} // namespace processor
} // namespace lbug
