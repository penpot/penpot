#pragma once

#include "planner/operator/simple/logical_simple.h"

namespace lbug {
namespace planner {

struct LogicalCreateTypePrintInfo final : OPPrintInfo {
    std::string typeName;
    std::string type;

    LogicalCreateTypePrintInfo(std::string typeName, std::string type)
        : typeName(std::move(typeName)), type(std::move(type)) {}

    std::string toString() const override { return typeName + " As " + type; };

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<LogicalCreateTypePrintInfo>(new LogicalCreateTypePrintInfo(*this));
    }

private:
    LogicalCreateTypePrintInfo(const LogicalCreateTypePrintInfo& other)
        : OPPrintInfo(other), typeName(other.typeName), type(other.type) {}
};

class LogicalCreateType : public LogicalSimple {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::CREATE_TYPE;

public:
    LogicalCreateType(std::string typeName, common::LogicalType type)
        : LogicalSimple{type_}, typeName{std::move(typeName)}, type{std::move(type)} {}

    std::string getExpressionsForPrinting() const override { return typeName; }

    const common::LogicalType& getType() const { return type; }

    std::unique_ptr<OPPrintInfo> getPrintInfo() const override {
        return std::make_unique<LogicalCreateTypePrintInfo>(typeName, type.toString());
    }

    std::unique_ptr<LogicalOperator> copy() final {
        return std::make_unique<LogicalCreateType>(typeName, type.copy());
    }

private:
    std::string typeName;
    common::LogicalType type;
};

} // namespace planner
} // namespace lbug
