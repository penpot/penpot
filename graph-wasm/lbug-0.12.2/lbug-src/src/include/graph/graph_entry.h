#pragma once

#include "catalog/catalog_entry/table_catalog_entry.h"
#include "common/copy_constructors.h"
#include "common/types/types.h"

namespace lbug {
namespace graph {

struct NativeGraphEntryTableInfo {
    catalog::TableCatalogEntry* entry;

    std::shared_ptr<binder::Expression> nodeOrRel;
    std::shared_ptr<binder::Expression> predicate;

    explicit NativeGraphEntryTableInfo(catalog::TableCatalogEntry* entry) : entry{entry} {}
    NativeGraphEntryTableInfo(catalog::TableCatalogEntry* entry,
        std::shared_ptr<binder::Expression> nodeOrRel,
        std::shared_ptr<binder::Expression> predicate)
        : entry{entry}, nodeOrRel{std::move(nodeOrRel)}, predicate{std::move(predicate)} {}
};

// Organize projected graph similar to CatalogEntry. When we want to share projected graph across
// statements, we need to migrate this class to catalog (or client context).
struct LBUG_API NativeGraphEntry {
    std::vector<NativeGraphEntryTableInfo> nodeInfos;
    std::vector<NativeGraphEntryTableInfo> relInfos;

    NativeGraphEntry() = default;
    NativeGraphEntry(std::vector<catalog::TableCatalogEntry*> nodeEntries,
        std::vector<catalog::TableCatalogEntry*> relEntries);
    EXPLICIT_COPY_DEFAULT_MOVE(NativeGraphEntry);

    bool isEmpty() const { return nodeInfos.empty() && relInfos.empty(); }

    std::vector<common::table_id_t> getNodeTableIDs() const;
    std::vector<catalog::TableCatalogEntry*> getRelEntries() const;
    std::vector<catalog::TableCatalogEntry*> getNodeEntries() const;

    const NativeGraphEntryTableInfo& getRelInfo(common::table_id_t tableID) const;

    void setRelPredicate(std::shared_ptr<binder::Expression> predicate);

private:
    NativeGraphEntry(const NativeGraphEntry& other)
        : nodeInfos{other.nodeInfos}, relInfos{other.relInfos} {}
};

} // namespace graph
} // namespace lbug
