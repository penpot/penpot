#include "graph/graph_entry.h"

#include "common/exception/runtime.h"

using namespace lbug::planner;
using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::catalog;

namespace lbug {
namespace graph {

NativeGraphEntry::NativeGraphEntry(std::vector<TableCatalogEntry*> nodeEntries,
    std::vector<TableCatalogEntry*> relEntries) {
    for (auto& entry : nodeEntries) {
        nodeInfos.emplace_back(entry);
    }
    for (auto& entry : relEntries) {
        relInfos.emplace_back(entry);
    }
}

std::vector<table_id_t> NativeGraphEntry::getNodeTableIDs() const {
    std::vector<table_id_t> result;
    for (auto& info : nodeInfos) {
        result.push_back(info.entry->getTableID());
    }
    return result;
}

std::vector<TableCatalogEntry*> NativeGraphEntry::getRelEntries() const {
    std::vector<TableCatalogEntry*> result;
    for (auto& info : relInfos) {
        result.push_back(info.entry);
    }
    return result;
}

std::vector<TableCatalogEntry*> NativeGraphEntry::getNodeEntries() const {
    std::vector<TableCatalogEntry*> result;
    for (auto& info : nodeInfos) {
        result.push_back(info.entry);
    }
    return result;
}

const NativeGraphEntryTableInfo& NativeGraphEntry::getRelInfo(table_id_t tableID) const {
    for (auto& info : relInfos) {
        if (info.entry->getTableID() == tableID) {
            return info;
        }
    }
    // LCOV_EXCL_START
    throw RuntimeException(stringFormat("Cannot find rel table with id {}", tableID));
    // LCOV_EXCL_STOP
}

} // namespace graph
} // namespace lbug
