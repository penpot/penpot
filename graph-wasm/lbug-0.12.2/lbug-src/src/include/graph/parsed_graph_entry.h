#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "common/cast.h"

namespace lbug {
namespace graph {

enum class GraphEntryType : uint8_t {
    NATIVE = 0,
    CYPHER = 1,
};

struct GraphEntryTypeUtils {
    static std::string toString(GraphEntryType type);
};

struct LBUG_API ParsedGraphEntry {
    GraphEntryType type;

    explicit ParsedGraphEntry(GraphEntryType type) : type{type} {}
    virtual ~ParsedGraphEntry() = default;

    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
};

struct ParsedNativeGraphTableInfo {
    std::string tableName;
    std::string predicate;

    ParsedNativeGraphTableInfo(std::string tableName, std::string predicate)
        : tableName{std::move(tableName)}, predicate{std::move(predicate)} {}
};

struct LBUG_API ParsedNativeGraphEntry : ParsedGraphEntry {
    std::vector<ParsedNativeGraphTableInfo> nodeInfos;
    std::vector<ParsedNativeGraphTableInfo> relInfos;

    ParsedNativeGraphEntry(std::vector<ParsedNativeGraphTableInfo> nodeInfos,
        std::vector<ParsedNativeGraphTableInfo> relInfos)
        : ParsedGraphEntry{GraphEntryType::NATIVE}, nodeInfos{std::move(nodeInfos)},
          relInfos{std::move(relInfos)} {}
};

struct LBUG_API ParsedCypherGraphEntry : ParsedGraphEntry {
    std::string cypherQuery;

    explicit ParsedCypherGraphEntry(std::string cypherQuery)
        : ParsedGraphEntry{GraphEntryType::CYPHER}, cypherQuery{std::move(cypherQuery)} {}
};

} // namespace graph
} // namespace lbug
