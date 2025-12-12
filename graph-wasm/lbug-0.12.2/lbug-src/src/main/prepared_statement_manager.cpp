#include "main/prepared_statement_manager.h"

#include "common/assert.h"
#include "main/prepared_statement.h"

namespace lbug {
namespace main {

CachedPreparedStatementManager::CachedPreparedStatementManager() = default;

CachedPreparedStatementManager::~CachedPreparedStatementManager() = default;

std::string CachedPreparedStatementManager::addStatement(
    std::unique_ptr<CachedPreparedStatement> statement) {
    std::unique_lock lck{mtx};
    auto idx = std::to_string(currentIdx);
    currentIdx++;
    statementMap.insert({idx, std::move(statement)});
    return idx;
}

CachedPreparedStatement* CachedPreparedStatementManager::getCachedStatement(
    const std::string& name) const {
    KU_ASSERT(containsStatement(name));
    return statementMap.at(name).get();
}

} // namespace main
} // namespace lbug
