#include "binder/expression/rel_expression.h"

#include "catalog/catalog_entry/rel_group_catalog_entry.h"
#include "catalog/catalog_entry/table_catalog_entry.h"
#include "common/enums/extend_direction_util.h"
#include "common/exception/binder.h"
#include "common/utils.h"

using namespace lbug::common;

namespace lbug {
namespace binder {

bool RelExpression::isMultiLabeled() const {
    if (entries.size() > 1) {
        return true;
    }
    for (auto& entry : entries) {
        auto relGroupEntry = entry->ptrCast<catalog::RelGroupCatalogEntry>();
        if (relGroupEntry->getNumRelTables() > 1) {
            return true;
        }
    }
    return false;
}

std::string RelExpression::detailsToString() const {
    std::string result = toString();
    switch (relType) {
    case QueryRelType::SHORTEST: {
        result += "SHORTEST";
    } break;
    case QueryRelType::ALL_SHORTEST: {
        result += "ALL SHORTEST";
    } break;
    case QueryRelType::WEIGHTED_SHORTEST: {
        result += "WEIGHTED SHORTEST";
    } break;
    case QueryRelType::ALL_WEIGHTED_SHORTEST: {
        result += "ALL WEIGHTED SHORTEST";
    } break;
    default:
        break;
    }
    if (QueryRelTypeUtils::isRecursive(relType)) {
        result += std::to_string(recursiveInfo->bindData->lowerBound);
        result += "..";
        result += std::to_string(recursiveInfo->bindData->upperBound);
    }
    return result;
}

std::vector<ExtendDirection> RelExpression::getExtendDirections() const {
    std::vector<ExtendDirection> ret;
    for (const auto direction : {ExtendDirection::FWD, ExtendDirection::BWD}) {
        const bool addDirection = std::all_of(entries.begin(), entries.end(),
            [direction](const catalog::TableCatalogEntry* tableEntry) {
                const auto* entry = tableEntry->constPtrCast<catalog::RelGroupCatalogEntry>();
                return common::containsValue(entry->getRelDataDirections(),
                    ExtendDirectionUtil::getRelDataDirection(direction));
            });
        if (addDirection) {
            ret.push_back(direction);
        }
    }
    if (ret.empty()) {
        throw BinderException(stringFormat(
            "There are no common storage directions among the rel "
            "tables matched by pattern '{}' (some tables have storage direction 'fwd' "
            "while others have storage direction 'bwd'). Scanning different tables matching the "
            "same pattern in different directions is currently unsupported.",
            toString()));
    }
    return ret;
}

std::vector<table_id_t> RelExpression::getInnerRelTableIDs() const {
    std::vector<table_id_t> innerTableIDs;
    for (auto& entry : entries) {
        for (auto& info : entry->cast<catalog::RelGroupCatalogEntry>().getRelEntryInfos()) {
            innerTableIDs.push_back(info.oid);
        }
    }
    return innerTableIDs;
}

} // namespace binder
} // namespace lbug
