#include "binder/expression/node_expression.h"
#include "function/gds/gds_function_collection.h"
#include "function/gds/rec_joins.h"
#include "function/gds/weight_utils.h"
#include "processor/execution_context.h"
#include "transaction/transaction.h"

using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::storage;
using namespace lbug::processor;

namespace lbug {
namespace function {

class Costs {
public:
    virtual ~Costs() = default;

    virtual void pinTableID(table_id_t tableID) = 0;

    virtual void setCost(offset_t offset, double cost) = 0;
    virtual bool tryReplaceWithMinCost(offset_t offset, double newCost) = 0;

    virtual double getCost(offset_t offset) = 0;
};

class SparseCostsReference : public Costs {
public:
    explicit SparseCostsReference(GDSSpareObjectManager<double>& sparseObjects)
        : sparseObjects{sparseObjects} {}

    void pinTableID(table_id_t tableID) override { curData = sparseObjects.getData(tableID); }

    void setCost(offset_t offset, double cost) override {
        KU_ASSERT(curData != nullptr);
        if (curData->contains(offset)) {
            curData->at(offset) = cost;
        } else {
            curData->insert({offset, cost});
        }
    }

    bool tryReplaceWithMinCost(offset_t offset, double newCost) override {
        auto curCost = getCost(offset);
        if (newCost < curCost) {
            setCost(offset, newCost);
            return true;
        }
        return false;
    }

    double getCost(offset_t offset) override {
        KU_ASSERT(curData != nullptr);
        if (curData->contains(offset)) {
            return curData->at(offset);
        }
        return std::numeric_limits<double>::max();
    }

private:
    std::unordered_map<offset_t, double>* curData = nullptr;
    GDSSpareObjectManager<double>& sparseObjects;
};

class DenseCostsReference : public Costs {
public:
    explicit DenseCostsReference(GDSDenseObjectManager<std::atomic<double>>& denseObjects)
        : denseObjects{denseObjects} {}

    void pinTableID(table_id_t tableID) override { curData = denseObjects.getData(tableID); }

    void setCost(offset_t offset, double cost) override {
        KU_ASSERT(curData != nullptr);
        curData[offset].store(cost, std::memory_order_relaxed);
    }

    bool tryReplaceWithMinCost(offset_t offset, double newCost) override {
        auto curCost = getCost(offset);
        while (newCost < curCost) {
            if (curData[offset].compare_exchange_strong(curCost, newCost)) {
                return true;
            }
        }
        return false;
    }

    double getCost(offset_t offset) override {
        KU_ASSERT(curData != nullptr);
        return curData[offset].load(std::memory_order_relaxed);
    }

private:
    table_id_map_t<offset_t> nodeMaxOffsetMap;
    std::atomic<double>* curData = nullptr;
    GDSDenseObjectManager<std::atomic<double>>& denseObjects;
};

class CostsPair {
public:
    explicit CostsPair(const table_id_map_t<offset_t>& maxOffsetMap)
        : maxOffsetMap{maxOffsetMap}, densityState{GDSDensityState::SPARSE},
          sparseObjects{maxOffsetMap} {
        curSparseCosts = std::make_unique<SparseCostsReference>(sparseObjects);
        nextSparseCosts = std::make_unique<SparseCostsReference>(sparseObjects);
        denseObjects = GDSDenseObjectManager<std::atomic<double>>();
        curDenseCosts = std::make_unique<DenseCostsReference>(denseObjects);
        nextDenseCosts = std::make_unique<DenseCostsReference>(denseObjects);
    }

    Costs* getCurrentCosts() { return curCosts; }

    void pinCurTableID(table_id_t tableID) {
        switch (densityState) {
        case GDSDensityState::SPARSE: {
            curSparseCosts->pinTableID(tableID);
            curCosts = curSparseCosts.get();
        } break;
        case GDSDensityState::DENSE: {
            curDenseCosts->pinTableID(tableID);
            curCosts = curDenseCosts.get();
        } break;
        default:
            KU_UNREACHABLE;
        }
    }

    void pinNextTableID(table_id_t tableID) {
        switch (densityState) {
        case GDSDensityState::SPARSE: {
            nextSparseCosts->pinTableID(tableID);
            nextCosts = nextSparseCosts.get();
        } break;
        case GDSDensityState::DENSE: {
            nextDenseCosts->pinTableID(tableID);
            nextCosts = nextDenseCosts.get();
        } break;
        default:
            KU_UNREACHABLE;
        }
    }

