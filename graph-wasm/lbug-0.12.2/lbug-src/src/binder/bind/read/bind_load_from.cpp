#include "binder/binder.h"
#include "binder/bound_scan_source.h"
#include "binder/expression/expression_util.h"
#include "binder/query/reading_clause/bound_load_from.h"
#include "common/exception/binder.h"
#include "parser/query/reading_clause/load_from.h"
#include "parser/scan_source.h"

using namespace lbug::function;
using namespace lbug::common;
using namespace lbug::parser;
using namespace lbug::catalog;

namespace lbug {
namespace binder {

std::unique_ptr<BoundReadingClause> Binder::bindLoadFrom(const ReadingClause& readingClause) {
    auto& loadFrom = readingClause.constCast<LoadFrom>();
    auto source = loadFrom.getSource();
    std::unique_ptr<BoundLoadFrom> boundLoadFrom;
    std::vector<std::string> columnNames;
    std::vector<LogicalType> columnTypes;
    for (auto& [name, type] : loadFrom.getColumnDefinitions()) {
        columnNames.push_back(name);
        columnTypes.push_back(LogicalType::convertFromString(type, clientContext));
    }
    switch (source->type) {
    case ScanSourceType::OBJECT: {
        auto objectSource = source->ptrCast<ObjectScanSource>();
        auto boundScanSource = bindObjectScanSource(*objectSource, loadFrom.getParsingOptions(),
            columnNames, columnTypes);
        auto& scanInfo = boundScanSource->constCast<BoundTableScanSource>().info;
        boundLoadFrom = std::make_unique<BoundLoadFrom>(scanInfo.copy());
    } break;
    case ScanSourceType::FILE: {
        auto boundScanSource =
            bindFileScanSource(*source, loadFrom.getParsingOptions(), columnNames, columnTypes);
        auto& scanInfo = boundScanSource->constCast<BoundTableScanSource>().info;
        boundLoadFrom = std::make_unique<BoundLoadFrom>(scanInfo.copy());
    } break;
    case ScanSourceType::PARAM: {
        auto boundScanSource = bindParameterScanSource(*source, loadFrom.getParsingOptions(),
            columnNames, columnTypes);
        auto& scanInfo = boundScanSource->constCast<BoundTableScanSource>().info;
        boundLoadFrom = std::make_unique<BoundLoadFrom>(scanInfo.copy());
    } break;
    default:
        throw BinderException(stringFormat("LOAD FROM subquery is not supported."));
    }
    if (!columnTypes.empty()) {
        auto info = boundLoadFrom->getInfo();
        for (auto i = 0u; i < columnTypes.size(); ++i) {
            ExpressionUtil::validateDataType(*info->bindData->columns[i], columnTypes[i]);
        }
    }
    if (loadFrom.hasWherePredicate()) {
        auto wherePredicate = bindWhereExpression(*loadFrom.getWherePredicate());
        boundLoadFrom->setPredicate(std::move(wherePredicate));
    }
    return boundLoadFrom;
}

} // namespace binder
} // namespace lbug
