#pragma once

#include "expression_evaluator/expression_evaluator.h"
#include "processor/operator/filtering_operator.h"
#include "processor/operator/physical_operator.h"

namespace lbug {
namespace processor {

struct FilterPrintInfo final : OPPrintInfo {
    std::shared_ptr<binder::Expression> expression;

    explicit FilterPrintInfo(std::shared_ptr<binder::Expression> expression)
        : expression{std::move(expression)} {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<FilterPrintInfo>(new FilterPrintInfo(*this));
    }

private:
    FilterPrintInfo(const FilterPrintInfo& other)
        : OPPrintInfo{other}, expression{other.expression} {}
};

class Filter final : public PhysicalOperator, public SelVectorOverWriter {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::FILTER;

public:
    Filter(std::unique_ptr<evaluator::ExpressionEvaluator> expressionEvaluator,
        uint32_t dataChunkToSelectPos, std::unique_ptr<PhysicalOperator> child, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{type_, std::move(child), id, std::move(printInfo)},
          expressionEvaluator{std::move(expressionEvaluator)},
          dataChunkToSelectPos(dataChunkToSelectPos) {}

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    bool getNextTuplesInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return make_unique<Filter>(expressionEvaluator->copy(), dataChunkToSelectPos,
            children[0]->copy(), id, printInfo->copy());
    }

private:
    std::unique_ptr<evaluator::ExpressionEvaluator> expressionEvaluator;
    uint32_t dataChunkToSelectPos;
    std::shared_ptr<common::DataChunkState> state;
};

struct NodeLabelFilterInfo {
    DataPos nodeVectorPos;
    std::unordered_set<common::table_id_t> nodeLabelSet;

    NodeLabelFilterInfo(const DataPos& nodeVectorPos,
        std::unordered_set<common::table_id_t> nodeLabelSet)
        : nodeVectorPos{nodeVectorPos}, nodeLabelSet{std::move(nodeLabelSet)} {}
    NodeLabelFilterInfo(const NodeLabelFilterInfo& other)
        : nodeVectorPos{other.nodeVectorPos}, nodeLabelSet{other.nodeLabelSet} {}

    std::unique_ptr<NodeLabelFilterInfo> copy() const {
        return std::make_unique<NodeLabelFilterInfo>(*this);
    }
};

class NodeLabelFiler final : public PhysicalOperator, public SelVectorOverWriter {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::FILTER;

public:
    NodeLabelFiler(std::unique_ptr<NodeLabelFilterInfo> info,
        std::unique_ptr<PhysicalOperator> child, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{type_, std::move(child), id, std::move(printInfo)},
          info{std::move(info)}, nodeIDVector{nullptr} {}

    void initLocalStateInternal(ResultSet* resultSet_, ExecutionContext* context) override;

    bool getNextTuplesInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() final {
        return std::make_unique<NodeLabelFiler>(info->copy(), children[0]->copy(), id,
            printInfo->copy());
    }

private:
    std::unique_ptr<NodeLabelFilterInfo> info;
    common::ValueVector* nodeIDVector;
};

} // namespace processor
} // namespace lbug
