#include "storage/stats/table_stats.h"

#include "common/serializer/deserializer.h"
#include "common/serializer/serializer.h"

namespace lbug {
namespace storage {

TableStats::TableStats(std::span<const common::LogicalType> dataTypes) : cardinality{0} {
    for (const auto& dataType : dataTypes) {
        columnStats.emplace_back(dataType);
    }
}

TableStats::TableStats(const TableStats& other) : cardinality{other.cardinality} {
    columnStats.reserve(other.columnStats.size());
    for (auto i = 0u; i < other.columnStats.size(); ++i) {
        columnStats.emplace_back(other.columnStats[i].copy());
    }
}

void TableStats::update(const std::vector<common::ValueVector*>& vectors, size_t numColumns) {
    std::vector<common::column_id_t> dummyColumnIDs;
    for (auto i = 0u; i < vectors.size(); ++i) {
        dummyColumnIDs.push_back(i);
    }
    update(dummyColumnIDs, vectors, numColumns);
}

void TableStats::update(const std::vector<common::column_id_t>& columnIDs,
    const std::vector<common::ValueVector*>& vectors, size_t numColumns) {
    KU_ASSERT(columnIDs.size() == vectors.size());
    size_t numColumnsToUpdate = std::min(numColumns, vectors.size());

    for (auto i = 0u; i < numColumnsToUpdate; ++i) {
        auto columnID = columnIDs[i];
        KU_ASSERT(columnID < columnStats.size());
        columnStats[columnID].update(vectors[i]);
    }
    const auto numValues = vectors[0]->state->getSelVector().getSelSize();
    for (auto i = 1u; i < numColumnsToUpdate; ++i) {
        KU_ASSERT(vectors[i]->state->getSelVector().getSelSize() == numValues);
    }
    incrementCardinality(numValues);
}

void TableStats::serialize(common::Serializer& serializer) const {
    serializer.writeDebuggingInfo("cardinality");
    serializer.write(cardinality);
    serializer.writeDebuggingInfo("column_stats");
    serializer.serializeVector(columnStats);
}

TableStats TableStats::deserialize(common::Deserializer& deserializer) {
    std::string info;
    deserializer.validateDebuggingInfo(info, "cardinality");
    deserializer.deserializeValue<common::cardinality_t>(cardinality);
    deserializer.validateDebuggingInfo(info, "column_stats");
    deserializer.deserializeVector(columnStats);
    return *this;
}

} // namespace storage
} // namespace lbug
