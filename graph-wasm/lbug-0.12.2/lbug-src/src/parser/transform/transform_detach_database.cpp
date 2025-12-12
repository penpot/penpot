#include "parser/detach_database.h"
#include "parser/transformer.h"

namespace lbug {
namespace parser {

std::unique_ptr<Statement> Transformer::transformDetachDatabase(
    CypherParser::KU_DetachDatabaseContext& ctx) {
    auto dbName = transformSchemaName(*ctx.oC_SchemaName());
    return std::make_unique<DetachDatabase>(std::move(dbName));
}

} // namespace parser
} // namespace lbug
