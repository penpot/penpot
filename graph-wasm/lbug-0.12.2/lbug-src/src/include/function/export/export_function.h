#pragma once

#include <atomic>

#include "common/case_insensitive_map.h"
#include "common/types/value/value.h"
#include "function/function.h"

namespace lbug {
namespace function {

struct ExportFuncLocalState {
    virtual ~ExportFuncLocalState() = default;

    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
};

struct ExportFuncBindData;

struct ExportFuncSharedState {
    virtual ~ExportFuncSharedState() = default;

    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }

    virtual void init(main::ClientContext& context, const ExportFuncBindData& bindData) = 0;

    std::atomic<bool> parallelFlag = true;
};

struct ExportFuncBindData {
    std::vector<std::string> columnNames;
    std::vector<common::LogicalType> types;
    std::string fileName;

    ExportFuncBindData(std::vector<std::string> columnNames, std::string fileName)
        : columnNames{std::move(columnNames)}, fileName{std::move(fileName)} {}

    virtual ~ExportFuncBindData() = default;

    void setDataType(std::vector<common::LogicalType> types_) { types = std::move(types_); }

    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }

    virtual std::unique_ptr<ExportFuncBindData> copy() const = 0;
};

struct ExportFuncBindInput {
    std::vector<std::string> columnNames;
    std::string filePath;
    common::case_insensitive_map_t<common::Value> parsingOptions;
};

using export_bind_t =
    std::function<std::unique_ptr<ExportFuncBindData>(function::ExportFuncBindInput& bindInput)>;
using export_init_local_t = std::function<std::unique_ptr<ExportFuncLocalState>(
    main::ClientContext&, const ExportFuncBindData&, std::vector<bool>)>;
using export_create_shared_t = std::function<std::shared_ptr<ExportFuncSharedState>()>;
using export_init_shared_t =
    std::function<void(ExportFuncSharedState&, main::ClientContext&, ExportFuncBindData&)>;
using export_sink_t = std::function<void(ExportFuncSharedState&, ExportFuncLocalState&,
    const ExportFuncBindData&, std::vector<std::shared_ptr<common::ValueVector>>)>;
using export_combine_t = std::function<void(ExportFuncSharedState&, ExportFuncLocalState&)>;
using export_finalize_t = std::function<void(ExportFuncSharedState&)>;

struct LBUG_API ExportFunction : public Function {
    ExportFunction() = default;
    explicit ExportFunction(std::string name) : Function{std::move(name), {}} {}

    ExportFunction(std::string name, export_init_local_t initLocal,
        export_create_shared_t createShared, export_init_shared_t initShared,
        export_sink_t copyToSink, export_combine_t copyToCombine, export_finalize_t copyToFinalize)
        : Function{std::move(name), {}}, initLocalState{std::move(initLocal)},
          createSharedState{std::move(createShared)}, initSharedState{std::move(initShared)},
          sink{std::move(copyToSink)}, combine{std::move(copyToCombine)},
          finalize{std::move(copyToFinalize)} {}

    export_bind_t bind;
    export_init_local_t initLocalState;
    export_create_shared_t createSharedState;
    export_init_shared_t initSharedState;
    export_sink_t sink;
    export_combine_t combine;
    export_finalize_t finalize;
};

struct ExportCSVFunction : public ExportFunction {
    static constexpr const char* name = "COPY_CSV";

    static function_set getFunctionSet();
};

struct ExportParquetFunction : public ExportFunction {
    static constexpr const char* name = "COPY_PARQUET";

    static function_set getFunctionSet();
};

} // namespace function
} // namespace lbug