    // CAS update nbrOffset if new path from boundOffset has a smaller cost.
    bool update(offset_t boundOffset, offset_t nbrOffset, double val) {
        KU_ASSERT(curCosts && nextCosts);
        auto newCost = curCosts->getCost(boundOffset) + val;
        return nextCosts->tryReplaceWithMinCost(nbrOffset, newCost);
    }

    void switchToDense(ExecutionContext* context) {
        KU_ASSERT(densityState == GDSDensityState::SPARSE);
        densityState = GDSDensityState::DENSE;
        auto mm = MemoryManager::Get(*context->clientContext);
        for (auto& [tableID, maxOffset] : maxOffsetMap) {
            denseObjects.allocate(tableID, maxOffset, mm);
            auto data = denseObjects.getData(tableID);
            for (auto i = 0u; i < maxOffset; i++) {
                data[i].store(std::numeric_limits<double>::max());
            }
        }
        for (auto& [tableID, map] : sparseObjects.getData()) {
            auto data = denseObjects.getData(tableID);
            for (auto& [offset, cost] : map) {
                data[offset].store(cost);
            }
        }
    }

private:
    table_id_map_t<offset_t> maxOffsetMap;
    GDSDensityState densityState;
    GDSSpareObjectManager<double> sparseObjects;
    std::unique_ptr<SparseCostsReference> curSparseCosts;
    std::unique_ptr<SparseCostsReference> nextSparseCosts;
    GDSDenseObjectManager<std::atomic<double>> denseObjects;
    std::unique_ptr<DenseCostsReference> curDenseCosts;
    std::unique_ptr<DenseCostsReference> nextDenseCosts;

    Costs* curCosts = nullptr;
    Costs* nextCosts = nullptr;
};

template<typename T>
class WSPDestinationsEdgeCompute : public EdgeCompute {
public:
    explicit WSPDestinationsEdgeCompute(CostsPair* costsPair) : costsPair{costsPair} {}

    std::vector<nodeID_t> edgeCompute(nodeID_t boundNodeID, graph::NbrScanState::Chunk& chunk,
        bool) override {
        std::vector<nodeID_t> result;
        chunk.forEach([&](auto neighbors, auto propertyVectors, auto i) {
            auto nbrNodeID = neighbors[i];
            auto weight = propertyVectors[0]->template getValue<T>(i);
            WeightUtils::checkWeight(WeightedSPDestinationsFunction::name, weight);
            if (costsPair->update(boundNodeID.offset, nbrNodeID.offset,
                    static_cast<double>(weight))) {
                result.push_back(nbrNodeID);
            }
        });
        return result;
    }

    std::unique_ptr<EdgeCompute> copy() override {
        return std::make_unique<WSPDestinationsEdgeCompute<T>>(costsPair);
    }

private:
    CostsPair* costsPair;
};

class WSPDestinationsAuxiliaryState : public GDSAuxiliaryState {
public:
    explicit WSPDestinationsAuxiliaryState(std::unique_ptr<CostsPair> costsPair)
        : costsPair{std::move(costsPair)} {}

    Costs* getCosts() { return costsPair->getCurrentCosts(); }

    void initSource(nodeID_t sourceNodeID) override {
        costsPair->pinCurTableID(sourceNodeID.tableID);
        costsPair->getCurrentCosts()->setCost(sourceNodeID.offset, 0);
    }

    void beginFrontierCompute(table_id_t fromTableID, table_id_t toTableID) override {
        costsPair->pinCurTableID(fromTableID);
        costsPair->pinNextTableID(toTableID);
    }

    void switchToDense(ExecutionContext* context, graph::Graph*) override {
        costsPair->switchToDense(context);
    }

private:
    std::unique_ptr<CostsPair> costsPair;
};

class WSPDestinationsOutputWriter : public RJOutputWriter {
public:
    WSPDestinationsOutputWriter(main::ClientContext* context, NodeOffsetMaskMap* outputNodeMask,
        nodeID_t sourceNodeID, Costs* costs, const table_id_map_t<offset_t>& maxOffsetMap)
        : RJOutputWriter{context, outputNodeMask, sourceNodeID}, costs{costs},
          maxOffsetMap{maxOffsetMap} {
        costVector = createVector(LogicalType::DOUBLE());
    }

    void beginWritingInternal(table_id_t tableID) override { costs->pinTableID(tableID); }

