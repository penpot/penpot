#include "processor/result/factorized_table_schema.h"

#include "common/null_buffer.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

ColumnSchema::ColumnSchema(const ColumnSchema& other) {
    isUnFlat = other.isUnFlat;
    groupID = other.groupID;
    numBytes = other.numBytes;
    mayContainNulls = other.mayContainNulls;
}

FactorizedTableSchema::FactorizedTableSchema(const FactorizedTableSchema& other) {
    for (auto i = 0u; i < other.columns.size(); ++i) {
        appendColumn(other.columns[i].copy());
    }
}

void FactorizedTableSchema::appendColumn(ColumnSchema column) {
    numBytesForDataPerTuple += column.getNumBytes();
    columns.push_back(std::move(column));
    colOffsets.push_back(
        colOffsets.empty() ? 0 : colOffsets.back() + getColumn(columns.size() - 2)->getNumBytes());
    numBytesForNullMapPerTuple = NullBuffer::getNumBytesForNullValues(getNumColumns());
    numBytesPerTuple = numBytesForDataPerTuple + numBytesForNullMapPerTuple;
}

bool FactorizedTableSchema::operator==(const FactorizedTableSchema& other) const {
    if (columns.size() != other.columns.size()) {
        return false;
    }
    for (auto i = 0u; i < columns.size(); i++) {
        if (columns[i] != other.columns[i]) {
            return false;
        }
    }
    return numBytesForDataPerTuple == other.numBytesForDataPerTuple && numBytesForNullMapPerTuple &&
           other.numBytesForNullMapPerTuple;
}

uint64_t FactorizedTableSchema::getNumFlatColumns() const {
    auto numFlatColumns = 0u;
    for (auto& column : columns) {
        if (column.isFlat()) {
            numFlatColumns++;
        }
    }
    return numFlatColumns;
}

uint64_t FactorizedTableSchema::getNumUnFlatColumns() const {
    auto numUnflatColumns = 0u;
    for (auto& column : columns) {
        if (!column.isFlat()) {
            numUnflatColumns++;
        }
    }
    return numUnflatColumns;
}

} // namespace processor
} // namespace lbug
