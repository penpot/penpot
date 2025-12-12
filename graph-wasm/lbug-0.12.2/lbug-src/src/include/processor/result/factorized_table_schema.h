#pragma once

#include "common/assert.h"
#include "common/copy_constructors.h"
#include "common/types/types.h"

namespace lbug {
namespace processor {

// TODO(Guodong/Ziyi): Move these typedef to common and unify them with the ones without `ft_`.
typedef uint64_t ft_tuple_idx_t;
typedef uint32_t ft_col_idx_t;
typedef uint32_t ft_col_offset_t;
typedef uint32_t ft_block_idx_t;
typedef uint32_t ft_block_offset_t;

class ColumnSchema {
public:
    ColumnSchema(bool isUnFlat, common::idx_t groupID, uint32_t numBytes)
        : isUnFlat{isUnFlat}, groupID{groupID}, numBytes{numBytes}, mayContainNulls{false} {}
    EXPLICIT_COPY_DEFAULT_MOVE(ColumnSchema);

    bool isFlat() const { return !isUnFlat; }

    common::idx_t getGroupID() const { return groupID; }

    uint32_t getNumBytes() const { return numBytes; }

    bool operator==(const ColumnSchema& other) const {
        return isUnFlat == other.isUnFlat && groupID == other.groupID && numBytes == other.numBytes;
    }
    bool operator!=(const ColumnSchema& other) const { return !(*this == other); }

    void setMayContainsNullsToTrue() { mayContainNulls = true; }

    bool hasNoNullGuarantee() const { return !mayContainNulls; }

private:
    ColumnSchema(const ColumnSchema& other);

private:
    // This following two information can alternatively be maintained at table schema
    // level as a column group information.
    // Whether column is unFlat.
    bool isUnFlat;
    // Group id.
    common::idx_t groupID;
    // Num bytes of the column.
    uint32_t numBytes;
    // Whether column may contain nulls.
    // If this field is true, the column can still be all non-null.
    bool mayContainNulls;
};

class LBUG_API FactorizedTableSchema {
public:
    FactorizedTableSchema() = default;
    EXPLICIT_COPY_DEFAULT_MOVE(FactorizedTableSchema);

    void appendColumn(ColumnSchema column);

    const ColumnSchema* getColumn(ft_col_idx_t idx) const { return &columns[idx]; }

    uint32_t getNumColumns() const { return columns.size(); }

    ft_col_offset_t getNullMapOffset() const { return numBytesForDataPerTuple; }

    uint32_t getNumBytesPerTuple() const { return numBytesPerTuple; }

    ft_col_offset_t getColOffset(ft_col_idx_t idx) const { return colOffsets[idx]; }

    void setMayContainsNullsToTrue(ft_col_idx_t idx) {
        KU_ASSERT(idx < columns.size());
        columns[idx].setMayContainsNullsToTrue();
    }

    bool isEmpty() const { return columns.empty(); }

    bool operator==(const FactorizedTableSchema& other) const;
    bool operator!=(const FactorizedTableSchema& other) const { return !(*this == other); }

    uint64_t getNumFlatColumns() const;
    uint64_t getNumUnFlatColumns() const;

private:
    FactorizedTableSchema(const FactorizedTableSchema& other);

private:
    std::vector<ColumnSchema> columns;
    uint32_t numBytesForDataPerTuple = 0;
    uint32_t numBytesForNullMapPerTuple = 0;
    uint32_t numBytesPerTuple = 0;
    std::vector<ft_col_offset_t> colOffsets;
};

} // namespace processor
} // namespace lbug
