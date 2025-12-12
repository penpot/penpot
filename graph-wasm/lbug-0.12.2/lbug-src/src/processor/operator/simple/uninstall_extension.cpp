#include "processor/operator/simple/uninstall_extension.h"

#include "common/exception/runtime.h"
#include "common/file_system/virtual_file_system.h"
#include "common/string_format.h"
#include "extension/extension.h"
#include "main/client_context.h"
#include "processor/execution_context.h"
#include "storage/buffer_manager/memory_manager.h"

namespace lbug {
namespace processor {

using namespace lbug::common;
using namespace lbug::extension;

void UninstallExtension::executeInternal(ExecutionContext* context) {
    auto clientContext = context->clientContext;
    auto vfs = VirtualFileSystem::GetUnsafe(*clientContext);
    auto localLibFilePath = ExtensionUtils::getLocalPathForExtensionLib(clientContext, path);
    if (!vfs->fileOrPathExists(localLibFilePath)) {
        throw RuntimeException{
            stringFormat("Can not uninstall extension: {} since it has not been installed.", path)};
    }
    std::error_code errCode;
    if (!std::filesystem::remove_all(
            extension::ExtensionUtils::getLocalDirForExtension(clientContext, path), errCode)) {
        // LCOV_EXCL_START
        throw RuntimeException{
            stringFormat("An error occurred while uninstalling extension: {}. Error: {}.", path,
                errCode.message())};
        // LCOV_EXCL_STOP
    }
    appendMessage(stringFormat("Extension: {} has been uninstalled", path),
        storage::MemoryManager::Get(*clientContext));
}

} // namespace processor
} // namespace lbug
