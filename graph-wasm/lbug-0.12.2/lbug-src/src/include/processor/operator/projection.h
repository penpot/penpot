#pragma once

#include "binder/expression/expression.h"
#include "expression_evaluator/expression_evaluator.h"
#include "processor/operator/physical_operator.h"

namespace lbug {
namespace processor {

struct ProjectionPrintInfo final : OPPrintInfo {
    binder::expression_vector expressions;

    explicit ProjectionPrintInfo(binder::expression_vector expressions)
        : expressions{std::move(expressions)} {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<ProjectionPrintInfo>(new ProjectionPrintInfo(*this));
    }

private:
    ProjectionPrintInfo(const ProjectionPrintInfo& other)
        : OPPrintInfo{other}, expressions{other.expressions} {}
};

struct ProjectionInfo {
    std::vector<std::unique_ptr<evaluator::ExpressionEvaluator>> evaluators;
    std::vector<DataPos> exprsOutputPos;
    std::unordered_set<common::idx_t> activeChunkIndices;
    std::unordered_set<common::idx_t> discardedChunkIndices;

    ProjectionInfo() = default;
    EXPLICIT_COPY_DEFAULT_MOVE(ProjectionInfo);

    void addEvaluator(std::unique_ptr<evaluator::ExpressionEvaluator> evaluator,
        const DataPos& outputPos) {
        evaluators.push_back(std::move(evaluator));
        exprsOutputPos.push_back(outputPos);
        activeChunkIndices.insert(outputPos.dataChunkPos);
    }

private:
    ProjectionInfo(const ProjectionInfo& other)
        : evaluators{copyVector(other.evaluators)}, exprsOutputPos{other.exprsOutputPos},
          activeChunkIndices{other.activeChunkIndices},
          discardedChunkIndices{other.discardedChunkIndices} {}
};

class Projection final : public PhysicalOperator {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::PROJECTION;

public:
    Projection(ProjectionInfo info, std::unique_ptr<PhysicalOperator> child, physical_op_id id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator(type_, std::move(child), id, std::move(printInfo)),
          info{std::move(info)}, prevMultiplicity{1} {}

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    bool getNextTuplesInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<Projection>(info.copy(), children[0]->copy(), id,
            printInfo->copy());
    }

private:
    void saveMultiplicity() { prevMultiplicity = resultSet->multiplicity; }

    void restoreMultiplicity() { resultSet->multiplicity = prevMultiplicity; }

private:
    ProjectionInfo info;
    uint64_t prevMultiplicity;
};

} // namespace processor
} // namespace lbug
