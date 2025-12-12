#pragma once

#include <iterator>
#include <memory>

#include "common/copy_constructors.h"
#include "common/data_chunk/sel_vector.h"
#include "common/types/types.h"
#include "common/vector/value_vector.h"
#include <span>

namespace lbug {
namespace catalog {
class TableCatalogEntry;
} // namespace catalog
namespace transaction {
class Transaction;
} // namespace transaction

namespace graph {
struct NativeGraphEntry;

struct GraphRelInfo {
    common::table_id_t srcTableID;
    common::table_id_t dstTableID;
    catalog::TableCatalogEntry* relGroupEntry;
    common::oid_t relTableID;

    GraphRelInfo(common::table_id_t srcTableID, common::table_id_t dstTableID,
        catalog::TableCatalogEntry* relGroupEntry, common::oid_t relTableID)
        : srcTableID{srcTableID}, dstTableID{dstTableID}, relGroupEntry{relGroupEntry},
          relTableID{relTableID} {}
};

class LBUG_API NbrScanState {
public:
    struct Chunk {
        friend class NbrScanState;

        EXPLICIT_COPY_METHOD(Chunk);
        // Any neighbour for which the given function returns false
        // will be omitted from future iterations
        // Used in GDSTask/EdgeCompute for updating the frontier
        template<class Func>
        void forEach(Func&& func) const {
            selVector.forEach([&](auto i) { func(nbrNodes, propertyVectors, i); });
        }
        template<class Func>
        void forEachBreakWhenFalse(Func&& func) const {
            selVector.forEachBreakWhenFalse([&](auto i) -> bool { return func(nbrNodes, i); });
        }

        uint64_t size() const { return selVector.getSelSize(); }

    private:
        Chunk(std::span<const common::nodeID_t> nbrNodes, common::SelectionVector& selVector,
            std::span<const std::shared_ptr<common::ValueVector>> propertyVectors);

        Chunk(const Chunk& other) noexcept
            : nbrNodes{other.nbrNodes}, selVector{other.selVector},
              propertyVectors{other.propertyVectors} {}

    private:
        std::span<const common::nodeID_t> nbrNodes;
        // this reference can be modified, but the underlying data will be reset the next time next
        // is called
        common::SelectionVector& selVector;
        std::span<const std::shared_ptr<common::ValueVector>> propertyVectors;
    };

    virtual ~NbrScanState() = default;
    virtual Chunk getChunk() = 0;

    // Returns true if there are more values after the current batch
    virtual bool next() = 0;

protected:
    static Chunk createChunk(std::span<const common::nodeID_t> nbrNodes,
        common::SelectionVector& selVector,
        std::span<const std::shared_ptr<common::ValueVector>> propertyVectors) {
        return Chunk{nbrNodes, selVector, propertyVectors};
    }
};

class VertexScanState {
public:
    struct Chunk {
        friend class VertexScanState;

        size_t size() const { return nodeIDs.size(); }
        std::span<const common::nodeID_t> getNodeIDs() const { return nodeIDs; }
        template<typename T>
        std::span<const T> getProperties(size_t propertyIndex) const {
            return std::span(reinterpret_cast<const T*>(propertyVectors[propertyIndex]->getData()),
                nodeIDs.size());
        }

    private:
        LBUG_API Chunk(std::span<const common::nodeID_t> nodeIDs,
            std::span<const std::shared_ptr<common::ValueVector>> propertyVectors);

    private:
        std::span<const common::nodeID_t> nodeIDs;
        std::span<const std::shared_ptr<common::ValueVector>> propertyVectors;
    };
    virtual Chunk getChunk() = 0;

    // Returns true if there are more values after the current batch
    virtual bool next() = 0;

    virtual ~VertexScanState() = default;

protected:
    static Chunk createChunk(std::span<const common::nodeID_t> nodeIDs,
        std::span<const std::shared_ptr<common::ValueVector>> propertyVectors) {
        return Chunk{nodeIDs, propertyVectors};
    }
};

/**
 * Graph interface to be use by GDS algorithms to get neighbors of nodes.
 *
 * Instances of Graph are not expected to be thread-safe. Therefore, if Graph is intended to be used
 * in a parallel manner, the user should first copy() an instance and give each thread a separate
 * copy. It is the responsibility of the implementing Graph class that the copy() is a lightweight
 * operation that does not copy large amounts of data between instances.
 */
class Graph {
public:
    class EdgeIterator {
    public:
        explicit constexpr EdgeIterator(NbrScanState* scanState) : scanState{scanState} {}
        DEFAULT_BOTH_MOVE(EdgeIterator);
        EdgeIterator(const EdgeIterator& other) = default;
        EdgeIterator() : scanState{nullptr} {}
        using difference_type = std::ptrdiff_t;
        using value_type = NbrScanState::Chunk;

