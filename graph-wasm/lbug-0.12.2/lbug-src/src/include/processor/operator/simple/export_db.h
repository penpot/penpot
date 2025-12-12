#pragma once

#include "common/copier_config/file_scan_info.h"
#include "processor/operator/sink.h"

namespace lbug {
namespace processor {

struct ExportDBSharedState final {
    std::unordered_map<std::string, const std::atomic<bool>*> canUseParallelReader;
};

struct ExportDBPrintInfo final : OPPrintInfo {
    std::string filePath;
    common::case_insensitive_map_t<common::Value> options;

    ExportDBPrintInfo(std::string filePath, common::case_insensitive_map_t<common::Value> options)
        : filePath{std::move(filePath)}, options{std::move(options)} {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<ExportDBPrintInfo>(new ExportDBPrintInfo(*this));
    }

private:
    ExportDBPrintInfo(const ExportDBPrintInfo& other)
        : OPPrintInfo{other}, filePath{other.filePath}, options{other.options} {}
};

class ExportDB final : public SimpleSink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::EXPORT_DATABASE;

public:
    ExportDB(common::FileScanInfo boundFileInfo, bool schemaOnly,
        std::shared_ptr<FactorizedTable> messageTable, physical_op_id id,
        std::unique_ptr<OPPrintInfo> printInfo,
        std::shared_ptr<ExportDBSharedState> sharedState = std::make_shared<ExportDBSharedState>())
        : SimpleSink{type_, std::move(messageTable), id, std::move(printInfo)},
          boundFileInfo{std::move(boundFileInfo)}, schemaOnly{schemaOnly},
          sharedState{std::move(sharedState)} {}

    void executeInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<ExportDB>(boundFileInfo.copy(), schemaOnly, messageTable, id,
            printInfo->copy(), sharedState);
    }

    void addToParallelReaderMap(const std::string& file, const std::atomic<bool>& parallelFlag) {
        sharedState->canUseParallelReader.insert({file, &parallelFlag});
    }

private:
    common::FileScanInfo boundFileInfo;
    bool schemaOnly;
    std::shared_ptr<ExportDBSharedState> sharedState = nullptr;
};
} // namespace processor
} // namespace lbug
