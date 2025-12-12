#pragma once

#include "database.h"

namespace lbug {
namespace storage {
class Table;
}

namespace main {

class ClientContext;
class LBUG_API StorageDriver {
public:
    explicit StorageDriver(Database* database);

    ~StorageDriver();

    void scan(const std::string& nodeName, const std::string& propertyName,
        common::offset_t* offsets, size_t numOffsets, uint8_t* result, size_t numThreads);

    // TODO: Should merge following two functions into a single one.
    uint64_t getNumNodes(const std::string& nodeName) const;
    uint64_t getNumRels(const std::string& relName) const;

private:
    void scanColumn(storage::Table* table, common::column_id_t columnID,
        const common::offset_t* offsets, size_t size, uint8_t* result) const;

private:
    std::unique_ptr<ClientContext> clientContext;
};

} // namespace main
} // namespace lbug
