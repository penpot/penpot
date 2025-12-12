#pragma once

#include "binder/expression/node_expression.h"
#include "common/mask.h"
#include "function/table/bind_data.h"
#include "graph/graph.h"
#include "graph/graph_entry.h"
#include "graph/parsed_graph_entry.h"
#include "processor/result/factorized_table_pool.h"

namespace lbug {

namespace main {
class ClientContext;
}

namespace function {

struct LBUG_API GDSConfig {
    virtual ~GDSConfig() = default;

    template<class TARGET>
    const TARGET& constCast() const {
        return *common::ku_dynamic_cast<const TARGET*>(this);
    }
};

struct LBUG_API GDSBindData : public TableFuncBindData {
    graph::NativeGraphEntry graphEntry;
    binder::expression_vector output;

    GDSBindData(binder::expression_vector columns, graph::NativeGraphEntry graphEntry,
        binder::expression_vector output)
        : TableFuncBindData{std::move(columns)}, graphEntry{graphEntry.copy()},
          output{std::move(output)} {}

    GDSBindData(const GDSBindData& other)
        : TableFuncBindData{other}, graphEntry{other.graphEntry.copy()}, output{other.output},
          resultTable{other.resultTable} {}

    void setResultFTable(std::shared_ptr<processor::FactorizedTable> table) {
        resultTable = std::move(table);
    }
    std::shared_ptr<processor::FactorizedTable> getResultTable() const { return resultTable; }

    std::unique_ptr<TableFuncBindData> copy() const override {
        return std::make_unique<GDSBindData>(*this);
    }

private:
    std::shared_ptr<processor::FactorizedTable> resultTable;
};

struct LBUG_API GDSFuncSharedState : public TableFuncSharedState {
    std::unique_ptr<graph::Graph> graph;

    GDSFuncSharedState(std::shared_ptr<processor::FactorizedTable> fTable,
        std::unique_ptr<graph::Graph> graph)
        : TableFuncSharedState{}, graph{std::move(graph)}, factorizedTablePool{std::move(fTable)} {}

    void setGraphNodeMask(std::unique_ptr<common::NodeOffsetMaskMap> maskMap);
    common::NodeOffsetMaskMap* getGraphNodeMaskMap() const { return graphNodeMask.get(); }

public:
    processor::FactorizedTablePool factorizedTablePool;

private:
    std::unique_ptr<common::NodeOffsetMaskMap> graphNodeMask = nullptr;
};

// Base class for every graph data science algorithm.
class LBUG_API GDSFunction {
    static constexpr char NODE_COLUMN_NAME[] = "node";
    static constexpr char REL_COLUMN_NAME[] = "rel";

public:
    static graph::NativeGraphEntry bindGraphEntry(main::ClientContext& context,
        const std::string& name);
    static graph::NativeGraphEntry bindGraphEntry(main::ClientContext& context,
        const graph::ParsedNativeGraphEntry& parsedGraphEntry);
    static std::shared_ptr<binder::Expression> bindRelOutput(const TableFuncBindInput& bindInput,
        const std::vector<catalog::TableCatalogEntry*>& relEntries,
        std::shared_ptr<binder::NodeExpression> srcNode,
        std::shared_ptr<binder::NodeExpression> dstNode,
        const std::optional<std::string>& name = std::nullopt,
        const std::optional<uint64_t>& yieldVariableIdx = std::nullopt);
    static std::shared_ptr<binder::Expression> bindNodeOutput(const TableFuncBindInput& bindInput,
        const std::vector<catalog::TableCatalogEntry*>& nodeEntries,
        const std::optional<std::string>& name = std::nullopt,
        const std::optional<uint64_t>& yieldVariableIdx = std::nullopt);
    static std::string bindColumnName(const parser::YieldVariable& yieldVariable,
        std::string expressionName);

    static std::unique_ptr<TableFuncSharedState> initSharedState(
        const TableFuncInitSharedStateInput& input);
    static void getLogicalPlan(planner::Planner* planner,
        const binder::BoundReadingClause& readingClause, binder::expression_vector predicates,
        planner::LogicalPlan& plan);
    static std::unique_ptr<processor::PhysicalOperator> getPhysicalPlan(
        processor::PlanMapper* planMapper, const planner::LogicalOperator* logicalOp);
};

} // namespace function
} // namespace lbug
