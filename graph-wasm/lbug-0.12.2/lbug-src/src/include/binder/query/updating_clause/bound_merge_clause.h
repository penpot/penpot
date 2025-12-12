#pragma once

#include "binder/query/query_graph.h"
#include "bound_insert_info.h"
#include "bound_set_info.h"
#include "bound_updating_clause.h"

namespace lbug {
namespace binder {

class BoundMergeClause final : public BoundUpdatingClause {
    static constexpr common::ClauseType type_ = common::ClauseType::MERGE;

public:
    BoundMergeClause(expression_vector columnDataExprs, std::shared_ptr<Expression> existenceMark,
        std::shared_ptr<Expression> distinctMark, QueryGraphCollection queryGraphCollection,
        std::shared_ptr<Expression> predicate, std::vector<BoundInsertInfo> insertInfos)
        : BoundUpdatingClause{type_}, columnDataExprs{std::move(columnDataExprs)},
          existenceMark{std::move(existenceMark)}, distinctMark{std::move(distinctMark)},
          queryGraphCollection{std::move(queryGraphCollection)}, predicate{std::move(predicate)},
          insertInfos{std::move(insertInfos)} {}

    expression_vector getColumnDataExprs() const { return columnDataExprs; }

    std::shared_ptr<Expression> getExistenceMark() const { return existenceMark; }
    std::shared_ptr<Expression> getDistinctMark() const { return distinctMark; }

    const QueryGraphCollection* getQueryGraphCollection() const { return &queryGraphCollection; }
    bool hasPredicate() const { return predicate != nullptr; }
    std::shared_ptr<Expression> getPredicate() const { return predicate; }

    const std::vector<BoundInsertInfo>& getInsertInfosRef() const { return insertInfos; }
    const std::vector<BoundSetPropertyInfo>& getOnMatchSetInfosRef() const {
        return onMatchSetPropertyInfos;
    }
    const std::vector<BoundSetPropertyInfo>& getOnCreateSetInfosRef() const {
        return onCreateSetPropertyInfos;
    }

    bool hasInsertNodeInfo() const {
        return hasInsertInfo(
            [](const BoundInsertInfo& info) { return info.tableType == common::TableType::NODE; });
    }
    std::vector<const BoundInsertInfo*> getInsertNodeInfos() const {
        return getInsertInfos(
            [](const BoundInsertInfo& info) { return info.tableType == common::TableType::NODE; });
    }
    bool hasInsertRelInfo() const {
        return hasInsertInfo(
            [](const BoundInsertInfo& info) { return info.tableType == common::TableType::REL; });
    }
    std::vector<const BoundInsertInfo*> getInsertRelInfos() const {
        return getInsertInfos(
            [](const BoundInsertInfo& info) { return info.tableType == common::TableType::REL; });
    }

    bool hasOnMatchSetNodeInfo() const {
        return hasOnMatchSetInfo([](const BoundSetPropertyInfo& info) {
            return info.tableType == common::TableType::NODE;
        });
    }
    std::vector<BoundSetPropertyInfo> getOnMatchSetNodeInfos() const {
        return getOnMatchSetInfos([](const BoundSetPropertyInfo& info) {
            return info.tableType == common::TableType::NODE;
        });
    }
    bool hasOnMatchSetRelInfo() const {
        return hasOnMatchSetInfo([](const BoundSetPropertyInfo& info) {
            return info.tableType == common::TableType::REL;
        });
    }
    std::vector<BoundSetPropertyInfo> getOnMatchSetRelInfos() const {
        return getOnMatchSetInfos([](const BoundSetPropertyInfo& info) {
            return info.tableType == common::TableType::REL;
        });
    }

    bool hasOnCreateSetNodeInfo() const {
        return hasOnCreateSetInfo([](const BoundSetPropertyInfo& info) {
            return info.tableType == common::TableType::NODE;
        });
    }
    std::vector<BoundSetPropertyInfo> getOnCreateSetNodeInfos() const {
        return getOnCreateSetInfos([](const BoundSetPropertyInfo& info) {
            return info.tableType == common::TableType::NODE;
        });
    }
    bool hasOnCreateSetRelInfo() const {
        return hasOnCreateSetInfo([](const BoundSetPropertyInfo& info) {
            return info.tableType == common::TableType::REL;
        });
    }
    std::vector<BoundSetPropertyInfo> getOnCreateSetRelInfos() const {
        return getOnCreateSetInfos([](const BoundSetPropertyInfo& info) {
            return info.tableType == common::TableType::REL;
        });
    }

    void addOnMatchSetPropertyInfo(BoundSetPropertyInfo setPropertyInfo) {
        onMatchSetPropertyInfos.push_back(std::move(setPropertyInfo));
    }
    void addOnCreateSetPropertyInfo(BoundSetPropertyInfo setPropertyInfo) {
        onCreateSetPropertyInfos.push_back(std::move(setPropertyInfo));
    }

private:
    bool hasInsertInfo(const std::function<bool(const BoundInsertInfo& info)>& check) const;
    std::vector<const BoundInsertInfo*> getInsertInfos(
        const std::function<bool(const BoundInsertInfo& info)>& check) const;

    bool hasOnMatchSetInfo(
        const std::function<bool(const BoundSetPropertyInfo& info)>& check) const;
    std::vector<BoundSetPropertyInfo> getOnMatchSetInfos(
        const std::function<bool(const BoundSetPropertyInfo& info)>& check) const;

    bool hasOnCreateSetInfo(
        const std::function<bool(const BoundSetPropertyInfo& info)>& check) const;
    std::vector<BoundSetPropertyInfo> getOnCreateSetInfos(
        const std::function<bool(const BoundSetPropertyInfo& info)>& check) const;

private:
    // Capture user input column (right-hand-side) values in MERGE clause
    // E.g. UNWIND [1,2,3] AS x MERGE (a {id:2, rank:x})
    // this field should be {2, x}
    expression_vector columnDataExprs;
    // Internal marks
    std::shared_ptr<Expression> existenceMark;
    std::shared_ptr<Expression> distinctMark;
    // Pattern to match.
    QueryGraphCollection queryGraphCollection;
    std::shared_ptr<Expression> predicate;
    // Pattern to create on match failure.
    std::vector<BoundInsertInfo> insertInfos;
    // Update on match
    std::vector<BoundSetPropertyInfo> onMatchSetPropertyInfos;
    // Update on create
    std::vector<BoundSetPropertyInfo> onCreateSetPropertyInfos;
};

} // namespace binder
} // namespace lbug
