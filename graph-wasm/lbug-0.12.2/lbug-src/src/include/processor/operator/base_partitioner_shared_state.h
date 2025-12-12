#pragma once

#include <array>
#include <atomic>

#include "common/api.h"
#include "common/types/types.h"

namespace lbug {
namespace storage {
class NodeTable;
class RelTable;
} // namespace storage
namespace main {
class ClientContext;
}
namespace processor {

struct LBUG_API PartitionerSharedState {
    storage::NodeTable* srcNodeTable;
    storage::NodeTable* dstNodeTable;
    storage::RelTable* relTable;

    static constexpr size_t DIRECTIONS = 2;
    std::array<common::offset_t, DIRECTIONS> numNodes;
    std::array<common::partition_idx_t, DIRECTIONS>
        numPartitions; // num of partitions in each direction.
    std::atomic<common::partition_idx_t> nextPartitionIdx;

    PartitionerSharedState()
        : srcNodeTable{nullptr}, dstNodeTable{nullptr}, relTable(nullptr), numNodes{0, 0},
          numPartitions{0, 0}, nextPartitionIdx{0} {}
    virtual ~PartitionerSharedState() = default;

    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }

    virtual void initialize(const common::logical_type_vec_t& columnTypes,
        common::idx_t numPartitioners, const main::ClientContext* clientContext);

    common::partition_idx_t getNextPartition(common::idx_t partitioningIdx);

    common::partition_idx_t getNumPartitions(common::idx_t partitioningIdx) const {
        return numPartitions[partitioningIdx];
    }
    common::offset_t getNumNodes(common::idx_t partitioningIdx) const {
        return numNodes[partitioningIdx];
    }

    virtual void resetState(common::idx_t partitioningIdx);

    static common::partition_idx_t getNumPartitionsFromRows(common::offset_t numRows);
};
} // namespace processor
} // namespace lbug
