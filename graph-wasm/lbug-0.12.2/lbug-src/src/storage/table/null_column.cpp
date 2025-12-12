#include "storage/table/null_column.h"

#include "common/vector/value_vector.h"
#include "storage/buffer_manager/memory_manager.h"
#include "storage/compression/compression.h"
#include "storage/storage_utils.h"

using namespace lbug::common;
using namespace lbug::transaction;

namespace lbug {
namespace storage {

struct NullColumnFunc {
    static void readValuesFromPageToVector(const uint8_t* frame, PageCursor& pageCursor,
        ValueVector* resultVector, uint32_t posInVector, uint32_t numValuesToRead,
        const CompressionMetadata& metadata) {
        // Read bit-packed null flags from the frame into the result vector
        // Casting to uint64_t should be safe as long as the page size is a multiple of 8 bytes.
        // Otherwise, it could read off the end of the page.
        if (metadata.isConstant()) {
            bool value = false;
            ConstantCompression::decompressValues(reinterpret_cast<uint8_t*>(&value), 0 /*offset*/,
                1 /*numValues*/, PhysicalTypeID::BOOL, 1 /*numBytesPerValue*/, metadata);
            resultVector->setNullRange(posInVector, numValuesToRead, value);
        } else {
            resultVector->setNullFromBits(reinterpret_cast<const uint64_t*>(frame),
                pageCursor.elemPosInPage, posInVector, numValuesToRead);
        }
    }
};

NullColumn::NullColumn(const std::string& name, FileHandle* dataFH, MemoryManager* mm,
    ShadowFile* shadowFile, bool enableCompression)
    : Column{name, LogicalType::BOOL(), dataFH, mm, shadowFile, enableCompression,
          false /*requireNullColumn*/} {
    readToVectorFunc = NullColumnFunc::readValuesFromPageToVector;
}

} // namespace storage
} // namespace lbug
