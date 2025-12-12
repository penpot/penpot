#pragma once

#include "binder/expression/expression.h"
#include "expression_evaluator.h"

namespace lbug {
namespace main {
class ClientContext;
}

namespace evaluator {

class PathExpressionEvaluator final : public ExpressionEvaluator {
    static constexpr EvaluatorType type_ = EvaluatorType::PATH;

public:
    PathExpressionEvaluator(std::shared_ptr<binder::Expression> expression,
        evaluator_vector_t children)
        : ExpressionEvaluator{type_, std::move(expression), std::move(children)},
          resultNodesVector(nullptr), resultRelsVector(nullptr) {}

    void init(const processor::ResultSet& resultSet, main::ClientContext* clientContext) override;

    void evaluate() override;

    bool selectInternal(common::SelectionVector& /*selVector*/) override { KU_UNREACHABLE; }

    std::unique_ptr<ExpressionEvaluator> copy() override {
        return make_unique<PathExpressionEvaluator>(expression, copyVector(children));
    }

private:
    struct InputVectors {
        // input can either be NODE, REL or RECURSIVE_REL
        common::ValueVector* input = nullptr;
        // nodesInput is LIST[NODE] for RECURSIVE_REL input and nullptr otherwise
        common::ValueVector* nodesInput = nullptr;
        // nodesDataInput is NODE for RECURSIVE_REL and nullptr otherwise
        common::ValueVector* nodesDataInput = nullptr;
        // relsInput is LIST[REL] for RECURSIVE_REL input and nullptr otherwise
        common::ValueVector* relsInput = nullptr;
        // relsDataInput is REL for RECURSIVE_REL input and nullptr otherwise
        common::ValueVector* relsDataInput = nullptr;

        std::vector<common::ValueVector*> nodeFieldVectors;
        std::vector<common::ValueVector*> relFieldVectors;
    };

    void resolveResultVector(const processor::ResultSet& resultSet,
        storage::MemoryManager* memoryManager) override;

    void copyNodes(common::sel_t resultPos, bool isEmptyRels);
    uint64_t copyRels(common::sel_t resultPos);

    void copyFieldVectors(common::offset_t inputVectorPos,
        const std::vector<common::ValueVector*>& inputFieldVectors,
        common::offset_t& resultVectorPos,
        const std::vector<common::ValueVector*>& resultFieldVectors);

private:
    std::vector<std::unique_ptr<InputVectors>> inputVectorsPerChild;
    common::ValueVector* resultNodesVector; // LIST[NODE]
    common::ValueVector* resultRelsVector;  // LIST[REL]
    std::vector<common::ValueVector*> resultNodesFieldVectors;
    std::vector<common::ValueVector*> resultRelsFieldVectors;
};

} // namespace evaluator
} // namespace lbug
