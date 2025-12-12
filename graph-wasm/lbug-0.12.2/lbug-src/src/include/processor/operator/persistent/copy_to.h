#pragma once

#include <filesystem>

#include "function/export/export_function.h"
#include "processor/operator/sink.h"
#include "processor/result/result_set.h"

namespace lbug {
namespace processor {

struct CopyToInfo {
    function::ExportFunction exportFunc;
    std::unique_ptr<function::ExportFuncBindData> bindData;
    std::vector<DataPos> inputVectorPoses;
    std::vector<bool> isFlatVec;

    CopyToInfo(function::ExportFunction exportFunc,
        std::unique_ptr<function::ExportFuncBindData> bindData,
        std::vector<DataPos> inputVectorPoses, std::vector<bool> isFlatVec)
        : exportFunc{std::move(exportFunc)}, bindData{std::move(bindData)},
          inputVectorPoses{std::move(inputVectorPoses)}, isFlatVec{std::move(isFlatVec)} {}

    CopyToInfo copy() const {
        return CopyToInfo{exportFunc, bindData->copy(), inputVectorPoses, isFlatVec};
    }
};

struct CopyToLocalState {
    std::unique_ptr<function::ExportFuncLocalState> exportFuncLocalState;
    std::vector<std::shared_ptr<common::ValueVector>> inputVectors;
};

struct CopyToPrintInfo final : OPPrintInfo {
    std::vector<std::string> columnNames;
    std::string fileName;

    CopyToPrintInfo(std::vector<std::string> columnNames, std::string fileName)
        : columnNames{std::move(columnNames)}, fileName{std::move(fileName)} {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<CopyToPrintInfo>(new CopyToPrintInfo(*this));
    }

private:
    CopyToPrintInfo(const CopyToPrintInfo& other)
        : OPPrintInfo{other}, columnNames{other.columnNames}, fileName{other.fileName} {}
};

class CopyTo final : public Sink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::COPY_TO;

public:
    CopyTo(CopyToInfo info, std::shared_ptr<function::ExportFuncSharedState> sharedState,
        std::unique_ptr<PhysicalOperator> child, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : Sink{type_, std::move(child), id, std::move(printInfo)}, info{std::move(info)},
          sharedState{std::move(sharedState)} {}

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    void initGlobalStateInternal(ExecutionContext* context) override;

    void finalize(ExecutionContext* context) override;

    void executeInternal(ExecutionContext* context) override;

    std::pair<std::string, std::atomic<bool>&> getParallelFlag() {
        return {std::filesystem::path(info.bindData->fileName).filename().string(),
            sharedState->parallelFlag};
    }

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<CopyTo>(info.copy(), sharedState, children[0]->copy(), id,
            printInfo->copy());
    }

private:
    CopyToInfo info;
    CopyToLocalState localState;
    std::shared_ptr<function::ExportFuncSharedState> sharedState;
};

} // namespace processor
} // namespace lbug
