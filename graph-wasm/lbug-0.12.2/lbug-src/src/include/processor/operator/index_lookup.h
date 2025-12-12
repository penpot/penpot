#pragma once

#include "binder/expression/expression.h"
#include "expression_evaluator/expression_evaluator.h"
#include "processor/operator/persistent/batch_insert_error_handler.h"
#include "processor/operator/physical_operator.h"

namespace lbug {
namespace transaction {
class Transaction;
}
namespace storage {
class NodeTable;
} // namespace storage
namespace processor {

struct BatchInsertSharedState;
struct IndexLookupInfo {
    storage::NodeTable* nodeTable;
    std::unique_ptr<evaluator::ExpressionEvaluator> keyEvaluator;
    DataPos resultVectorPos;

    IndexLookupInfo(storage::NodeTable* nodeTable,
        std::unique_ptr<evaluator::ExpressionEvaluator> keyEvaluator,
        const DataPos& resultVectorPos)
        : nodeTable{nodeTable}, keyEvaluator{std::move(keyEvaluator)},
          resultVectorPos{resultVectorPos} {}
    EXPLICIT_COPY_DEFAULT_MOVE(IndexLookupInfo);

private:
    IndexLookupInfo(const IndexLookupInfo& other)
        : nodeTable{other.nodeTable}, keyEvaluator{other.keyEvaluator->copy()},
          resultVectorPos{other.resultVectorPos} {}
};

struct IndexLookupPrintInfo final : OPPrintInfo {
    binder::expression_vector expressions;
    explicit IndexLookupPrintInfo(binder::expression_vector expressions)
        : expressions{std::move(expressions)} {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<IndexLookupPrintInfo>(new IndexLookupPrintInfo(*this));
    }

private:
    IndexLookupPrintInfo(const IndexLookupPrintInfo& other)
        : OPPrintInfo{other}, expressions{other.expressions} {}
};

struct IndexLookupLocalState {
    explicit IndexLookupLocalState(std::unique_ptr<BatchInsertErrorHandler> errorHandler)
        : errorHandler(std::move(errorHandler)) {}

    std::unique_ptr<BatchInsertErrorHandler> errorHandler;
    std::vector<common::ValueVector*> warningDataVectors;
};

class IndexLookup final : public PhysicalOperator {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::INDEX_LOOKUP;

public:
    IndexLookup(std::vector<IndexLookupInfo> infos, std::vector<DataPos> warningDataVectorPos,
        std::unique_ptr<PhysicalOperator> child, common::idx_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{type_, std::move(child), id, std::move(printInfo)},
          infos{std::move(infos)}, warningDataVectorPos{std::move(warningDataVectorPos)} {}

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    bool getNextTuplesInternal(ExecutionContext* context) final;

    std::unique_ptr<PhysicalOperator> copy() final {
        return std::make_unique<IndexLookup>(copyVector(infos), warningDataVectorPos,
            children[0]->copy(), getOperatorID(), printInfo->copy());
    }

private:
    void lookup(transaction::Transaction* transaction, const IndexLookupInfo& info);

private:
    std::vector<IndexLookupInfo> infos;
    std::vector<DataPos> warningDataVectorPos;
    std::unique_ptr<IndexLookupLocalState> localState;
};

} // namespace processor
} // namespace lbug
