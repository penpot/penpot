#pragma once

#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "common/enums/conflict_action.h"
#include "common/enums/table_type.h"
#include "parsed_property_definition.h"

namespace lbug {
namespace parser {

struct ExtraCreateTableInfo {
    virtual ~ExtraCreateTableInfo() = default;

    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }
};

struct CreateTableInfo {
    common::TableType type;
    std::string tableName;
    std::vector<ParsedPropertyDefinition> propertyDefinitions;
    std::unique_ptr<ExtraCreateTableInfo> extraInfo;
    common::ConflictAction onConflict;

    CreateTableInfo(common::TableType type, std::string tableName,
        common::ConflictAction onConflict)
        : type{type}, tableName{std::move(tableName)}, extraInfo{nullptr}, onConflict{onConflict} {}
    DELETE_COPY_DEFAULT_MOVE(CreateTableInfo);
};

struct ExtraCreateNodeTableInfo final : ExtraCreateTableInfo {
    std::string pKName;

    explicit ExtraCreateNodeTableInfo(std::string pKName) : pKName{std::move(pKName)} {}
};

struct ExtraCreateRelTableGroupInfo final : ExtraCreateTableInfo {
    std::string relMultiplicity;
    std::vector<std::pair<std::string, std::string>> srcDstTablePairs;
    options_t options;

    ExtraCreateRelTableGroupInfo(std::string relMultiplicity,
        std::vector<std::pair<std::string, std::string>> srcDstTablePairs, options_t options)
        : relMultiplicity{std::move(relMultiplicity)},
          srcDstTablePairs{std::move(srcDstTablePairs)}, options{std::move(options)} {}
};

} // namespace parser
} // namespace lbug
