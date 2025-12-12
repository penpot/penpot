#include "binder/binder.h"
#include "binder/expression/property_expression.h"
#include "binder/expression_binder.h"
#include "common/enums/extend_direction_util.h"
#include "main/client_context.h"
#include "planner/operator/extend/logical_extend.h"
#include "processor/operator/scan/scan_multi_rel_tables.h"
#include "processor/operator/scan/scan_rel_table.h"
#include "processor/plan_mapper.h"
#include "storage/storage_manager.h"

using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::planner;
using namespace lbug::storage;
using namespace lbug::catalog;

namespace lbug {
namespace processor {

static ScanRelTableInfo getRelTableScanInfo(const TableCatalogEntry& tableEntry,
    RelDataDirection direction, RelTable* relTable, bool shouldScanNbrID,
    const expression_vector& properties, const std::vector<ColumnPredicateSet>& columnPredicates,
    main::ClientContext* clientContext) {
    std::vector<ColumnPredicateSet> columnPredicateSets = copyVector(columnPredicates);
    if (!columnPredicateSets.empty()) {
        // Since we insert a nbr column. We need to pad an empty nbr column predicate set.
        columnPredicateSets.insert(columnPredicateSets.begin(), ColumnPredicateSet());
    }
    auto tableInfo = ScanRelTableInfo(relTable, std::move(columnPredicateSets), direction);
    // We always should scan nbrID from relTable. This is not a property in the schema label, so
    // cannot be bound to a column in the front-end.
    auto nbrColumnID = shouldScanNbrID ? NBR_ID_COLUMN_ID : INVALID_COLUMN_ID;
    tableInfo.addColumnInfo(nbrColumnID, ColumnCaster(LogicalType::INTERNAL_ID()));
    auto binder = Binder(clientContext);
    auto expressionBinder = ExpressionBinder(&binder, clientContext);
    for (auto& expr : properties) {
        auto& property = expr->constCast<PropertyExpression>();
        if (property.hasProperty(tableEntry.getTableID())) {
            auto propertyName = property.getPropertyName();
            auto& columnType = tableEntry.getProperty(propertyName).getType();
            auto columnCaster = ColumnCaster(columnType.copy());
            if (property.getDataType() != columnType) {
                auto columnExpr = std::make_shared<PropertyExpression>(property);
                columnExpr->dataType = columnType.copy();
                columnCaster.setCastExpr(
                    expressionBinder.forceCast(columnExpr, property.getDataType()));
            }
            tableInfo.addColumnInfo(tableEntry.getColumnID(propertyName), std::move(columnCaster));
        } else {
            tableInfo.addColumnInfo(INVALID_COLUMN_ID, ColumnCaster(LogicalType::ANY()));
        }
    }
    return tableInfo;
}

static bool isRelTableQualifies(ExtendDirection direction, table_id_t srcTableID,
    table_id_t dstTableID, table_id_t boundNodeTableID, const table_id_set_t& nbrTableISet) {
    switch (direction) {
    case ExtendDirection::FWD: {
        return srcTableID == boundNodeTableID && nbrTableISet.contains(dstTableID);
    }
    case ExtendDirection::BWD: {
        return dstTableID == boundNodeTableID && nbrTableISet.contains(srcTableID);
    }
    default:
        KU_UNREACHABLE;
    }
}

static std::vector<ScanRelTableInfo> populateRelTableCollectionScanner(table_id_t boundNodeTableID,
    const table_id_set_t& nbrTableISet, const RelGroupCatalogEntry& entry,
    ExtendDirection extendDirection, bool shouldScanNbrID, const expression_vector& properties,
    const std::vector<ColumnPredicateSet>& columnPredicates, main::ClientContext* clientContext) {
    std::vector<ScanRelTableInfo> scanInfos;
    const auto storageManager = StorageManager::Get(*clientContext);
    for (auto& info : entry.getRelEntryInfos()) {
        auto srcTableID = info.nodePair.srcTableID;
        auto dstTableID = info.nodePair.dstTableID;
        auto relTable = storageManager->getTable(info.oid)->ptrCast<RelTable>();
        switch (extendDirection) {
        case ExtendDirection::FWD: {
            if (isRelTableQualifies(ExtendDirection::FWD, srcTableID, dstTableID, boundNodeTableID,
                    nbrTableISet)) {
                scanInfos.push_back(getRelTableScanInfo(entry, RelDataDirection::FWD, relTable,
                    shouldScanNbrID, properties, columnPredicates, clientContext));
            }
        } break;
        case ExtendDirection::BWD: {
            if (isRelTableQualifies(ExtendDirection::BWD, srcTableID, dstTableID, boundNodeTableID,
                    nbrTableISet)) {
                scanInfos.push_back(getRelTableScanInfo(entry, RelDataDirection::BWD, relTable,
                    shouldScanNbrID, properties, columnPredicates, clientContext));
            }
        } break;
        case ExtendDirection::BOTH: {
            if (isRelTableQualifies(ExtendDirection::FWD, srcTableID, dstTableID, boundNodeTableID,
                    nbrTableISet)) {
                scanInfos.push_back(getRelTableScanInfo(entry, RelDataDirection::FWD, relTable,
                    shouldScanNbrID, properties, columnPredicates, clientContext));
            }
            if (isRelTableQualifies(ExtendDirection::BWD, srcTableID, dstTableID, boundNodeTableID,
                    nbrTableISet)) {
                scanInfos.push_back(getRelTableScanInfo(entry, RelDataDirection::BWD, relTable,
                    shouldScanNbrID, properties, columnPredicates, clientContext));
            }
        } break;
        default:
            KU_UNREACHABLE;
        }
    }
    return scanInfos;
}

static bool scanSingleRelTable(const RelExpression& rel, const NodeExpression& boundNode,
    ExtendDirection extendDirection) {
    return !rel.isMultiLabeled() && !boundNode.isMultiLabeled() &&
           extendDirection != ExtendDirection::BOTH;
}

std::unique_ptr<PhysicalOperator> PlanMapper::mapExtend(const LogicalOperator* logicalOperator) {
    auto extend = logicalOperator->constPtrCast<LogicalExtend>();
    auto outFSchema = extend->getSchema();
    auto inFSchema = extend->getChild(0)->getSchema();
    auto boundNode = extend->getBoundNode();
    auto nbrNode = extend->getNbrNode();
    auto rel = extend->getRel();
    auto extendDirection = extend->getDirection();
    auto prevOperator = mapOperator(logicalOperator->getChild(0).get());
    auto inNodeIDPos = getDataPos(*boundNode->getInternalID(), *inFSchema);
    std::vector<DataPos> outVectorsPos;
    auto outNodeIDPos = getDataPos(*nbrNode->getInternalID(), *outFSchema);
    outVectorsPos.push_back(outNodeIDPos);
    for (auto& expression : extend->getProperties()) {
        outVectorsPos.push_back(getDataPos(*expression, *outFSchema));
    }
    auto scanInfo = ScanOpInfo(inNodeIDPos, outVectorsPos);
    std::vector<std::string> tableNames;
    auto storageManager = StorageManager::Get(*clientContext);
    for (auto entry : rel->getEntries()) {
        tableNames.push_back(entry->getName());
    }
    auto printInfo = std::make_unique<ScanRelTablePrintInfo>(tableNames, extend->getProperties(),
        boundNode, rel, nbrNode, extendDirection, rel->getVariableName());
    if (scanSingleRelTable(*rel, *boundNode, extendDirection)) {
        KU_ASSERT(rel->getNumEntries() == 1);
        auto entry = rel->getEntry(0)->ptrCast<RelGroupCatalogEntry>();
        auto relDataDirection = ExtendDirectionUtil::getRelDataDirection(extendDirection);
        auto entryInfo = entry->getSingleRelEntryInfo();
        auto relTable = storageManager->getTable(entryInfo.oid)->ptrCast<RelTable>();
        auto scanRelInfo =
            getRelTableScanInfo(*entry, relDataDirection, relTable, extend->shouldScanNbrID(),
                extend->getProperties(), extend->getPropertyPredicates(), clientContext);
        return std::make_unique<ScanRelTable>(std::move(scanInfo), std::move(scanRelInfo),
            std::move(prevOperator), getOperatorID(), printInfo->copy());
    }
    // map to generic extend
    auto directionInfo = DirectionInfo();
    directionInfo.extendFromSource = extend->extendFromSourceNode();
    if (rel->hasDirectionExpr()) {
        directionInfo.directionPos = getDataPos(*rel->getDirectionExpr(), *outFSchema);
    }
    table_id_map_t<RelTableCollectionScanner> scanners;
    for (auto boundNodeTableID : boundNode->getTableIDs()) {
        for (auto entry : rel->getEntries()) {
            auto& relGroupEntry = entry->constCast<RelGroupCatalogEntry>();
            auto scanInfos =
                populateRelTableCollectionScanner(boundNodeTableID, nbrNode->getTableIDsSet(),
                    relGroupEntry, extendDirection, extend->shouldScanNbrID(),
                    extend->getProperties(), extend->getPropertyPredicates(), clientContext);
            if (scanInfos.empty()) {
                continue;
            }
            if (scanners.contains(boundNodeTableID)) {
                scanners.at(boundNodeTableID).addRelInfos(std::move(scanInfos));
            } else {
                scanners.insert(
                    {boundNodeTableID, RelTableCollectionScanner(std::move(scanInfos))});
            }
        }
    }
    return std::make_unique<ScanMultiRelTable>(std::move(scanInfo), std::move(directionInfo),
        std::move(scanners), std::move(prevOperator), getOperatorID(), printInfo->copy());
}

} // namespace processor
} // namespace lbug
