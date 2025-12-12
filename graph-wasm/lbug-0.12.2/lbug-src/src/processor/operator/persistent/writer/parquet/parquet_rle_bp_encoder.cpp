#include "processor/operator/persistent/writer/parquet/parquet_rle_bp_encoder.h"

#include "common/assert.h"

namespace lbug {
namespace processor {

static void varintEncode(uint32_t val, common::Serializer& ser) {
    do {
        uint8_t byte = val & 127;
        val >>= 7;
        if (val != 0) {
            byte |= 128;
        }
        ser.write<uint8_t>(byte);
    } while (val != 0);
}

uint8_t RleBpEncoder::getVarintSize(uint32_t val) {
    uint8_t res = 0;
    do {
        val >>= 7;
        res++;
    } while (val != 0);
    return res;
}

RleBpEncoder::RleBpEncoder(uint32_t bit_width)
    : byteWidth((bit_width + 7) / 8), byteCount(uint64_t(-1)), runCount(uint64_t(-1)),
      currentRunCount(0), lastValue(0) {}

// we always RLE everything (for now)
void RleBpEncoder::beginPrepare(uint32_t first_value) {
    byteCount = 0;
    runCount = 1;
    currentRunCount = 1;
    lastValue = first_value;
}

void RleBpEncoder::finishRun() {
    // last value, or value has changed
    // write out the current run
    byteCount += getVarintSize(currentRunCount << 1) + byteWidth;
    currentRunCount = 1;
    runCount++;
}

void RleBpEncoder::prepareValue(uint32_t value) {
    if (value != lastValue) {
        finishRun();
        lastValue = value;
    } else {
        currentRunCount++;
    }
}

void RleBpEncoder::finishPrepare() {
    finishRun();
}

uint64_t RleBpEncoder::getByteCount() const {
    KU_ASSERT(byteCount != uint64_t(-1));
    return byteCount;
}

void RleBpEncoder::beginWrite(uint32_t first_value) {
    // start the RLE runs
    lastValue = first_value;
    currentRunCount = 1;
}

void RleBpEncoder::writeRun(common::Serializer& writer) {
    // write the header of the run
    varintEncode(currentRunCount << 1, writer);
    // now write the value
    KU_ASSERT(lastValue >> (byteWidth * 8) == 0);
    switch (byteWidth) {
    case 1:
        writer.write<uint8_t>(lastValue);
        break;
    case 2:
        writer.write<uint16_t>(lastValue);
        break;
    case 3:
        writer.write<uint8_t>(lastValue & 0xFF);
        writer.write<uint8_t>((lastValue >> 8) & 0xFF);
        writer.write<uint8_t>((lastValue >> 16) & 0xFF);
        break;
    case 4:
        writer.write<uint32_t>(lastValue);
        break;
    default:
        KU_UNREACHABLE;
    }
    currentRunCount = 1;
}

void RleBpEncoder::writeValue(common::Serializer& writer, uint32_t value) {
    if (value != lastValue) {
        writeRun(writer);
        lastValue = value;
    } else {
        currentRunCount++;
    }
}

void RleBpEncoder::finishWrite(common::Serializer& writer) {
    writeRun(writer);
}

} // namespace processor
} // namespace lbug
