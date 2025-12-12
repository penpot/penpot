#pragma once

#include "insert_executor.h"
#include "processor/operator/physical_operator.h"
#include "processor/result/pattern_creation_info_table.h"
#include "set_executor.h"

namespace lbug {
namespace processor {

struct MergeInfo {
    std::vector<std::unique_ptr<evaluator::ExpressionEvaluator>> keyEvaluators;
    FactorizedTableSchema tableSchema;
    common::executor_info executorInfo;
    DataPos existenceMark;

    MergeInfo(std::vector<std::unique_ptr<evaluator::ExpressionEvaluator>> keyEvaluators,
        FactorizedTableSchema tableSchema, common::executor_info executorInfo,
        DataPos existenceMark)
        : keyEvaluators{std::move(keyEvaluators)}, tableSchema{std::move(tableSchema)},
          executorInfo{std::move(executorInfo)}, existenceMark{existenceMark} {}
    EXPLICIT_COPY_DEFAULT_MOVE(MergeInfo);

private:
    MergeInfo(const MergeInfo& other)
        : keyEvaluators{copyVector(other.keyEvaluators)}, tableSchema{other.tableSchema.copy()},
          executorInfo{other.executorInfo}, existenceMark{other.existenceMark} {}
};

struct MergePrintInfo final : OPPrintInfo {
    binder::expression_vector pattern;
    std::vector<binder::expression_pair> onCreate;
    std::vector<binder::expression_pair> onMatch;

    MergePrintInfo(binder::expression_vector pattern, std::vector<binder::expression_pair> onCreate,
        std::vector<binder::expression_pair> onMatch)
        : pattern(std::move(pattern)), onCreate(std::move(onCreate)), onMatch(std::move(onMatch)) {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<MergePrintInfo>(new MergePrintInfo(*this));
    }

private:
    MergePrintInfo(const MergePrintInfo& other)
        : OPPrintInfo(other), pattern(other.pattern), onCreate(other.onCreate),
          onMatch(other.onMatch) {}
};

struct MergeLocalState {
    std::vector<common::ValueVector*> keyVectors;
    std::unique_ptr<PatternCreationInfoTable> hashTable;
    common::ValueVector* existenceVector = nullptr;

    void init(ResultSet& resultSet, main::ClientContext* context, MergeInfo& info);

    bool patternExists() const;

    PatternCreationInfo getPatternCreationInfo() const {
        return hashTable->getPatternCreationInfo(keyVectors);
    }
};

class Merge final : public PhysicalOperator {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::MERGE;

public:
    Merge(std::vector<NodeInsertExecutor> nodeInsertExecutors,
        std::vector<RelInsertExecutor> relInsertExecutors,
        std::vector<std::unique_ptr<NodeSetExecutor>> onCreateNodeSetExecutors,
        std::vector<std::unique_ptr<RelSetExecutor>> onCreateRelSetExecutors,
        std::vector<std::unique_ptr<NodeSetExecutor>> onMatchNodeSetExecutors,
        std::vector<std::unique_ptr<RelSetExecutor>> onMatchRelSetExecutors, MergeInfo info,
        std::unique_ptr<PhysicalOperator> child, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{type_, std::move(child), id, std::move(printInfo)},
          nodeInsertExecutors{std::move(nodeInsertExecutors)},
          relInsertExecutors{std::move(relInsertExecutors)},
          onCreateNodeSetExecutors{std::move(onCreateNodeSetExecutors)},
          onCreateRelSetExecutors{std::move(onCreateRelSetExecutors)},
          onMatchNodeSetExecutors{std::move(onMatchNodeSetExecutors)},
          onMatchRelSetExecutors{std::move(onMatchRelSetExecutors)}, info{std::move(info)} {}

    bool isParallel() const override { return false; }

    void initLocalStateInternal(ResultSet* resultSet_, ExecutionContext* context) override;

    bool getNextTuplesInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<Merge>(copyVector(nodeInsertExecutors),
            copyVector(relInsertExecutors), copyVector(onCreateNodeSetExecutors),
            copyVector(onCreateRelSetExecutors), copyVector(onMatchNodeSetExecutors),
            copyVector(onMatchRelSetExecutors), info.copy(), children[0]->copy(), id,
            printInfo->copy());
    }

private:
    void executeOnMatch(ExecutionContext* context);

    void executeOnCreatedPattern(PatternCreationInfo& info, ExecutionContext* context);

    void executeOnNewPattern(PatternCreationInfo& info, ExecutionContext* context);

    void executeNoMatch(ExecutionContext* context);

private:
    std::vector<NodeInsertExecutor> nodeInsertExecutors;
    std::vector<RelInsertExecutor> relInsertExecutors;

    std::vector<std::unique_ptr<NodeSetExecutor>> onCreateNodeSetExecutors;
    std::vector<std::unique_ptr<RelSetExecutor>> onCreateRelSetExecutors;

    std::vector<std::unique_ptr<NodeSetExecutor>> onMatchNodeSetExecutors;
    std::vector<std::unique_ptr<RelSetExecutor>> onMatchRelSetExecutors;

    MergeInfo info;
    MergeLocalState localState;
};

} // namespace processor
} // namespace lbug
