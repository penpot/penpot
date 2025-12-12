#pragma once

#include <mutex>

#include "common/data_chunk/data_chunk.h"
#include "common/mask.h"
#include "function/function.h"
#include "processor/operator/physical_operator.h"

namespace lbug {
namespace binder {
class BoundReadingClause;
}
namespace parser {
struct YieldVariable;
class ParsedExpression;
} // namespace parser

namespace planner {
class LogicalOperator;
class LogicalPlan;
class Planner;
} // namespace planner

namespace processor {
struct ExecutionContext;
class PlanMapper;
} // namespace processor

namespace function {

struct TableFuncBindInput;
struct TableFuncBindData;

// Shared state
struct LBUG_API TableFuncSharedState {
    common::row_idx_t numRows = 0;
    // This for now is only used for QueryHNSWIndex.
    // TODO(Guodong): This is not a good way to pass semiMasks to QueryHNSWIndex function.
    // However, to avoid function specific logic when we handle semi mask in mapper, so we can move
    // HNSW into an extension, we have to let semiMasks be owned by a base class.
    common::NodeOffsetMaskMap semiMasks;
    std::mutex mtx;

    explicit TableFuncSharedState() = default;
    explicit TableFuncSharedState(common::row_idx_t numRows) : numRows{numRows} {}
    virtual ~TableFuncSharedState() = default;
    virtual uint64_t getNumRows() const { return numRows; }

    common::table_id_map_t<common::SemiMask*> getSemiMasks() const { return semiMasks.getMasks(); }

    template<class TARGET>
    TARGET* ptrCast() {
        return common::ku_dynamic_cast<TARGET*>(this);
    }
};

// Local state
struct TableFuncLocalState {
    virtual ~TableFuncLocalState() = default;

    template<class TARGET>
    TARGET* ptrCast() {
        return common::ku_dynamic_cast<TARGET*>(this);
    }
};

// Execution input
struct TableFuncInput {
    TableFuncBindData* bindData;
    TableFuncLocalState* localState;
    TableFuncSharedState* sharedState;
    processor::ExecutionContext* context;

    TableFuncInput() = default;
    TableFuncInput(TableFuncBindData* bindData, TableFuncLocalState* localState,
        TableFuncSharedState* sharedState, processor::ExecutionContext* context)
        : bindData{bindData}, localState{localState}, sharedState{sharedState}, context{context} {}
    DELETE_COPY_DEFAULT_MOVE(TableFuncInput);
};

// Execution output.
// We might want to merge this with TableFuncLocalState. Also not all table function output vectors
// in a single dataChunk, e.g. FTableScan. In future, if we have more cases, we should consider
// make TableFuncOutput pure virtual.
struct TableFuncOutput {
    common::DataChunk dataChunk;

    explicit TableFuncOutput(common::DataChunk dataChunk) : dataChunk{std::move(dataChunk)} {}
    virtual ~TableFuncOutput() = default;

    void resetState();
    void setOutputSize(common::offset_t size) const;
};

struct LBUG_API TableFuncInitSharedStateInput final {
    TableFuncBindData* bindData;
    processor::ExecutionContext* context;

    TableFuncInitSharedStateInput(TableFuncBindData* bindData, processor::ExecutionContext* context)
        : bindData{bindData}, context{context} {}
};

// Init local state
struct TableFuncInitLocalStateInput {
    TableFuncSharedState& sharedState;
    TableFuncBindData& bindData;
    main::ClientContext* clientContext;

    TableFuncInitLocalStateInput(TableFuncSharedState& sharedState, TableFuncBindData& bindData,
        main::ClientContext* clientContext)
        : sharedState{sharedState}, bindData{bindData}, clientContext{clientContext} {}
};

// Init output
struct TableFuncInitOutputInput {
    std::vector<processor::DataPos> outColumnPositions;
    processor::ResultSet& resultSet;

