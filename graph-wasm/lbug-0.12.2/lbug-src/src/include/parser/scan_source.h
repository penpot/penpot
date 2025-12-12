#pragma once

#include <memory>
#include <string>
#include <vector>

#include "common/copy_constructors.h"
#include "common/enums/scan_source_type.h"
#include "expression/parsed_expression.h"
#include "parser/statement.h"

namespace lbug {
namespace parser {

struct BaseScanSource {
    common::ScanSourceType type;

    explicit BaseScanSource(common::ScanSourceType type) : type{type} {}
    virtual ~BaseScanSource() = default;
    DELETE_COPY_AND_MOVE(BaseScanSource);

    template<class TARGET>
    TARGET* ptrCast() {
        return common::ku_dynamic_cast<TARGET*>(this);
    }
    template<class TARGET>
    const TARGET* constPtrCast() const {
        return common::ku_dynamic_cast<const TARGET*>(this);
    }
};

struct ParameterScanSource : public BaseScanSource {
    std::unique_ptr<ParsedExpression> paramExpression;

    explicit ParameterScanSource(std::unique_ptr<ParsedExpression> paramExpression)
        : BaseScanSource{common::ScanSourceType::PARAM},
          paramExpression{std::move(paramExpression)} {}
};

struct FileScanSource : public BaseScanSource {
    std::vector<std::string> filePaths;

    explicit FileScanSource(std::vector<std::string> paths)
        : BaseScanSource{common::ScanSourceType::FILE}, filePaths{std::move(paths)} {}
};

struct ObjectScanSource : public BaseScanSource {
    // If multiple object presents, assuming they have a nested structure.
    // E.g. for postgres.person, objectNames should be [postgres, person]
    std::vector<std::string> objectNames;

    explicit ObjectScanSource(std::vector<std::string> objectNames)
        : BaseScanSource{common::ScanSourceType::OBJECT}, objectNames{std::move(objectNames)} {}
};

struct QueryScanSource : public BaseScanSource {
    std::unique_ptr<Statement> statement;

    explicit QueryScanSource(std::unique_ptr<Statement> statement)
        : BaseScanSource{common::ScanSourceType::QUERY}, statement{std::move(statement)} {}
};

struct TableFuncScanSource : public BaseScanSource {
    std::unique_ptr<ParsedExpression> functionExpression = nullptr;

    explicit TableFuncScanSource(std::unique_ptr<ParsedExpression> functionExpression)
        : BaseScanSource{common::ScanSourceType::TABLE_FUNC},
          functionExpression{std::move(functionExpression)} {}
};

} // namespace parser
} // namespace lbug
