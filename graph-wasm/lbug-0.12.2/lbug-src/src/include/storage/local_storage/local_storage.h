#pragma once

#include <unordered_map>

#include "common/copy_constructors.h"
#include "storage/local_storage/local_table.h"
#include "storage/optimistic_allocator.h"

namespace lbug {
namespace main {
class ClientContext;
} // namespace main
namespace storage {
// Data structures in LocalStorage are not thread-safe.
// For now, we only support single thread insertions and updates. Once we optimize them with
// multiple threads, LocalStorage and its related data structures should be reworked to be
// thread-safe.
class LocalStorage {
public:
    explicit LocalStorage(main::ClientContext& clientContext) : clientContext{clientContext} {}
    DELETE_COPY_AND_MOVE(LocalStorage);

    // Do nothing if the table already exists, otherwise create a new local table.
    LocalTable* getOrCreateLocalTable(Table& table);
    // Return nullptr if no local table exists.
    LocalTable* getLocalTable(common::table_id_t tableID) const;

    PageAllocator* addOptimisticAllocator();

    void commit();
    void rollback();

private:
    main::ClientContext& clientContext;
    std::unordered_map<common::table_id_t, std::unique_ptr<LocalTable>> tables;

    // The mutex is only needed when working with the optimistic allocators
    std::mutex mtx;
    std::vector<std::unique_ptr<OptimisticAllocator>> optimisticAllocators;
};

} // namespace storage
} // namespace lbug
