#include "processor/operator/ddl/drop.h"

#include "catalog/catalog.h"
#include "catalog/catalog_entry/index_catalog_entry.h"
#include "catalog/catalog_entry/rel_group_catalog_entry.h"
#include "common/exception/binder.h"
#include "common/string_format.h"
#include "main/client_context.h"
#include "processor/execution_context.h"
#include "storage/buffer_manager/memory_manager.h"
#include "transaction/transaction.h"

using namespace lbug::catalog;
using namespace lbug::common;

namespace lbug {
namespace processor {

void Drop::executeInternal(ExecutionContext* context) {
    auto clientContext = context->clientContext;
    switch (dropInfo.dropType) {
    case DropType::SEQUENCE: {
        dropSequence(clientContext);
    } break;
    case DropType::TABLE: {
        dropTable(clientContext);
    } break;
    case DropType::MACRO: {
        dropMacro(clientContext);
    } break;
    default:
        KU_UNREACHABLE;
    }
}

void Drop::dropSequence(const main::ClientContext* context) {
    auto catalog = Catalog::Get(*context);
    auto transaction = transaction::Transaction::Get(*context);
    auto memoryManager = storage::MemoryManager::Get(*context);
    if (!catalog->containsSequence(transaction, dropInfo.name)) {
        auto message = stringFormat("Sequence {} does not exist.", dropInfo.name);
        switch (dropInfo.conflictAction) {
        case ConflictAction::ON_CONFLICT_DO_NOTHING: {
            appendMessage(message, memoryManager);
            return;
        }
        case ConflictAction::ON_CONFLICT_THROW: {
            throw BinderException(message);
        }
        default:
            KU_UNREACHABLE;
        }
    }
    catalog->dropSequence(transaction, dropInfo.name);
    appendMessage(stringFormat("Sequence {} has been dropped.", dropInfo.name), memoryManager);
}

void Drop::dropTable(const main::ClientContext* context) {
    auto catalog = Catalog::Get(*context);
    auto transaction = transaction::Transaction::Get(*context);
    auto memoryManager = storage::MemoryManager::Get(*context);
    if (!catalog->containsTable(transaction, dropInfo.name, context->useInternalCatalogEntry())) {
        auto message = stringFormat("Table {} does not exist.", dropInfo.name);
        switch (dropInfo.conflictAction) {
        case ConflictAction::ON_CONFLICT_DO_NOTHING: {
            appendMessage(message, memoryManager);
            return;
        }
        case ConflictAction::ON_CONFLICT_THROW: {
            throw BinderException(message);
        }
        default:
            KU_UNREACHABLE;
        }
    }
    auto entry = catalog->getTableCatalogEntry(transaction, dropInfo.name);
    switch (entry->getType()) {
    case CatalogEntryType::NODE_TABLE_ENTRY: {
        for (auto& indexEntry : catalog->getIndexEntries(transaction)) {
            if (indexEntry->getTableID() == entry->getTableID()) {
                throw BinderException(stringFormat(
                    "Cannot delete node table {} because it is referenced by index {}.",
                    entry->getName(), indexEntry->getIndexName()));
            }
        }
        for (auto& relEntry : catalog->getRelGroupEntries(transaction)) {
            if (relEntry->isParent(entry->getTableID())) {
                throw BinderException(stringFormat("Cannot delete node table {} because it is "
                                                   "referenced by relationship table {}.",
                    entry->getName(), relEntry->getName()));
            }
        }
    } break;
    case CatalogEntryType::REL_GROUP_ENTRY: {
        // Do nothing
    } break;
    default:
        KU_UNREACHABLE;
    }
    catalog->dropTableEntryAndIndex(transaction, dropInfo.name);
    appendMessage(stringFormat("Table {} has been dropped.", dropInfo.name), memoryManager);
}

void Drop::dropMacro(const main::ClientContext* context) {
    auto catalog = Catalog::Get(*context);
    auto transaction = transaction::Transaction::Get(*context);
    auto memoryManager = storage::MemoryManager::Get(*context);
    handleMacroExistence(context);
    catalog->dropMacro(transaction, dropInfo.name);
    appendMessage(stringFormat("Macro {} has been dropped.", dropInfo.name), memoryManager);
}

void Drop::handleMacroExistence(const main::ClientContext* context) {
    auto catalog = Catalog::Get(*context);
    auto transaction = transaction::Transaction::Get(*context);
    auto memoryManager = storage::MemoryManager::Get(*context);
    if (!catalog->containsMacro(transaction, dropInfo.name)) {
        auto message = stringFormat("Macro {} does not exist.", dropInfo.name);
        switch (dropInfo.conflictAction) {
        case ConflictAction::ON_CONFLICT_DO_NOTHING: {
            appendMessage(message, memoryManager);
            return;
        }
        case ConflictAction::ON_CONFLICT_THROW: {
            throw BinderException(message);
        }
        default:
            KU_UNREACHABLE;
        }
    }
}

} // namespace processor
} // namespace lbug