        value_type operator*() const { return scanState->getChunk(); }
        EdgeIterator& operator++() {
            if (!scanState->next()) {
                scanState = nullptr;
            }
            return *this;
        }
        void operator++(int) { ++*this; }
        bool operator==(const EdgeIterator& other) const {
            // Only needed for comparing to the end, so they are equal if and only if both are null
            return scanState == nullptr && other.scanState == nullptr;
        }
        // Counts and consumes the iterator
        uint64_t count() const {
            // TODO(bmwinger): avoid scanning if all that's necessary is to count the results
            uint64_t result = 0;
            do {
                result += scanState->getChunk().size();
            } while (scanState->next());
            return result;
        }

        std::vector<common::nodeID_t> collectNbrNodes() {
            std::vector<common::nodeID_t> nbrNodes;
            for (const auto chunk : *this) {
                nbrNodes.reserve(nbrNodes.size() + chunk.size());
                chunk.forEach(
                    [&](auto neighbors, auto, auto i) { nbrNodes.push_back(neighbors[i]); });
            }
            return nbrNodes;
        }

        EdgeIterator& begin() noexcept { return *this; }
        static constexpr EdgeIterator end() noexcept { return EdgeIterator(nullptr); }

    private:
        NbrScanState* scanState;
    };
    static_assert(std::input_iterator<EdgeIterator>);

    Graph() = default;
    virtual ~Graph() = default;

    virtual NativeGraphEntry* getGraphEntry() = 0;

    // Get id for all node tables.
    virtual std::vector<common::table_id_t> getNodeTableIDs() const = 0;

    // Get max offset of each table as a map.
    virtual common::table_id_map_t<common::offset_t> getMaxOffsetMap(
        transaction::Transaction* transaction) const = 0;

    // Get max offset of given table.
    virtual common::offset_t getMaxOffset(transaction::Transaction* transaction,
        common::table_id_t id) const = 0;

    // Get num nodes for all node tables.
    virtual common::offset_t getNumNodes(transaction::Transaction* transaction) const = 0;

    // Get all possible (srcTable, dstTable, relTable)s.
    virtual std::vector<GraphRelInfo> getRelInfos(common::table_id_t srcTableID) = 0;

    // Prepares scan on the specified relationship table (works for backwards and forwards scans)
    virtual std::unique_ptr<NbrScanState> prepareRelScan(const catalog::TableCatalogEntry& entry,
        common::oid_t relTableID, common::table_id_t nbrTableID,
        std::vector<std::string> relProperties, bool randomLookup = true) = 0;

    // Get dst nodeIDs for given src nodeID using forward adjList.
    virtual EdgeIterator scanFwd(common::nodeID_t nodeID, NbrScanState& state) = 0;

    // Get dst nodeIDs for given src nodeID tables using backward adjList.
    virtual EdgeIterator scanBwd(common::nodeID_t nodeID, NbrScanState& state) = 0;

    class VertexIterator {
    public:
        explicit constexpr VertexIterator(VertexScanState* scanState) : scanState{scanState} {}
        DEFAULT_BOTH_MOVE(VertexIterator);
        VertexIterator(const VertexIterator& other) = default;
        VertexIterator() : scanState{nullptr} {}
        using difference_type = std::ptrdiff_t;
        using value_type = VertexScanState::Chunk;

        value_type operator*() const { return scanState->getChunk(); }
        VertexIterator& operator++() {
            if (!scanState->next()) {
                scanState = nullptr;
            }
            return *this;
        }
        void operator++(int) { ++*this; }
        bool operator==(const VertexIterator& other) const {
            // Only needed for comparing to the end, so they are equal if and only if both are null
            return scanState == nullptr && other.scanState == nullptr;
        }

        VertexIterator& begin() noexcept { return *this; }
        static constexpr VertexIterator end() noexcept { return VertexIterator(nullptr); }

    private:
        VertexScanState* scanState;
    };
    static_assert(std::input_iterator<EdgeIterator>);

    virtual std::unique_ptr<VertexScanState> prepareVertexScan(
        catalog::TableCatalogEntry* tableEntry, const std::vector<std::string>& properties) = 0;

    virtual VertexIterator scanVertices(common::offset_t startNodeOffset,
        common::offset_t endNodeOffsetExclusive, VertexScanState& scanState) = 0;

    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }
};

} // namespace graph
} // namespace lbug
