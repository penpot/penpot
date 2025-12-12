#include "processor/operator/ddl/create_type.h"

#include "catalog/catalog.h"
#include "processor/execution_context.h"
#include "storage/buffer_manager/memory_manager.h"
#include "transaction/transaction.h"

using namespace lbug::catalog;
using namespace lbug::common;

namespace lbug {
namespace processor {

std::string CreateTypePrintInfo::toString() const {
    return typeName + " AS " + type;
}

void CreateType::executeInternal(ExecutionContext* context) {
    auto clientContext = context->clientContext;
    auto transaction = transaction::Transaction::Get(*clientContext);
    Catalog::Get(*clientContext)->createType(transaction, name, type.copy());
    appendMessage(stringFormat("Type {}({}) has been created.", name, type.toString()),
        storage::MemoryManager::Get(*clientContext));
}

} // namespace processor
} // namespace lbug
