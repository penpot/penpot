#pragma once

#include <cstddef>

#include "common/assert.h"
#include "common/copy_constructors.h"
#include "common/data_chunk/sel_vector.h"
#include "common/enums/rel_direction.h"
#include "common/mask.h"
#include "common/types/types.h"
#include "common/vector/value_vector.h"
#include "graph.h"
#include "graph_entry.h"
#include "processor/operator/filtering_operator.h"
#include "storage/table/node_table.h"
#include "storage/table/rel_table.h"

namespace lbug {
namespace storage {
class MemoryManager;
}

namespace graph {

class OnDiskGraphNbrScanState : public NbrScanState {
    friend class OnDiskGraph;

public:
    OnDiskGraphNbrScanState(main::ClientContext* context, const catalog::TableCatalogEntry& entry,
        common::oid_t relTableID, std::shared_ptr<binder::Expression> predicate);
    OnDiskGraphNbrScanState(main::ClientContext* context, const catalog::TableCatalogEntry& entry,
        common::oid_t relTableID, std::shared_ptr<binder::Expression> predicate,
        std::vector<std::string> relProperties, bool randomLookup = false);

    Chunk getChunk() override {
        return createChunk(currentIter->getNbrNodes(), currentIter->getSelVectorUnsafe(),
            std::span(propertyVectors.valueVectors));
    }
    bool next() override;

    void startScan(common::RelDataDirection direction);

    class InnerIterator : public processor::SelVectorOverWriter {
    public:
        InnerIterator(const main::ClientContext* context, storage::RelTable* relTable,
            std::unique_ptr<storage::RelTableScanState> tableScanState);

        DELETE_COPY_DEFAULT_MOVE(InnerIterator);

        std::span<const common::nodeID_t> getNbrNodes() const {
            RUNTIME_CHECK(for (size_t i = 0; i < getSelVector().getSelSize(); i++) {
                KU_ASSERT(
                    getSelVector().getSelectedPositions()[i] < common::DEFAULT_VECTOR_CAPACITY);
            });
            return std::span(&dstVector().getValue<const common::nodeID_t>(0),
                common::DEFAULT_VECTOR_CAPACITY);
        }

        common::SelectionVector& getSelVectorUnsafe() {
            return tableScanState->outState->getSelVectorUnsafe();
        }

        const common::SelectionVector& getSelVector() const {
            return tableScanState->outState->getSelVector();
        }

        bool next(evaluator::ExpressionEvaluator* predicate, common::SemiMask* nbrNodeMask);
        void initScan() const;

        common::RelDataDirection getDirection() const { return tableScanState->direction; }

    private:
        common::ValueVector& dstVector() const { return *tableScanState->outputVectors[0]; }

        const main::ClientContext* context;
        storage::RelTable* relTable;
        std::unique_ptr<storage::RelTableScanState> tableScanState;
    };

private:
    std::unique_ptr<common::ValueVector> srcNodeIDVector;
    std::unique_ptr<common::ValueVector> dstNodeIDVector;
    common::DataChunk propertyVectors;

    std::unique_ptr<evaluator::ExpressionEvaluator> relPredicateEvaluator;
    common::SemiMask* nbrNodeMask = nullptr;

    std::vector<InnerIterator> directedIterators;
    InnerIterator* currentIter = nullptr;
};

class OnDiskGraphVertexScanState final : public VertexScanState {
public:
    OnDiskGraphVertexScanState(main::ClientContext& context,
        const catalog::TableCatalogEntry* tableEntry,
        const std::vector<std::string>& propertyNames);

    void startScan(common::offset_t beginOffset, common::offset_t endOffsetExclusive);

    bool next() override;
    Chunk getChunk() override {
        return createChunk(std::span(&nodeIDVector->getValue<common::nodeID_t>(0),
                               nodeIDVector->getSelVectorPtr()->getSelSize()),
            std::span(propertyVectors.valueVectors));
    }

private:
    const main::ClientContext& context;
    const storage::NodeTable& nodeTable;

    common::DataChunk propertyVectors;
    std::unique_ptr<common::ValueVector> nodeIDVector;
    std::unique_ptr<storage::NodeTableScanState> tableScanState;

    common::offset_t numNodesToScan;
    common::offset_t currentOffset;
    common::offset_t endOffsetExclusive;
};

class LBUG_API OnDiskGraph final : public Graph {
public:
    OnDiskGraph(main::ClientContext* context, NativeGraphEntry entry);

    NativeGraphEntry* getGraphEntry() override { return &graphEntry; }

    void setNodeOffsetMask(common::NodeOffsetMaskMap* maskMap) { nodeOffsetMaskMap = maskMap; }

    std::vector<common::table_id_t> getNodeTableIDs() const override {
        return graphEntry.getNodeTableIDs();
    }

    common::table_id_map_t<common::offset_t> getMaxOffsetMap(
        transaction::Transaction* transaction) const override;

    common::offset_t getMaxOffset(transaction::Transaction* transaction,
        common::table_id_t id) const override;

    common::offset_t getNumNodes(transaction::Transaction* transaction) const override;

    std::vector<GraphRelInfo> getRelInfos(common::table_id_t srcTableID) override;

    std::unique_ptr<NbrScanState> prepareRelScan(const catalog::TableCatalogEntry& entry,
        common::oid_t relTableID, common::table_id_t nbrTableID,
        std::vector<std::string> relProperties, bool randomLookUp = true) override;

    EdgeIterator scanFwd(common::nodeID_t nodeID, NbrScanState& state) override;
    EdgeIterator scanBwd(common::nodeID_t nodeID, NbrScanState& state) override;

    std::unique_ptr<VertexScanState> prepareVertexScan(catalog::TableCatalogEntry* tableEntry,
        const std::vector<std::string>& propertiesToScan) override;
    VertexIterator scanVertices(common::offset_t beginOffset, common::offset_t endOffsetExclusive,
        VertexScanState& state) override;

private:
    main::ClientContext* context;
    NativeGraphEntry graphEntry;
    common::NodeOffsetMaskMap* nodeOffsetMaskMap = nullptr;
    common::table_id_map_t<storage::NodeTable*> nodeIDToNodeTable;
    std::vector<GraphRelInfo> relInfos;
};

} // namespace graph
} // namespace lbug
