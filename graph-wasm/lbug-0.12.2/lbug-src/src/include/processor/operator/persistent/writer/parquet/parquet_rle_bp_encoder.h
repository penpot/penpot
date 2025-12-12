#pragma once

#include "common/serializer/serializer.h"

namespace lbug {
namespace processor {

class RleBpEncoder {
public:
    explicit RleBpEncoder(uint32_t bitWidth);

public:
    // NOTE: Prepare is only required if a byte count is required BEFORE writing
    // This is the case with e.g. writing repetition/definition levels
    // If GetByteCount() is not required, prepare can be safely skipped.
    void beginPrepare(uint32_t firstValue);
    void prepareValue(uint32_t value);
    void finishPrepare();

    void beginWrite(uint32_t first_value);
    void writeValue(common::Serializer& writer, uint32_t value);
    void finishWrite(common::Serializer& writer);

    uint64_t getByteCount() const;

    static uint8_t getVarintSize(uint32_t val);

private:
    //! meta information
    uint32_t byteWidth;
    //! RLE run information
    uint64_t byteCount;
    uint64_t runCount;
    uint64_t currentRunCount;
    uint32_t lastValue;

private:
    void finishRun();
    void writeRun(common::Serializer& bufferedSerializer);
};

} // namespace processor
} // namespace lbug
