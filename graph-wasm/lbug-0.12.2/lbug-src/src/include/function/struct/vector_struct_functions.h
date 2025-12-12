#pragma once

#include "common/vector/value_vector.h"
#include "function/function.h"

namespace lbug {
namespace function {

struct StructPackFunctions {
    static constexpr const char* name = "STRUCT_PACK";

    static function_set getFunctionSet();

    static void execFunc(const std::vector<std::shared_ptr<common::ValueVector>>& parameters,
        const std::vector<common::SelectionVector*>& parameterSelVectors,
        common::ValueVector& result, common::SelectionVector* resultSelVector,
        void* /*dataPtr*/ = nullptr);
    static void undirectedRelPackExecFunc(
        const std::vector<std::shared_ptr<common::ValueVector>>& parameters,
        common::ValueVector& result, void* /*dataPtr*/ = nullptr);
    static void compileFunc(FunctionBindData* bindData,
        const std::vector<std::shared_ptr<common::ValueVector>>& parameters,
        std::shared_ptr<common::ValueVector>& result);
    static void undirectedRelCompileFunc(FunctionBindData* bindData,
        const std::vector<std::shared_ptr<common::ValueVector>>& parameters,
        std::shared_ptr<common::ValueVector>& result);
};

struct StructExtractBindData : public FunctionBindData {
    common::idx_t childIdx;

    StructExtractBindData(common::LogicalType dataType, common::idx_t childIdx)
        : FunctionBindData{std::move(dataType)}, childIdx{childIdx} {}

    std::unique_ptr<FunctionBindData> copy() const override {
        return std::make_unique<StructExtractBindData>(resultType.copy(), childIdx);
    }
};

struct StructExtractFunctions {
    static constexpr const char* name = "STRUCT_EXTRACT";

    static function_set getFunctionSet();

    static std::unique_ptr<FunctionBindData> bindFunc(const ScalarBindFuncInput& input);

    static void compileFunc(FunctionBindData* bindData,
        const std::vector<std::shared_ptr<common::ValueVector>>& parameters,
        std::shared_ptr<common::ValueVector>& result);
};

struct KeysFunctions {
    static constexpr const char* name = "KEYS";

    static function_set getFunctionSet();
};

} // namespace function
} // namespace lbug
