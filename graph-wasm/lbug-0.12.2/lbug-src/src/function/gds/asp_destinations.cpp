#include "binder/expression/node_expression.h"
#include "function/gds/gds_function_collection.h"
#include "function/gds/rec_joins.h"
#include "processor/execution_context.h"
#include "transaction/transaction.h"

using namespace lbug::processor;
using namespace lbug::common;
using namespace lbug::binder;
using namespace lbug::storage;
using namespace lbug::graph;

namespace lbug {
namespace function {

using multiplicity_t = uint64_t;

class Multiplicities {
public:
    virtual ~Multiplicities() = default;

    virtual void pinTableID(table_id_t tableID) = 0;

    virtual void increaseMultiplicity(offset_t offset, multiplicity_t multiplicity) = 0;

    virtual multiplicity_t getMultiplicity(offset_t offset) = 0;
};

class SparseMultiplicitiesReference final : public Multiplicities {
public:
    explicit SparseMultiplicitiesReference(GDSSpareObjectManager<multiplicity_t>& spareObjects)
        : spareObjects{spareObjects} {}

    void pinTableID(table_id_t tableID) override { curData = spareObjects.getData(tableID); }

    void increaseMultiplicity(offset_t offset, multiplicity_t multiplicity) override {
        KU_ASSERT(curData);
        if (curData->contains(offset)) {
            curData->at(offset) += multiplicity;
        } else {
            curData->insert({offset, multiplicity});
        }
    }

    multiplicity_t getMultiplicity(offset_t offset) override {
        KU_ASSERT(curData);
        if (curData->contains(offset)) {
            return curData->at(offset);
        }
        return 0;
    }

private:
    GDSSpareObjectManager<multiplicity_t>& spareObjects;
    std::unordered_map<offset_t, multiplicity_t>* curData = nullptr;
};

class DenseMultiplicitiesReference final : public Multiplicities {
public:
    explicit DenseMultiplicitiesReference(
        GDSDenseObjectManager<std::atomic<multiplicity_t>>& denseObjects)
        : denseObjects(denseObjects) {}

    void pinTableID(table_id_t tableID) override { curData = denseObjects.getData(tableID); }

    void increaseMultiplicity(offset_t offset, multiplicity_t multiplicity) override {
        KU_ASSERT(curData);
        curData[offset].fetch_add(multiplicity);
    }

    multiplicity_t getMultiplicity(offset_t offset) override {
        KU_ASSERT(curData);
        return curData[offset].load(std::memory_order_relaxed);
    }

private:
    GDSDenseObjectManager<std::atomic<multiplicity_t>>& denseObjects;
    std::atomic<multiplicity_t>* curData = nullptr;
};

class MultiplicitiesPair {
public:
    explicit MultiplicitiesPair(const table_id_map_t<offset_t>& maxOffsetMap)
        : maxOffsetMap{maxOffsetMap}, densityState{GDSDensityState::SPARSE},
          sparseObjects{maxOffsetMap} {
        curSparseMultiplicities = std::make_unique<SparseMultiplicitiesReference>(sparseObjects);
        nextSparseMultiplicities = std::make_unique<SparseMultiplicitiesReference>(sparseObjects);
        denseObjects = GDSDenseObjectManager<std::atomic<multiplicity_t>>();
        curDenseMultiplicities = std::make_unique<DenseMultiplicitiesReference>(denseObjects);
        nextDenseMultiplicities = std::make_unique<DenseMultiplicitiesReference>(denseObjects);
    }

    void pinCurTableID(table_id_t tableID) {
        switch (densityState) {
        case GDSDensityState::SPARSE: {
            curSparseMultiplicities->pinTableID(tableID);
            curMultiplicities = curSparseMultiplicities.get();
        } break;
        case GDSDensityState::DENSE: {
            curDenseMultiplicities->pinTableID(tableID);
            curMultiplicities = curDenseMultiplicities.get();
        } break;
        default:
            KU_UNREACHABLE;
        }
    }

    void pinNextTableID(table_id_t tableID) {
        switch (densityState) {
        case GDSDensityState::SPARSE: {
            nextSparseMultiplicities->pinTableID(tableID);
            nextMultiplicities = nextSparseMultiplicities.get();
        } break;
        case GDSDensityState::DENSE: {
            nextDenseMultiplicities->pinTableID(tableID);
            nextMultiplicities = nextDenseMultiplicities.get();
        } break;
        default:
            KU_UNREACHABLE;
        }
    }

    void increaseNextMultiplicity(offset_t offset, multiplicity_t multiplicity) {
        nextMultiplicities->increaseMultiplicity(offset, multiplicity);
    }

    multiplicity_t getCurrentMultiplicity(offset_t offset) const {
        return curMultiplicities->getMultiplicity(offset);
    }
    Multiplicities* getCurrentMultiplicities() { return curMultiplicities; }

