#include "common/assert.h"
#include "parser/copy.h"
#include "parser/expression/parsed_literal_expression.h"
#include "parser/scan_source.h"
#include "parser/transformer.h"

using namespace lbug::common;

namespace lbug {
namespace parser {

std::unique_ptr<Statement> Transformer::transformCopyTo(CypherParser::KU_CopyTOContext& ctx) {
    std::string filePath = transformStringLiteral(*ctx.StringLiteral());
    auto regularQuery = transformQuery(*ctx.oC_Query());
    auto copyTo = std::make_unique<CopyTo>(std::move(filePath), std::move(regularQuery));
    if (ctx.kU_Options()) {
        copyTo->setParsingOption(transformOptions(*ctx.kU_Options()));
    }
    return copyTo;
}

std::unique_ptr<Statement> Transformer::transformCopyFrom(CypherParser::KU_CopyFromContext& ctx) {
    auto source = transformScanSource(*ctx.kU_ScanSource());
    auto tableName = transformSchemaName(*ctx.oC_SchemaName());
    auto copyFrom = std::make_unique<CopyFrom>(std::move(source), std::move(tableName));
    CopyFromColumnInfo info;
    info.inputColumnOrder = ctx.kU_ColumnNames();
    if (ctx.kU_ColumnNames()) {
        info.columnNames = transformColumnNames(*ctx.kU_ColumnNames());
    }
    if (ctx.kU_Options()) {
        copyFrom->setParsingOption(transformOptions(*ctx.kU_Options()));
    }
    copyFrom->setColumnInfo(std::move(info));
    return copyFrom;
}

std::unique_ptr<Statement> Transformer::transformCopyFromByColumn(
    CypherParser::KU_CopyFromByColumnContext& ctx) {
    auto source = std::make_unique<FileScanSource>(transformFilePaths(ctx.StringLiteral()));
    auto tableName = transformSchemaName(*ctx.oC_SchemaName());
    auto copyFrom = std::make_unique<CopyFrom>(std::move(source), std::move(tableName));
    copyFrom->setByColumn();
    return copyFrom;
}

std::vector<std::string> Transformer::transformColumnNames(
    CypherParser::KU_ColumnNamesContext& ctx) {
    std::vector<std::string> columnNames;
    for (auto& schemaName : ctx.oC_SchemaName()) {
        columnNames.push_back(transformSchemaName(*schemaName));
    }
    return columnNames;
}

std::vector<std::string> Transformer::transformFilePaths(
    const std::vector<antlr4::tree::TerminalNode*>& stringLiteral) {
    std::vector<std::string> csvFiles;
    csvFiles.reserve(stringLiteral.size());
    for (auto& csvFile : stringLiteral) {
        csvFiles.push_back(transformStringLiteral(*csvFile));
    }
    return csvFiles;
}

std::unique_ptr<BaseScanSource> Transformer::transformScanSource(
    CypherParser::KU_ScanSourceContext& ctx) {
    if (ctx.kU_FilePaths()) {
        auto filePaths = transformFilePaths(ctx.kU_FilePaths()->StringLiteral());
        return std::make_unique<FileScanSource>(std::move(filePaths));
    } else if (ctx.oC_Query()) {
        auto query = transformQuery(*ctx.oC_Query());
        return std::make_unique<QueryScanSource>(std::move(query));
    } else if (ctx.oC_Variable()) {
        std::vector<std::string> objectNames;
        objectNames.push_back(transformVariable(*ctx.oC_Variable()));
        if (ctx.oC_SchemaName()) {
            objectNames.push_back(transformSchemaName(*ctx.oC_SchemaName()));
        }
        return std::make_unique<ObjectScanSource>(std::move(objectNames));
    } else if (ctx.oC_FunctionInvocation()) {
        auto functionExpression = transformFunctionInvocation(*ctx.oC_FunctionInvocation());
        return std::make_unique<TableFuncScanSource>(std::move(functionExpression));
    } else if (ctx.oC_Parameter()) {
        auto paramExpression = transformParameterExpression(*ctx.oC_Parameter());
        return std::make_unique<ParameterScanSource>(std::move(paramExpression));
    }
    KU_UNREACHABLE;
}

options_t Transformer::transformOptions(CypherParser::KU_OptionsContext& ctx) {
    options_t options;
    for (auto loadOption : ctx.kU_Option()) {
        auto optionName = transformSymbolicName(*loadOption->oC_SymbolicName());
        // Check if the literal exists, otherwise set the value to true by default
        if (loadOption->oC_Literal()) {
            // If there is a literal, transform it and use it as the value
            options.emplace(optionName, transformLiteral(*loadOption->oC_Literal()));
        } else {
            // If no literal is provided, set the default value to true
            options.emplace(optionName,
                std::make_unique<ParsedLiteralExpression>(Value(true), "true"));
        }
    }
    return options;
}

} // namespace parser
} // namespace lbug
