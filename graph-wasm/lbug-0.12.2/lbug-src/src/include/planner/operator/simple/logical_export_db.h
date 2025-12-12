#pragma once

#include "common/copier_config/csv_reader_config.h"
#include "common/copier_config/file_scan_info.h"
#include "logical_simple.h"

namespace lbug {
namespace planner {

class LogicalExportDatabase final : public LogicalSimple {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::EXPORT_DATABASE;

public:
    LogicalExportDatabase(common::FileScanInfo boundFileInfo,
        const std::vector<std::shared_ptr<LogicalOperator>>& plans, bool exportSchemaOnly)
        : LogicalSimple{type_, plans}, boundFileInfo{std::move(boundFileInfo)},
          schemaOnly{exportSchemaOnly} {}

    std::string getFilePath() const { return boundFileInfo.filePaths[0]; }
    common::FileType getFileType() const { return boundFileInfo.fileTypeInfo.fileType; }
    common::CSVOption getCopyOption() const {
        auto csvConfig = common::CSVReaderConfig::construct(boundFileInfo.options);
        return csvConfig.option.copy();
    }
    const common::FileScanInfo* getBoundFileInfo() const { return &boundFileInfo; }
    std::string getExpressionsForPrinting() const override { return std::string{}; }

    bool isSchemaOnly() const { return schemaOnly; }

    std::unique_ptr<LogicalOperator> copy() override {
        return make_unique<LogicalExportDatabase>(boundFileInfo.copy(), copyVector(children),
            schemaOnly);
    }

private:
    common::FileScanInfo boundFileInfo;
    bool schemaOnly;
};

} // namespace planner
} // namespace lbug