    void switchToDense(ExecutionContext* context) {
        KU_ASSERT(densityState == GDSDensityState::SPARSE);
        densityState = GDSDensityState::DENSE;
        for (auto& [tableID, maxOffset] : maxOffsetMap) {
            denseObjects.allocate(tableID, maxOffset, MemoryManager::Get(*context->clientContext));
            auto data = denseObjects.getData(tableID);
            for (auto i = 0u; i < maxOffset; i++) {
                data[i].store(0);
            }
        }
        for (auto& [tableID, map] : sparseObjects.getData()) {
            auto data = denseObjects.getData(tableID);
            for (auto& [offset, multiplicity] : map) {
                data[offset].store(multiplicity);
            }
        }
    }

private:
    table_id_map_t<offset_t> maxOffsetMap;
    GDSDensityState densityState;
    GDSSpareObjectManager<multiplicity_t> sparseObjects;
    std::unique_ptr<SparseMultiplicitiesReference> curSparseMultiplicities;
    std::unique_ptr<SparseMultiplicitiesReference> nextSparseMultiplicities;
    GDSDenseObjectManager<std::atomic<multiplicity_t>> denseObjects;
    std::unique_ptr<DenseMultiplicitiesReference> curDenseMultiplicities;
    std::unique_ptr<DenseMultiplicitiesReference> nextDenseMultiplicities;

    Multiplicities* curMultiplicities = nullptr;
    Multiplicities* nextMultiplicities = nullptr;
};

class ASPDestinationsAuxiliaryState : public GDSAuxiliaryState {
public:
    explicit ASPDestinationsAuxiliaryState(std::unique_ptr<MultiplicitiesPair> multiplicitiesPair)
        : multiplicitiesPair{std::move(multiplicitiesPair)} {}

    MultiplicitiesPair* getMultiplicitiesPair() const { return multiplicitiesPair.get(); }

    void initSource(nodeID_t source) override {
        multiplicitiesPair->pinNextTableID(source.tableID);
        multiplicitiesPair->increaseNextMultiplicity(source.offset, 1);
    }

    void beginFrontierCompute(table_id_t curTableID, table_id_t nextTableID) override {
        multiplicitiesPair->pinCurTableID(curTableID);
        multiplicitiesPair->pinNextTableID(nextTableID);
    }

    void switchToDense(ExecutionContext* context, Graph*) override {
        multiplicitiesPair->switchToDense(context);
    }

private:
    std::unique_ptr<MultiplicitiesPair> multiplicitiesPair;
};

class ASPDestinationsOutputWriter : public RJOutputWriter {
public:
    ASPDestinationsOutputWriter(main::ClientContext* context, NodeOffsetMaskMap* outputNodeMask,
        nodeID_t sourceNodeID, Frontier* frontier, Multiplicities* multiplicities)
        : RJOutputWriter{context, outputNodeMask, sourceNodeID}, frontier{frontier},
          multiplicities{multiplicities} {
        lengthVector = createVector(LogicalType::UINT16());
    }

    void beginWritingInternal(table_id_t tableID) override {
        frontier->pinTableID(tableID);
        multiplicities->pinTableID(tableID);
    }

    void write(FactorizedTable& fTable, table_id_t tableID, LimitCounter* counter) override {
        auto& sparseFrontier = frontier->cast<SparseFrontier>();
        for (auto [offset, _] : sparseFrontier.getCurrentData()) {
            write(fTable, {offset, tableID}, counter);
        }
    }

    void write(FactorizedTable& fTable, nodeID_t dstNodeID, LimitCounter* counter) override {
        if (!inOutputNodeMask(dstNodeID.offset)) { // Skip dst if it not is in scope.
            return;
        }
        if (dstNodeID == sourceNodeID_) { // Skip writing source node.
            return;
        }
        auto iter = frontier->getIteration(dstNodeID.offset);
        if (iter == FRONTIER_UNVISITED) { // Skip if dst is not visited.
            return;
        }
        dstNodeIDVector->setValue<nodeID_t>(0, dstNodeID);
        lengthVector->setValue<uint16_t>(0, iter);
        auto multiplicity = multiplicities->getMultiplicity(dstNodeID.offset);
        for (auto i = 0u; i < multiplicity; ++i) {
            fTable.append(vectors);
        }
        if (counter != nullptr) {
            counter->increase(multiplicity);
        }
    }

