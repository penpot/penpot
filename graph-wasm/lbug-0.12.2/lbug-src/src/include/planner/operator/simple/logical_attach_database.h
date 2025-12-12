#pragma once

#include "binder/bound_attach_info.h"
#include "logical_simple.h"

namespace lbug {
namespace planner {

struct LogicalAttachDatabasePrintInfo final : OPPrintInfo {
    std::string dbName;

    explicit LogicalAttachDatabasePrintInfo(std::string dbName) : dbName(std::move(dbName)) {}

    std::string toString() const override { return "Database: " + dbName; };

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<LogicalAttachDatabasePrintInfo>(
            new LogicalAttachDatabasePrintInfo(*this));
    }

private:
    LogicalAttachDatabasePrintInfo(const LogicalAttachDatabasePrintInfo& other)
        : OPPrintInfo(other), dbName(other.dbName) {}
};

class LogicalAttachDatabase final : public LogicalSimple {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::ATTACH_DATABASE;

public:
    explicit LogicalAttachDatabase(binder::AttachInfo attachInfo)
        : LogicalSimple{type_}, attachInfo{std::move(attachInfo)} {}

    binder::AttachInfo getAttachInfo() const { return attachInfo; }

    std::string getExpressionsForPrinting() const override { return attachInfo.dbPath; }

    std::unique_ptr<LogicalOperator> copy() override {
        return std::make_unique<LogicalAttachDatabase>(attachInfo);
    }

private:
    binder::AttachInfo attachInfo;
};

} // namespace planner
} // namespace lbug
