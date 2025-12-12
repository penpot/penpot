#pragma once

#include "common/types/types.h"

namespace lbug {
namespace catalog {

struct NodeTableIDPair {
    common::table_id_t srcTableID = common::INVALID_TABLE_ID;
    common::table_id_t dstTableID = common::INVALID_TABLE_ID;

    NodeTableIDPair() = default;
    NodeTableIDPair(common::table_id_t srcTableID, common::table_id_t dstTableID)
        : srcTableID{srcTableID}, dstTableID{dstTableID} {}

    void serialize(common::Serializer& serializer) const;
    static NodeTableIDPair deserialize(common::Deserializer& deser);
};

struct NodeTableIDPairHash {
    std::size_t operator()(const NodeTableIDPair& np) const {
        std::size_t h1 = std::hash<common::table_id_t>{}(np.srcTableID);
        std::size_t h2 = std::hash<common::table_id_t>{}(np.dstTableID);
        return h1 ^ (h2 << 1);
    }
};

struct NodeTableIDPairEqual {
    bool operator()(const NodeTableIDPair& lhs, const NodeTableIDPair& rhs) const {
        return lhs.srcTableID == rhs.srcTableID && lhs.dstTableID == rhs.dstTableID;
    }
};

using node_table_id_pair_set_t =
    std::unordered_set<NodeTableIDPair, NodeTableIDPairHash, NodeTableIDPairEqual>;

} // namespace catalog
} // namespace lbug