    std::unique_ptr<RJOutputWriter> copy() override {
        return std::make_unique<ASPDestinationsOutputWriter>(context, outputNodeMask, sourceNodeID_,
            frontier, multiplicities);
    }

private:
    std::unique_ptr<ValueVector> lengthVector;
    Frontier* frontier;
    Multiplicities* multiplicities;
};

class ASPDestinationsEdgeCompute : public SPEdgeCompute {
public:
    ASPDestinationsEdgeCompute(SPFrontierPair* frontierPair, MultiplicitiesPair* multiplicitiesPair)
        : SPEdgeCompute{frontierPair}, multiplicitiesPair{multiplicitiesPair} {};

    std::vector<nodeID_t> edgeCompute(nodeID_t boundNodeID, NbrScanState::Chunk& resultChunk,
        bool) override {
        std::vector<nodeID_t> activeNodes;
        resultChunk.forEach([&](auto neighbors, auto, auto i) {
            auto nbrNodeID = neighbors[i];
            auto nbrVal = frontierPair->getNextFrontierValue(nbrNodeID.offset);
            // We should update the nbrID's multiplicity in 2 cases: 1) if nbrID is being visited
            // for the first time, i.e., when its value in the pathLengths frontier is
            // FRONTIER_UNVISITED. Or 2) if nbrID has already been visited but in this
            // iteration, so it's value is curIter + 1.
            auto shouldUpdate =
                nbrVal == FRONTIER_UNVISITED || nbrVal == frontierPair->getCurrentIter();
            if (shouldUpdate) {
                // This is safe because boundNodeID is in the current frontier, so its
                // shortest paths multiplicity is guaranteed to not change in the current iteration.
                auto boundMultiplicity =
                    multiplicitiesPair->getCurrentMultiplicity(boundNodeID.offset);
                multiplicitiesPair->increaseNextMultiplicity(nbrNodeID.offset, boundMultiplicity);
            }
            if (nbrVal == FRONTIER_UNVISITED) {
                activeNodes.push_back(nbrNodeID);
            }
        });
        return activeNodes;
    }

    std::unique_ptr<EdgeCompute> copy() override {
        return std::make_unique<ASPDestinationsEdgeCompute>(frontierPair, multiplicitiesPair);
    }

private:
    MultiplicitiesPair* multiplicitiesPair;
};

// All shortest path algorithm. Only destinations are tracked (reachability query).
class AllSPDestinationsAlgorithm final : public RJAlgorithm {
public:
    std::string getFunctionName() const override { return AllSPDestinationsFunction::name; }

    expression_vector getResultColumns(const RJBindData& bindData) const override {
        expression_vector columns;
        columns.push_back(bindData.nodeInput->constCast<NodeExpression>().getInternalID());
        columns.push_back(bindData.nodeOutput->constCast<NodeExpression>().getInternalID());
        columns.push_back(bindData.lengthExpr);
        return columns;
    }

    std::unique_ptr<RJAlgorithm> copy() const override {
        return std::make_unique<AllSPDestinationsAlgorithm>(*this);
    }

private:
    std::unique_ptr<GDSComputeState> getComputeState(ExecutionContext* context, const RJBindData&,
        RecursiveExtendSharedState* sharedState) override {
        auto clientContext = context->clientContext;
        auto graph = sharedState->graph.get();
        auto multiplicitiesPair = std::make_unique<MultiplicitiesPair>(
            graph->getMaxOffsetMap(transaction::Transaction::Get(*clientContext)));
        auto frontier = DenseFrontier::getUnvisitedFrontier(context, graph);
        auto frontierPair = std::make_unique<SPFrontierPair>(std::move(frontier));
        auto edgeCompute = std::make_unique<ASPDestinationsEdgeCompute>(frontierPair.get(),
            multiplicitiesPair.get());
        auto auxiliaryState =
            std::make_unique<ASPDestinationsAuxiliaryState>(std::move(multiplicitiesPair));
        return std::make_unique<GDSComputeState>(std::move(frontierPair), std::move(edgeCompute),
            std::move(auxiliaryState));
    }

    std::unique_ptr<RJOutputWriter> getOutputWriter(ExecutionContext* context, const RJBindData&,
        GDSComputeState& computeState, nodeID_t sourceNodeID,
        RecursiveExtendSharedState* sharedState) override {
        auto frontier = computeState.frontierPair->ptrCast<SPFrontierPair>()->getFrontier();
        auto multiplicities = computeState.auxiliaryState->ptrCast<ASPDestinationsAuxiliaryState>()
                                  ->getMultiplicitiesPair()
                                  ->getCurrentMultiplicities();
        return std::make_unique<ASPDestinationsOutputWriter>(context->clientContext,
            sharedState->getOutputNodeMaskMap(), sourceNodeID, frontier, multiplicities);
    }
};

std::unique_ptr<RJAlgorithm> AllSPDestinationsFunction::getAlgorithm() {
    return std::make_unique<AllSPDestinationsAlgorithm>();
}

} // namespace function
} // namespace lbug
