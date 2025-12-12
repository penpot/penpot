#include "catalog/catalog_entry/node_table_id_pair.h"

#include "common/serializer/deserializer.h"
#include "common/serializer/serializer.h"

using namespace lbug::common;

namespace lbug {
namespace catalog {

void NodeTableIDPair::serialize(Serializer& serializer) const {
    serializer.writeDebuggingInfo("srcTableID");
    serializer.serializeValue(srcTableID);
    serializer.writeDebuggingInfo("dstTableID");
    serializer.serializeValue(dstTableID);
}

NodeTableIDPair NodeTableIDPair::deserialize(Deserializer& deser) {
    std::string debuggingInfo;
    table_id_t srcTableID = INVALID_TABLE_ID;
    table_id_t dstTableID = INVALID_TABLE_ID;
    deser.validateDebuggingInfo(debuggingInfo, "srcTableID");
    deser.deserializeValue(srcTableID);
    deser.validateDebuggingInfo(debuggingInfo, "dstTableID");
    deser.deserializeValue(dstTableID);
    return NodeTableIDPair{srcTableID, dstTableID};
}

} // namespace catalog
} // namespace lbug
