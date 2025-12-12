#include "binder/bound_scan_source.h"

using namespace lbug::common;

namespace lbug {
namespace binder {

expression_vector BoundTableScanSource::getWarningColumns() const {
    expression_vector warningDataExprs;
    auto& columns = info.bindData->columns;
    switch (type) {
    case ScanSourceType::FILE: {
        auto bindData = info.bindData->constPtrCast<function::ScanFileBindData>();
        for (auto i = bindData->numWarningDataColumns; i >= 1; --i) {
            KU_ASSERT(i < columns.size());
            warningDataExprs.push_back(columns[columns.size() - i]);
        }
    } break;
    default:
        break;
    }
    return warningDataExprs;
}

bool BoundTableScanSource::getIgnoreErrorsOption() const {
    return info.bindData->getIgnoreErrorsOption();
}

bool BoundQueryScanSource::getIgnoreErrorsOption() const {
    return info.options.contains(CopyConstants::IGNORE_ERRORS_OPTION_NAME) ?
               info.options.at(CopyConstants::IGNORE_ERRORS_OPTION_NAME).getValue<bool>() :
               CopyConstants::DEFAULT_IGNORE_ERRORS;
}

} // namespace binder
} // namespace lbug
