#include "parser/attach_database.h"
#include "parser/transformer.h"

namespace lbug {
namespace parser {

std::unique_ptr<Statement> Transformer::transformAttachDatabase(
    CypherParser::KU_AttachDatabaseContext& ctx) {
    auto dbPath = transformStringLiteral(*ctx.StringLiteral());
    auto dbAlias = ctx.oC_SchemaName() ? transformSchemaName(*ctx.oC_SchemaName()) : "";
    auto dbType = transformSymbolicName(*ctx.oC_SymbolicName());
    auto attachOption = ctx.kU_Options() ? transformOptions(*ctx.kU_Options()) : options_t{};
    AttachInfo attachInfo{std::move(dbPath), std::move(dbAlias), std::move(dbType),
        std::move(attachOption)};
    return std::make_unique<AttachDatabase>(std::move(attachInfo));
}

} // namespace parser
} // namespace lbug