    void write(FactorizedTable& fTable, table_id_t tableID, LimitCounter* counter) override {
        for (auto i = 0u; i < maxOffsetMap.at(tableID); ++i) {
            write(fTable, {i, tableID}, counter);
        }
    }

    void write(FactorizedTable& fTable, nodeID_t dstNodeID, LimitCounter* counter) override {
        if (!inOutputNodeMask(dstNodeID.offset)) { // Skip dst if it not is in scope.
            return;
        }
        if (dstNodeID == sourceNodeID_) { // Skip writing source node.
            return;
        }
        dstNodeIDVector->setValue<nodeID_t>(0, dstNodeID);
        auto cost = costs->getCost(dstNodeID.offset);
        if (cost == std::numeric_limits<double>::max()) { // Skip if dst is not visited.
            return;
        }
        costVector->setValue<double>(0, cost);
        fTable.append(vectors);
        if (counter != nullptr) {
            counter->increase(1);
        }
    }

    std::unique_ptr<RJOutputWriter> copy() override {
        return std::make_unique<WSPDestinationsOutputWriter>(context, outputNodeMask, sourceNodeID_,
            costs, maxOffsetMap);
    }

private:
    Costs* costs;
    std::unique_ptr<ValueVector> costVector;
    table_id_map_t<offset_t> maxOffsetMap;
};

class WeightedSPDestinationsAlgorithm : public RJAlgorithm {
public:
    std::string getFunctionName() const override { return WeightedSPDestinationsFunction::name; }

    // return srcNodeID, dstNodeID, weight
    expression_vector getResultColumns(const RJBindData& bindData) const override {
        expression_vector columns;
        columns.push_back(bindData.nodeInput->constCast<NodeExpression>().getInternalID());
        columns.push_back(bindData.nodeOutput->constCast<NodeExpression>().getInternalID());
        columns.push_back(bindData.weightOutputExpr);
        return columns;
    }

    std::unique_ptr<RJAlgorithm> copy() const override {
        return std::make_unique<WeightedSPDestinationsAlgorithm>(*this);
    }

private:
    std::unique_ptr<GDSComputeState> getComputeState(ExecutionContext* context,
        const RJBindData& bindData, RecursiveExtendSharedState* sharedState) override {
        auto clientContext = context->clientContext;
        auto graph = sharedState->graph.get();
        auto curDenseFrontier = DenseFrontier::getUninitializedFrontier(context, graph);
        auto nextDenseFrontier = DenseFrontier::getUninitializedFrontier(context, graph);
        auto frontierPair = std::make_unique<DenseSparseDynamicFrontierPair>(
            std::move(curDenseFrontier), std::move(nextDenseFrontier));
        auto costsPair = std::make_unique<CostsPair>(
            graph->getMaxOffsetMap(transaction::Transaction::Get(*clientContext)));
        auto costPairPtr = costsPair.get();
        auto auxiliaryState = std::make_unique<WSPDestinationsAuxiliaryState>(std::move(costsPair));
        std::unique_ptr<GDSComputeState> gdsState;
        WeightUtils::visit(WeightedSPDestinationsFunction::name,
            bindData.weightPropertyExpr->getDataType(), [&]<typename T>(T) {
                auto edgeCompute = std::make_unique<WSPDestinationsEdgeCompute<T>>(costPairPtr);
                gdsState = std::make_unique<GDSComputeState>(std::move(frontierPair),
                    std::move(edgeCompute), std::move(auxiliaryState));
            });
        return gdsState;
    }

    std::unique_ptr<RJOutputWriter> getOutputWriter(ExecutionContext* context, const RJBindData&,
        GDSComputeState& computeState, nodeID_t sourceNodeID,
        RecursiveExtendSharedState* sharedState) override {
        auto costs =
            computeState.auxiliaryState->ptrCast<WSPDestinationsAuxiliaryState>()->getCosts();
        auto clientContext = context->clientContext;
        return std::make_unique<WSPDestinationsOutputWriter>(clientContext,
            sharedState->getOutputNodeMaskMap(), sourceNodeID, costs,
            sharedState->graph->getMaxOffsetMap(transaction::Transaction::Get(*clientContext)));
    }
};

std::unique_ptr<RJAlgorithm> WeightedSPDestinationsFunction::getAlgorithm() {
    return std::make_unique<WeightedSPDestinationsAlgorithm>();
}

} // namespace function
} // namespace lbug
