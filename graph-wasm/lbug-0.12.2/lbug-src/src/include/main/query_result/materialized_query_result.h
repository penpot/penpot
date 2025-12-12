#pragma once

#include "main/query_result.h"

namespace lbug {
namespace processor {
class FactorizedTable;
class FactorizedTableIterator;
} // namespace processor

namespace main {

class MaterializedQueryResult : public QueryResult {
    static constexpr QueryResultType type_ = QueryResultType::FTABLE;

public:
    MaterializedQueryResult();
    LBUG_API explicit MaterializedQueryResult(std::shared_ptr<processor::FactorizedTable> table);
    MaterializedQueryResult(std::vector<std::string> columnNames,
        std::vector<common::LogicalType> columnTypes,
        std::shared_ptr<processor::FactorizedTable> table);
    ~MaterializedQueryResult() override;

    uint64_t getNumTuples() const override;

    bool hasNext() const override;

    std::shared_ptr<processor::FlatTuple> getNext() override;

    void resetIterator() override;

    std::string toString() const override;

    bool hasNextArrowChunk() override;

    std::unique_ptr<ArrowArray> getNextArrowChunk(int64_t chunkSize) override;

    const processor::FactorizedTable& getFactorizedTable() const { return *table; }

private:
    std::shared_ptr<processor::FactorizedTable> table;
    std::unique_ptr<processor::FactorizedTableIterator> iterator;
};

} // namespace main
} // namespace lbug
