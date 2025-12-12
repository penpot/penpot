#include "processor/operator/macro/create_macro.h"

#include "common/string_format.h"
#include "processor/execution_context.h"
#include "storage/buffer_manager/memory_manager.h"
#include "transaction/transaction.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

std::string CreateMacroPrintInfo::toString() const {
    return macroName;
}

void CreateMacro::executeInternal(ExecutionContext* context) {
    auto clientContext = context->clientContext;
    auto catalog = catalog::Catalog::Get(*clientContext);
    auto transaction = transaction::Transaction::Get(*clientContext);
    catalog->addScalarMacroFunction(transaction, info.macroName, info.macro->copy());
    appendMessage(stringFormat("Macro: {} has been created.", info.macroName),
        storage::MemoryManager::Get(*clientContext));
}

} // namespace processor
} // namespace lbug
