#pragma once

#include "binder/query/updating_clause/bound_set_info.h"
#include "planner/operator/logical_operator.h"
#include "planner/operator/persistent/logical_insert.h"

namespace lbug {
namespace planner {

class LogicalMerge final : public LogicalOperator {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::MERGE;

public:
    LogicalMerge(std::shared_ptr<binder::Expression> existenceMark, binder::expression_vector keys,
        std::shared_ptr<LogicalOperator> child)
        : LogicalOperator{type_, std::move(child)}, existenceMark{std::move(existenceMark)},
          keys{std::move(keys)} {}

    void computeFactorizedSchema() override;
    void computeFlatSchema() override;

    std::string getExpressionsForPrinting() const override { return {}; }

    f_group_pos_set getGroupsPosToFlatten();

    std::shared_ptr<binder::Expression> getExistenceMark() const { return existenceMark; }

    void addInsertNodeInfo(LogicalInsertInfo info) { insertNodeInfos.push_back(std::move(info)); }
    const std::vector<LogicalInsertInfo>& getInsertNodeInfos() const { return insertNodeInfos; }

    void addInsertRelInfo(LogicalInsertInfo info) { insertRelInfos.push_back(std::move(info)); }
    const std::vector<LogicalInsertInfo>& getInsertRelInfos() const { return insertRelInfos; }

    void addOnCreateSetNodeInfo(binder::BoundSetPropertyInfo info) {
        onCreateSetNodeInfos.push_back(std::move(info));
    }
    const std::vector<binder::BoundSetPropertyInfo>& getOnCreateSetNodeInfos() const {
        return onCreateSetNodeInfos;
    }

    void addOnCreateSetRelInfo(binder::BoundSetPropertyInfo info) {
        onCreateSetRelInfos.push_back(std::move(info));
    }
    const std::vector<binder::BoundSetPropertyInfo>& getOnCreateSetRelInfos() const {
        return onCreateSetRelInfos;
    }

    void addOnMatchSetNodeInfo(binder::BoundSetPropertyInfo info) {
        onMatchSetNodeInfos.push_back(std::move(info));
    }
    const std::vector<binder::BoundSetPropertyInfo>& getOnMatchSetNodeInfos() const {
        return onMatchSetNodeInfos;
    }

    void addOnMatchSetRelInfo(binder::BoundSetPropertyInfo info) {
        onMatchSetRelInfos.push_back(std::move(info));
    }
    const std::vector<binder::BoundSetPropertyInfo>& getOnMatchSetRelInfos() const {
        return onMatchSetRelInfos;
    }
    const binder::expression_vector& getKeys() const { return keys; }

    std::unique_ptr<LogicalOperator> copy() override;

private:
    std::shared_ptr<binder::Expression> existenceMark;
    // Create infos
    std::vector<LogicalInsertInfo> insertNodeInfos;
    std::vector<LogicalInsertInfo> insertRelInfos;
    // On Create infos
    std::vector<binder::BoundSetPropertyInfo> onCreateSetNodeInfos;
    std::vector<binder::BoundSetPropertyInfo> onCreateSetRelInfos;
    // On Match infos
    std::vector<binder::BoundSetPropertyInfo> onMatchSetNodeInfos;
    std::vector<binder::BoundSetPropertyInfo> onMatchSetRelInfos;
    // Key expressions used in merge hash table.
    // If a merge clause is taking input from previous query parts
    // E.g. UNWIND [1,1,3] AS x MERGE (n:N{id:x})
    // Since we don't re-evaluate the existence of n for each x, we need to create n only for
    // distinct x, i.e. 1 & 3. So there is a notion of key in MERGE.
    binder::expression_vector keys;
};

} // namespace planner
} // namespace lbug
