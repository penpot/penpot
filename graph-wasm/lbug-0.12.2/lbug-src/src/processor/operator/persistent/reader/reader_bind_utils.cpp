#include "processor/operator/persistent/reader/reader_bind_utils.h"

#include "common/exception/binder.h"
#include "common/string_format.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

void ReaderBindUtils::validateNumColumns(uint32_t expectedNumber, uint32_t detectedNumber) {
    if (detectedNumber == 0) {
        return; // Empty CSV. Continue processing.
    }
    if (expectedNumber != detectedNumber) {
        throw common::BinderException(common::stringFormat(
            "Number of columns mismatch. Expected {} but got {}.", expectedNumber, detectedNumber));
    }
}

void ReaderBindUtils::validateColumnTypes(const std::vector<std::string>& columnNames,
    const std::vector<common::LogicalType>& expectedColumnTypes,
    const std::vector<common::LogicalType>& detectedColumnTypes) {
    KU_ASSERT(expectedColumnTypes.size() == detectedColumnTypes.size());
    for (auto i = 0u; i < expectedColumnTypes.size(); ++i) {
        if (expectedColumnTypes[i] != detectedColumnTypes[i]) {
            throw common::BinderException(common::stringFormat(
                "Column `{}` type mismatch. Expected {} but got {}.", columnNames[i],
                expectedColumnTypes[i].toString(), detectedColumnTypes[i].toString()));
        }
    }
}

void ReaderBindUtils::resolveColumns(const std::vector<std::string>& expectedColumnNames,
    const std::vector<std::string>& detectedColumnNames,
    std::vector<std::string>& resultColumnNames,
    const std::vector<common::LogicalType>& expectedColumnTypes,
    const std::vector<common::LogicalType>& detectedColumnTypes,
    std::vector<common::LogicalType>& resultColumnTypes) {
    if (expectedColumnTypes.empty()) {
        resultColumnNames = detectedColumnNames;
        resultColumnTypes = LogicalType::copy(detectedColumnTypes);
    } else {
        validateNumColumns(expectedColumnTypes.size(), detectedColumnTypes.size());
        resultColumnNames = expectedColumnNames;
        resultColumnTypes = LogicalType::copy(expectedColumnTypes);
    }
}

} // namespace processor
} // namespace lbug
