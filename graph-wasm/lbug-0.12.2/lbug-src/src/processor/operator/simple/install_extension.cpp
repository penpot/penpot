#include "processor/operator/simple/install_extension.h"

#include "common/string_format.h"
#include "processor/execution_context.h"
#include "storage/buffer_manager/memory_manager.h"

namespace lbug {
namespace processor {

using namespace lbug::common;
using namespace lbug::extension;

void InstallExtension::setOutputMessage(bool installed, storage::MemoryManager* memoryManager) {
    if (info.forceInstall) {
        appendMessage(
            stringFormat("Extension: {} updated from the repo: {}.", info.name, info.repo),
            memoryManager);
        return;
    }
    if (installed) {
        appendMessage(
            stringFormat("Extension: {} installed from the repo: {}.", info.name, info.repo),
            memoryManager);
    } else {
        appendMessage(
            stringFormat(
                "Extension: {} is already installed.\nTo update it, you can run: UPDATE {}.",
                info.name, info.name),
            memoryManager);
    }
}

void InstallExtension::executeInternal(ExecutionContext* context) {
    auto clientContext = context->clientContext;
    ExtensionInstaller installer{info, *clientContext};
    bool installResult = installer.install();
    setOutputMessage(installResult, storage::MemoryManager::Get(*clientContext));
    if (info.forceInstall) {
        KU_ASSERT(installResult);
    }
}

} // namespace processor
} // namespace lbug
