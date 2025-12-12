#include "binder/binder.h"
#include "binder/bound_detach_database.h"
#include "parser/detach_database.h"

namespace lbug {
namespace binder {

std::unique_ptr<BoundStatement> Binder::bindDetachDatabase(const parser::Statement& statement) {
    auto& detachDatabase = statement.constCast<parser::DetachDatabase>();
    return std::make_unique<BoundDetachDatabase>(detachDatabase.getDBName());
}

} // namespace binder
} // namespace lbug
