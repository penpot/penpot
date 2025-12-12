#pragma once

#include "function/export/export_function.h"
#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

struct LogicalCopyToPrintInfo final : OPPrintInfo {
    std::vector<std::string> columnNames;
    std::string fileName;

    LogicalCopyToPrintInfo(std::vector<std::string> columnNames, std::string fileName)
        : columnNames(std::move(columnNames)), fileName(std::move(fileName)) {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<LogicalCopyToPrintInfo>(new LogicalCopyToPrintInfo(*this));
    }

private:
    LogicalCopyToPrintInfo(const LogicalCopyToPrintInfo& other)
        : OPPrintInfo(other), columnNames(other.columnNames), fileName(other.fileName) {}
};

class LogicalCopyTo final : public LogicalOperator {
public:
    LogicalCopyTo(std::unique_ptr<function::ExportFuncBindData> bindData,
        function::ExportFunction exportFunc, std::shared_ptr<LogicalOperator> child)
        : LogicalOperator{LogicalOperatorType::COPY_TO, std::move(child),
              std::optional<common::cardinality_t>(0)},
          bindData{std::move(bindData)}, exportFunc{std::move(exportFunc)} {}

    f_group_pos_set getGroupsPosToFlatten();

    std::string getExpressionsForPrinting() const override { return std::string{}; }

    void computeFactorizedSchema() override;
    void computeFlatSchema() override;

    std::unique_ptr<function::ExportFuncBindData> getBindData() const { return bindData->copy(); }
    function::ExportFunction getExportFunc() const { return exportFunc; };

    std::unique_ptr<OPPrintInfo> getPrintInfo() const override {
        return std::make_unique<LogicalCopyToPrintInfo>(bindData->columnNames, bindData->fileName);
    }

    std::unique_ptr<LogicalOperator> copy() override {
        return make_unique<LogicalCopyTo>(bindData->copy(), exportFunc, children[0]->copy());
    }

private:
    std::unique_ptr<function::ExportFuncBindData> bindData;
    function::ExportFunction exportFunc;
};

} // namespace planner
} // namespace lbug
