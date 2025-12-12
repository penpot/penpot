#include "parser/port_db.h"
#include "parser/transformer.h"

using namespace lbug::common;

namespace lbug {
namespace parser {

std::unique_ptr<Statement> Transformer::transformExportDatabase(
    CypherParser::KU_ExportDatabaseContext& ctx) {
    std::string filePath = transformStringLiteral(*ctx.StringLiteral());
    auto exportDB = std::make_unique<ExportDB>(std::move(filePath));
    if (ctx.kU_Options()) {
        exportDB->setParsingOption(transformOptions(*ctx.kU_Options()));
    }
    return exportDB;
}

std::unique_ptr<Statement> Transformer::transformImportDatabase(
    CypherParser::KU_ImportDatabaseContext& ctx) {
    std::string filePath = transformStringLiteral(*ctx.StringLiteral());
    return std::make_unique<ImportDB>(std::move(filePath));
}

} // namespace parser
} // namespace lbug
