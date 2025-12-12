#include "catalog/catalog_entry/rel_group_catalog_entry.h"

#include <sstream>

#include "binder/ddl/bound_create_table_info.h"
#include "catalog/catalog.h"
#include "common/serializer/deserializer.h"
#include "transaction/transaction.h"

using namespace lbug::common;
using namespace lbug::main;

namespace lbug {
namespace catalog {

void RelGroupCatalogEntry::addFromToConnection(table_id_t srcTableID, table_id_t dstTableID,
    oid_t oid) {
    relTableInfos.emplace_back(NodeTableIDPair{srcTableID, dstTableID}, oid);
}

void RelGroupCatalogEntry::dropFromToConnection(table_id_t srcTableID, table_id_t dstTableID) {
    auto tmpInfos = relTableInfos;
    relTableInfos.clear();
    for (auto& tmpInfo : tmpInfos) {
        if (tmpInfo.nodePair.srcTableID == srcTableID &&
            tmpInfo.nodePair.dstTableID == dstTableID) {
            continue;
        }
        relTableInfos.emplace_back(tmpInfo);
    }
}

void RelTableCatalogInfo::serialize(Serializer& ser) const {
    ser.writeDebuggingInfo("nodePair");
    nodePair.serialize(ser);
    ser.writeDebuggingInfo("oid");
    ser.serializeValue(oid);
}

RelTableCatalogInfo RelTableCatalogInfo::deserialize(Deserializer& deser) {
    std::string debuggingInfo;
    oid_t oid = INVALID_OID;
    deser.validateDebuggingInfo(debuggingInfo, "nodePair");
    auto nodePair = NodeTableIDPair::deserialize(deser);
    deser.validateDebuggingInfo(debuggingInfo, "oid");
    deser.deserializeValue(oid);
    return RelTableCatalogInfo{nodePair, oid};
}

bool RelGroupCatalogEntry::isParent(table_id_t tableID) {
    for (auto& info : relTableInfos) {
        if (info.nodePair.srcTableID == tableID || info.nodePair.dstTableID == tableID) {
            return true;
        }
    }
    return false;
}

const RelTableCatalogInfo& RelGroupCatalogEntry::getSingleRelEntryInfo() const {
    KU_ASSERT(relTableInfos.size() == 1);
    return relTableInfos[0];
}

const RelTableCatalogInfo* RelGroupCatalogEntry::getRelEntryInfo(table_id_t srcTableID,
    table_id_t dstTableID) const {
    for (auto& info : relTableInfos) {
        if (info.nodePair.srcTableID == srcTableID && info.nodePair.dstTableID == dstTableID) {
            return &info;
        }
    }
    return nullptr;
}

std::unordered_set<table_id_t> RelGroupCatalogEntry::getSrcNodeTableIDSet() const {
    std::unordered_set<table_id_t> result;
    for (auto& info : relTableInfos) {
        result.insert(info.nodePair.srcTableID);
    }
    return result;
}

std::unordered_set<table_id_t> RelGroupCatalogEntry::getDstNodeTableIDSet() const {
    std::unordered_set<table_id_t> result;
    for (auto& info : relTableInfos) {
        result.insert(info.nodePair.dstTableID);
    }
    return result;
}

void RelGroupCatalogEntry::serialize(Serializer& serializer) const {
    TableCatalogEntry::serialize(serializer);
    serializer.writeDebuggingInfo("srcMultiplicity");
    serializer.serializeValue(srcMultiplicity);
    serializer.writeDebuggingInfo("dstMultiplicity");
    serializer.serializeValue(dstMultiplicity);
    serializer.writeDebuggingInfo("storageDirection");
    serializer.serializeValue(storageDirection);
    serializer.writeDebuggingInfo("relTableInfos");
    serializer.serializeVector(relTableInfos);
}

std::unique_ptr<RelGroupCatalogEntry> RelGroupCatalogEntry::deserialize(
    Deserializer& deserializer) {
    std::string debuggingInfo;
    auto srcMultiplicity = RelMultiplicity::MANY;
    auto dstMultiplicity = RelMultiplicity::MANY;
    auto storageDirection = ExtendDirection::BOTH;
    std::vector<RelTableCatalogInfo> relTableInfos;
    deserializer.validateDebuggingInfo(debuggingInfo, "srcMultiplicity");
    deserializer.deserializeValue(srcMultiplicity);
    deserializer.validateDebuggingInfo(debuggingInfo, "dstMultiplicity");
    deserializer.deserializeValue(dstMultiplicity);
    deserializer.validateDebuggingInfo(debuggingInfo, "storageDirection");
    deserializer.deserializeValue(storageDirection);
    deserializer.validateDebuggingInfo(debuggingInfo, "relTableInfos");
    deserializer.deserializeVector(relTableInfos);
    auto relGroupEntry = std::make_unique<RelGroupCatalogEntry>();
    relGroupEntry->srcMultiplicity = srcMultiplicity;
    relGroupEntry->dstMultiplicity = dstMultiplicity;
    relGroupEntry->storageDirection = storageDirection;
    relGroupEntry->relTableInfos = relTableInfos;
    return relGroupEntry;
}

static std::string getFromToStr(const NodeTableIDPair& pair, const Catalog* catalog,
    const transaction::Transaction* transaction) {
    auto srcTableName = catalog->getTableCatalogEntry(transaction, pair.srcTableID)->getName();
    auto dstTableName = catalog->getTableCatalogEntry(transaction, pair.dstTableID)->getName();
    return stringFormat("FROM `{}` TO `{}`", srcTableName, dstTableName);
}

std::string RelGroupCatalogEntry::toCypher(const ToCypherInfo& info) const {
    auto relGroupInfo = info.constCast<RelGroupToCypherInfo>();
    auto catalog = Catalog::Get(*relGroupInfo.context);
    auto transaction = transaction::Transaction::Get(*relGroupInfo.context);
    std::stringstream ss;
    ss << stringFormat("CREATE REL TABLE `{}` (", getName());
    KU_ASSERT(!relTableInfos.empty());
    ss << getFromToStr(relTableInfos[0].nodePair, catalog, transaction);
    for (auto i = 1u; i < relTableInfos.size(); ++i) {
        ss << stringFormat(", {}", getFromToStr(relTableInfos[i].nodePair, catalog, transaction));
    }
    ss << ", " << propertyCollection.toCypher() << RelMultiplicityUtils::toString(srcMultiplicity)
       << "_" << RelMultiplicityUtils::toString(dstMultiplicity) << ");";
    return ss.str();
}

std::vector<RelDataDirection> RelGroupCatalogEntry::getRelDataDirections() const {
    switch (storageDirection) {
    case ExtendDirection::FWD: {
        return {RelDataDirection::FWD};
    }
    case ExtendDirection::BWD: {
        return {RelDataDirection::BWD};
    }
    case ExtendDirection::BOTH: {
        return {RelDataDirection::FWD, RelDataDirection::BWD};
    }
    default: {
        KU_UNREACHABLE;
    }
    }
}

std::unique_ptr<TableCatalogEntry> RelGroupCatalogEntry::copy() const {
    auto other = std::make_unique<RelGroupCatalogEntry>();
    other->srcMultiplicity = srcMultiplicity;
    other->dstMultiplicity = dstMultiplicity;
    other->storageDirection = storageDirection;
    other->relTableInfos = relTableInfos;
    other->copyFrom(*this);
    return other;
}

std::unique_ptr<binder::BoundExtraCreateCatalogEntryInfo>
RelGroupCatalogEntry::getBoundExtraCreateInfo(transaction::Transaction*) const {
    std::vector<NodeTableIDPair> nodePairs;
    for (auto& relTableInfo : relTableInfos) {
        nodePairs.push_back(relTableInfo.nodePair);
    }
    return std::make_unique<binder::BoundExtraCreateRelTableGroupInfo>(
        copyVector(propertyCollection.getDefinitions()), srcMultiplicity, dstMultiplicity,
        storageDirection, std::move(nodePairs));
}

} // namespace catalog
} // namespace lbug
