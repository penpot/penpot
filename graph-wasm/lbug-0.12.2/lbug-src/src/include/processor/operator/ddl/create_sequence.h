#pragma once

#include "binder/ddl/bound_create_sequence_info.h"
#include "processor/operator/sink.h"

namespace lbug {
namespace processor {

struct CreateSequencePrintInfo final : OPPrintInfo {
    std::string seqName;

    explicit CreateSequencePrintInfo(std::string seqName) : seqName{std::move(seqName)} {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<CreateSequencePrintInfo>(new CreateSequencePrintInfo(*this));
    }

private:
    CreateSequencePrintInfo(const CreateSequencePrintInfo& other)
        : OPPrintInfo{other}, seqName{other.seqName} {}
};

class CreateSequence final : public SimpleSink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::CREATE_SEQUENCE;

public:
    CreateSequence(binder::BoundCreateSequenceInfo info,
        std::shared_ptr<FactorizedTable> messageTable, physical_op_id id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : SimpleSink{type_, std::move(messageTable), id, std::move(printInfo)},
          info{std::move(info)} {}

    void executeInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<CreateSequence>(info.copy(), messageTable, id, printInfo->copy());
    }

private:
    binder::BoundCreateSequenceInfo info;
};

} // namespace processor
} // namespace lbug
