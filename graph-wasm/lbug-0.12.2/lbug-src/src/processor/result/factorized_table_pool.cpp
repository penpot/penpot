#include "processor/result/factorized_table_pool.h"

namespace lbug {
namespace processor {

FactorizedTable* FactorizedTablePool::claimLocalTable(storage::MemoryManager* mm) {
    std::unique_lock<std::mutex> lck{mtx};
    if (availableLocalTables.empty()) {
        auto table = std::make_shared<FactorizedTable>(mm, globalTable->getTableSchema()->copy());
        localTables.push_back(table);
        availableLocalTables.push(table.get());
    }
    auto result = availableLocalTables.top();
    availableLocalTables.pop();
    return result;
}

void FactorizedTablePool::returnLocalTable(FactorizedTable* table) {
    std::unique_lock<std::mutex> lck{mtx};
    availableLocalTables.push(table);
}

void FactorizedTablePool::mergeLocalTables() {
    std::unique_lock<std::mutex> lck{mtx};
    for (auto& localTable : localTables) {
        globalTable->merge(*localTable);
    }
}

} // namespace processor
} // namespace lbug