    TableFuncInitOutputInput(std::vector<processor::DataPos> outColumnPositions,
        processor::ResultSet& resultSet)
        : outColumnPositions{std::move(outColumnPositions)}, resultSet{resultSet} {}
};

using table_func_bind_t = std::function<std::unique_ptr<TableFuncBindData>(main::ClientContext*,
    const TableFuncBindInput*)>;
using table_func_t =
    std::function<common::offset_t(const TableFuncInput&, TableFuncOutput& output)>;
using table_func_init_shared_t =
    std::function<std::shared_ptr<TableFuncSharedState>(const TableFuncInitSharedStateInput&)>;
using table_func_init_local_t =
    std::function<std::unique_ptr<TableFuncLocalState>(const TableFuncInitLocalStateInput&)>;
using table_func_init_output_t =
    std::function<std::unique_ptr<TableFuncOutput>(const TableFuncInitOutputInput&)>;
using table_func_can_parallel_t = std::function<bool()>;
using table_func_progress_t = std::function<double(TableFuncSharedState* sharedState)>;
using table_func_finalize_t =
    std::function<void(const processor::ExecutionContext*, TableFuncSharedState*)>;
using table_func_rewrite_t =
    std::function<std::string(main::ClientContext&, const TableFuncBindData& bindData)>;
using table_func_get_logical_plan_t =
    std::function<void(planner::Planner*, const binder::BoundReadingClause&,
        std::vector<std::shared_ptr<binder::Expression>>, planner::LogicalPlan&)>;
using table_func_get_physical_plan_t = std::function<std::unique_ptr<processor::PhysicalOperator>(
    processor::PlanMapper*, const planner::LogicalOperator*)>;
using table_func_infer_input_types =
    std::function<std::vector<common::LogicalType>(const binder::expression_vector&)>;

struct LBUG_API TableFunction final : Function {
    table_func_t tableFunc = nullptr;
    table_func_bind_t bindFunc = nullptr;
    table_func_init_shared_t initSharedStateFunc = nullptr;
    table_func_init_local_t initLocalStateFunc = nullptr;
    table_func_init_output_t initOutputFunc = nullptr;
    table_func_can_parallel_t canParallelFunc = [] { return true; };
    table_func_progress_t progressFunc = [](TableFuncSharedState*) { return 0.0; };
    table_func_finalize_t finalizeFunc = [](auto, auto) {};
    table_func_rewrite_t rewriteFunc = nullptr;
    table_func_get_logical_plan_t getLogicalPlanFunc = getLogicalPlan;
    table_func_get_physical_plan_t getPhysicalPlanFunc = getPhysicalPlan;
    table_func_infer_input_types inferInputTypes = nullptr;

    TableFunction() {}
    TableFunction(std::string name, std::vector<common::LogicalTypeID> inputTypes)
        : Function{std::move(name), std::move(inputTypes)} {}
    ~TableFunction() override;
    TableFunction(const TableFunction&) = default;
    TableFunction& operator=(const TableFunction& other) = default;
    DEFAULT_BOTH_MOVE(TableFunction);

    std::string signatureToString() const override {
        return common::LogicalTypeUtils::toString(parameterTypeIDs);
    }

    std::unique_ptr<TableFunction> copy() const { return std::make_unique<TableFunction>(*this); }

    // Init local state func
    static std::unique_ptr<TableFuncLocalState> initEmptyLocalState(
        const TableFuncInitLocalStateInput& input);
    // Init shared state func
    static std::unique_ptr<TableFuncSharedState> initEmptySharedState(
        const TableFuncInitSharedStateInput& input);
    // Init output func
    static std::unique_ptr<TableFuncOutput> initSingleDataChunkScanOutput(
        const TableFuncInitOutputInput& input);
    // Utility functions
    static std::vector<std::string> extractYieldVariables(const std::vector<std::string>& names,
        const std::vector<parser::YieldVariable>& yieldVariables);
    // Get logical plan func
    static void getLogicalPlan(planner::Planner* planner,
        const binder::BoundReadingClause& boundReadingClause, binder::expression_vector predicates,
        planner::LogicalPlan& plan);
    // Get physical plan func
    static std::unique_ptr<processor::PhysicalOperator> getPhysicalPlan(
        processor::PlanMapper* planMapper, const planner::LogicalOperator* logicalOp);
    // Table func
    static common::offset_t emptyTableFunc(const TableFuncInput& input, TableFuncOutput& output);
};

} // namespace function
} // namespace lbug
