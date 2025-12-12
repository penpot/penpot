#pragma once

#include "processor/result/result_set.h"

namespace lbug {
namespace binder {
class Expression;
}
namespace evaluator {

struct EvaluatorLocalState {
    main::ClientContext* clientContext = nullptr;
};

enum class EvaluatorType : uint8_t {
    CASE_ELSE = 0,
    FUNCTION = 1,
    LAMBDA_PARAM = 2,
    LIST_LAMBDA = 3,
    LITERAL = 4,
    PATH = 5,
    NODE_REL = 6,
    REFERENCE = 8,
};

class ExpressionEvaluator;
using evaluator_vector_t = std::vector<std::unique_ptr<ExpressionEvaluator>>;

class ExpressionEvaluator {
public:
    explicit ExpressionEvaluator(EvaluatorType type, std::shared_ptr<binder::Expression> expression)
        : type{type}, expression{std::move(expression)} {};
    ExpressionEvaluator(EvaluatorType type, std::shared_ptr<binder::Expression> expression,
        bool isResultFlat)
        : type{type}, expression{std::move(expression)}, isResultFlat_{isResultFlat} {}
    ExpressionEvaluator(EvaluatorType type, std::shared_ptr<binder::Expression> expression,
        evaluator_vector_t children)
        : type{type}, expression{std::move(expression)}, children{std::move(children)} {}
    ExpressionEvaluator(const ExpressionEvaluator& other)
        : type{other.type}, expression{other.expression}, isResultFlat_{other.isResultFlat_},
          children{copyVector(other.children)} {}
    virtual ~ExpressionEvaluator() = default;

    EvaluatorType getEvaluatorType() const { return type; }

    std::shared_ptr<binder::Expression> getExpression() const { return expression; }
    bool isResultFlat() const { return isResultFlat_; }

    const evaluator_vector_t& getChildren() const { return children; }

    virtual void init(const processor::ResultSet& resultSet, main::ClientContext* clientContext);

    virtual void evaluate() = 0;
    // Evaluate and duplicate result for count times. This is a fast path we implemented for
    // bulk-insert when evaluate default values. A default value should be
    // - a constant (after folding); or
    // - a nextVal() function for serial column
    virtual void evaluate(common::sel_t count);

    bool select(common::SelectionVector& selVector, bool shouldSetSelVectorToFiltered);

    virtual std::unique_ptr<ExpressionEvaluator> copy() = 0;

    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }
    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
    template<class TARGET>
    TARGET* ptrCast() {
        return common::ku_dynamic_cast<TARGET*>(this);
    }

protected:
    virtual void resolveResultVector(const processor::ResultSet& resultSet,
        storage::MemoryManager* memoryManager) = 0;

    void resolveResultStateFromChildren(const std::vector<ExpressionEvaluator*>& inputEvaluators);

    virtual bool selectInternal(common::SelectionVector& selVector) = 0;

    bool updateSelectedPos(common::SelectionVector& selVector) const {
        auto& resultSelVector = resultVector->state->getSelVector();
        if (resultSelVector.getSelSize() > 1) {
            auto numSelectedValues = 0u;
            for (auto i = 0u; i < resultSelVector.getSelSize(); ++i) {
                auto pos = resultSelVector[i];
                auto selectedPosBuffer = selVector.getMutableBuffer();
                selectedPosBuffer[numSelectedValues] = pos;
                numSelectedValues +=
                    resultVector->isNull(pos) ? 0 : resultVector->getValue<bool>(pos);
            }
            selVector.setSelSize(numSelectedValues);
            return numSelectedValues > 0;
        } else {
            // If result state is flat (i.e. all children are flat), we shouldn't try to update
            // selectedPos because we don't know which one is leading, i.e. the one being selected
            // by filter.
            // So we forget about selectedPos and directly return true/false. This doesn't change
            // the correctness, because when all children are flat the check is done on tuple.
            auto pos = resultVector->state->getSelVector()[0];
            return resultVector->isNull(pos) ? 0 : resultVector->getValue<bool>(pos);
        }
    }

public:
    std::shared_ptr<common::ValueVector> resultVector;

protected:
    EvaluatorType type;
    std::shared_ptr<binder::Expression> expression;
    bool isResultFlat_ = true;
    evaluator_vector_t children;
    EvaluatorLocalState localState;
};

} // namespace evaluator
} // namespace lbug
