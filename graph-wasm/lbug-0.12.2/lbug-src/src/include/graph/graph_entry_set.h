#pragma once

#include <memory>
#include <unordered_map>

#include "common/assert.h"
#include "parsed_graph_entry.h"

namespace lbug {
namespace main {
class ClientContext;
}
namespace graph {

class GraphEntrySet {
public:
    void validateGraphNotExist(const std::string& name) const;
    void validateGraphExist(const std::string& name) const;

    bool hasGraph(const std::string& name) const { return nameToEntry.contains(name); }
    ParsedGraphEntry* getEntry(const std::string& name) const {
        KU_ASSERT(hasGraph(name));
        return nameToEntry.at(name).get();
    }
    void addGraph(const std::string& name, std::unique_ptr<ParsedGraphEntry> entry) {
        nameToEntry.insert({name, std::move(entry)});
    }
    void dropGraph(const std::string& name) { nameToEntry.erase(name); }

    const std::unordered_map<std::string, std::unique_ptr<ParsedGraphEntry>>&
    getNameToEntryMap() const {
        return nameToEntry;
    }

    LBUG_API static GraphEntrySet* Get(const main::ClientContext& context);

private:
    std::unordered_map<std::string, std::unique_ptr<ParsedGraphEntry>> nameToEntry;
};

} // namespace graph
} // namespace lbug
