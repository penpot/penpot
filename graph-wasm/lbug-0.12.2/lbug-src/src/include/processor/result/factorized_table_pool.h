#pragma once

#include <mutex>
#include <stack>

#include "processor/result/factorized_table.h"

namespace lbug {
namespace processor {

// We implement a local ftable pool to avoid generate many small ftables when running GDS.
// Alternative solutions are directly writing to global ftable with partition so conflict is
// minimized. Or we optimize ftable to be more memory efficient when number of tuples is small.
class LBUG_API FactorizedTablePool {
public:
    explicit FactorizedTablePool(std::shared_ptr<FactorizedTable> globalTable)
        : globalTable{std::move(globalTable)} {}
    DELETE_COPY_AND_MOVE(FactorizedTablePool);

    FactorizedTable* claimLocalTable(storage::MemoryManager* mm);

    void returnLocalTable(FactorizedTable* table);

    void mergeLocalTables();

    std::shared_ptr<FactorizedTable> getGlobalTable() const { return globalTable; }

private:
    std::mutex mtx;
    std::shared_ptr<FactorizedTable> globalTable;
    std::stack<FactorizedTable*> availableLocalTables;
    std::vector<std::shared_ptr<FactorizedTable>> localTables;
};

} // namespace processor
} // namespace lbug