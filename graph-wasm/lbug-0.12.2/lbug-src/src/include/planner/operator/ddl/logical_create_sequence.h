#pragma once

#include "binder/ddl/bound_create_sequence_info.h"
#include "planner/operator/simple/logical_simple.h"

namespace lbug {
namespace planner {

struct LogicalCreateSequencePrintInfo final : OPPrintInfo {
    std::string sequenceName;

    explicit LogicalCreateSequencePrintInfo(std::string sequenceName)
        : sequenceName(std::move(sequenceName)) {}

    std::string toString() const override { return "Sequence: " + sequenceName; };

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<LogicalCreateSequencePrintInfo>(
            new LogicalCreateSequencePrintInfo(*this));
    }

private:
    LogicalCreateSequencePrintInfo(const LogicalCreateSequencePrintInfo& other)
        : OPPrintInfo(other), sequenceName(other.sequenceName) {}
};

class LogicalCreateSequence : public LogicalSimple {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::CREATE_SEQUENCE;

public:
    explicit LogicalCreateSequence(binder::BoundCreateSequenceInfo info)
        : LogicalSimple{type_}, info{std::move(info)} {}

    std::string getExpressionsForPrinting() const override { return info.sequenceName; }

    binder::BoundCreateSequenceInfo getInfo() const { return info.copy(); }

    std::unique_ptr<OPPrintInfo> getPrintInfo() const override {
        return std::make_unique<LogicalCreateSequencePrintInfo>(info.sequenceName);
    }

    std::unique_ptr<LogicalOperator> copy() final {
        return std::make_unique<LogicalCreateSequence>(info.copy());
    }

private:
    binder::BoundCreateSequenceInfo info;
};

} // namespace planner
} // namespace lbug
