#include "binder/binder.h"
#include "binder/bound_use_database.h"
#include "parser/use_database.h"

namespace lbug {
namespace binder {

std::unique_ptr<BoundStatement> Binder::bindUseDatabase(const parser::Statement& statement) {
    auto useDatabase = statement.constCast<parser::UseDatabase>();
    return std::make_unique<BoundUseDatabase>(useDatabase.getDBName());
}

} // namespace binder
} // namespace lbug
