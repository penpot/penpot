#include "processor/operator/persistent/writer/parquet/struct_column_writer.h"

#include "common/constants.h"
#include "common/vector/value_vector.h"

namespace lbug {
namespace processor {

using namespace lbug_parquet::format;
using namespace lbug::common;

std::unique_ptr<ColumnWriterState> StructColumnWriter::initializeWriteState(
    lbug_parquet::format::RowGroup& rowGroup) {
    auto result = std::make_unique<StructColumnWriterState>(rowGroup, rowGroup.columns.size());

    result->childStates.reserve(childWriters.size());
    for (auto& child_writer : childWriters) {
        result->childStates.push_back(child_writer->initializeWriteState(rowGroup));
    }
    return result;
}

bool StructColumnWriter::hasAnalyze() {
    for (auto& child_writer : childWriters) {
        if (child_writer->hasAnalyze()) {
            return true;
        }
    }
    return false;
}

void StructColumnWriter::analyze(ColumnWriterState& state_p, ColumnWriterState* /*parent*/,
    ValueVector* vector, uint64_t count) {
    auto& state = reinterpret_cast<StructColumnWriterState&>(state_p);
    auto& childVectors = StructVector::getFieldVectors(vector);
    for (auto child_idx = 0u; child_idx < childWriters.size(); child_idx++) {
        // Need to check again. It might be that just one child needs it but the rest not
        if (childWriters[child_idx]->hasAnalyze()) {
            childWriters[child_idx]->analyze(*state.childStates[child_idx], &state_p,
                childVectors[child_idx].get(), count);
        }
    }
}

void StructColumnWriter::finalizeAnalyze(ColumnWriterState& state_p) {
    auto& state = reinterpret_cast<StructColumnWriterState&>(state_p);
    for (auto child_idx = 0u; child_idx < childWriters.size(); child_idx++) {
        // Need to check again. It might be that just one child needs it but the rest not
        if (childWriters[child_idx]->hasAnalyze()) {
            childWriters[child_idx]->finalizeAnalyze(*state.childStates[child_idx]);
        }
    }
}

void StructColumnWriter::prepare(ColumnWriterState& state_p, ColumnWriterState* parent,
    ValueVector* vector, uint64_t count) {
    auto& state = reinterpret_cast<StructColumnWriterState&>(state_p);
    if (parent) {
        // propagate empty entries from the parent
        while (state.isEmpty.size() < parent->isEmpty.size()) {
            state.isEmpty.push_back(parent->isEmpty[state.isEmpty.size()]);
        }
    }
    handleRepeatLevels(state_p, parent);
    handleDefineLevels(state_p, parent, vector, count, ParquetConstants::PARQUET_DEFINE_VALID,
        maxDefine - 1);
    auto& child_vectors = StructVector::getFieldVectors(vector);
    for (auto child_idx = 0u; child_idx < childWriters.size(); child_idx++) {
        childWriters[child_idx]->prepare(*state.childStates[child_idx], &state_p,
            child_vectors[child_idx].get(), count);
    }
}

void StructColumnWriter::beginWrite(ColumnWriterState& state_p) {
    auto& state = reinterpret_cast<StructColumnWriterState&>(state_p);
    for (auto child_idx = 0u; child_idx < childWriters.size(); child_idx++) {
        childWriters[child_idx]->beginWrite(*state.childStates[child_idx]);
    }
}

void StructColumnWriter::write(ColumnWriterState& state_p, ValueVector* vector, uint64_t count) {
    auto& state = reinterpret_cast<StructColumnWriterState&>(state_p);
    auto& child_vectors = StructVector::getFieldVectors(vector);
    for (auto child_idx = 0u; child_idx < childWriters.size(); child_idx++) {
        childWriters[child_idx]->write(*state.childStates[child_idx],
            child_vectors[child_idx].get(), count);
    }
}

void StructColumnWriter::finalizeWrite(ColumnWriterState& state_p) {
    auto& state = reinterpret_cast<StructColumnWriterState&>(state_p);
    for (auto child_idx = 0u; child_idx < childWriters.size(); child_idx++) {
        // we add the null count of the struct to the null count of the children
        childWriters[child_idx]->nullCount += nullCount;
        childWriters[child_idx]->finalizeWrite(*state.childStates[child_idx]);
    }
}

} // namespace processor
} // namespace lbug
