#include "extension/extension.h"

#include "common/exception/io.h"
#include "common/string_format.h"
#include "common/string_utils.h"
#include "common/system_message.h"
#include "main/client_context.h"
#include "main/database.h"
#include "storage/storage_manager.h"

#ifdef _WIN32

#include "windows.h"
#define RTLD_NOW 0
#define RTLD_LOCAL 0

#else
#include <dlfcn.h>
#endif

namespace lbug {
namespace extension {

std::string getOS() {
    std::string os = "linux";
#if !defined(_GLIBCXX_USE_CXX11_ABI) || _GLIBCXX_USE_CXX11_ABI == 0
    if (os == "linux") {
        os = "linux_old";
    }
#endif
#ifdef _WIN32
    os = "win";
#elif defined(__APPLE__)
    os = "osx";
#endif
    return os;
}

std::string getArch() {
    std::string arch = "amd64";
#if defined(__i386__) || defined(_M_IX86)
    arch = "x86";
#elif defined(__aarch64__) || defined(__ARM_ARCH_ISA_A64)
    arch = "arm64";
#endif
    return arch;
}

std::string getPlatform() {
    return getOS() + "_" + getArch();
}

static ExtensionRepoInfo getExtensionRepoInfo(std::string& extensionURL) {
    common::StringUtils::replaceAll(extensionURL, "http://", "");
    auto hostNamePos = extensionURL.find('/');
    auto hostName = extensionURL.substr(0, hostNamePos);
    auto hostURL = "http://" + hostName;
    auto hostPath = extensionURL.substr(hostNamePos);
    return {hostPath, hostURL, extensionURL};
}

std::string ExtensionSourceUtils::toString(ExtensionSource source) {
    switch (source) {
    case ExtensionSource::OFFICIAL:
        return "OFFICIAL";
    case ExtensionSource::USER:
        return "USER";
    case ExtensionSource::STATIC_LINKED:
        return "STATIC LINK";
    default:
        KU_UNREACHABLE;
    }
}

static ExtensionRepoInfo getExtensionFilePath(const std::string& extensionName,
    const std::string& extensionRepo, const std::string& fileName) {
    auto extensionURL = common::stringFormat(ExtensionUtils::EXTENSION_FILE_REPO_PATH,
        extensionRepo, LBUG_EXTENSION_VERSION, getPlatform(), extensionName, fileName);
    return getExtensionRepoInfo(extensionURL);
}

ExtensionRepoInfo ExtensionUtils::getExtensionLibRepoInfo(const std::string& extensionName,
    const std::string& extensionRepo) {
    return getExtensionFilePath(extensionName, extensionRepo, getExtensionFileName(extensionName));
}

ExtensionRepoInfo ExtensionUtils::getExtensionLoaderRepoInfo(const std::string& extensionName,
    const std::string& extensionRepo) {
    return getExtensionFilePath(extensionName, extensionRepo,
        getExtensionFileName(extensionName + EXTENSION_LOADER_SUFFIX));
}

ExtensionRepoInfo ExtensionUtils::getExtensionInstallerRepoInfo(const std::string& extensionName,
    const std::string& extensionRepo) {
    return getExtensionFilePath(extensionName, extensionRepo,
        getExtensionFileName(extensionName + EXTENSION_INSTALLER_SUFFIX));
}

ExtensionRepoInfo ExtensionUtils::getSharedLibRepoInfo(const std::string& fileName,
    const std::string& extensionRepo) {
    auto extensionURL = common::stringFormat(SHARED_LIB_REPO, extensionRepo, LBUG_EXTENSION_VERSION,
        getPlatform(), fileName);
    return getExtensionRepoInfo(extensionURL);
}

std::string ExtensionUtils::getExtensionFileName(const std::string& name) {
    return common::stringFormat(EXTENSION_FILE_NAME, common::StringUtils::getLower(name),
        EXTENSION_FILE_SUFFIX);
}

std::string ExtensionUtils::getLocalPathForExtensionLib(main::ClientContext* context,
    const std::string& extensionName) {
    return common::stringFormat("{}/{}", getLocalDirForExtension(context, extensionName),
        getExtensionFileName(extensionName));
}

std::string ExtensionUtils::getLocalPathForExtensionLoader(main::ClientContext* context,
    const std::string& extensionName) {
    return common::stringFormat("{}/{}", getLocalDirForExtension(context, extensionName),
        getExtensionFileName(extensionName + EXTENSION_LOADER_SUFFIX));
}

std::string ExtensionUtils::getLocalPathForExtensionInstaller(main::ClientContext* context,
    const std::string& extensionName) {
    return common::stringFormat("{}/{}", getLocalDirForExtension(context, extensionName),
        getExtensionFileName(extensionName + EXTENSION_INSTALLER_SUFFIX));
}

std::string ExtensionUtils::getLocalDirForExtension(main::ClientContext* context,
    const std::string& extensionName) {
    return common::stringFormat("{}{}", context->getExtensionDir(), extensionName);
}

std::string ExtensionUtils::appendLibSuffix(const std::string& libName) {
    auto os = getOS();
    std::string suffix;
    if (os == "linux" || os == "linux_old") {
        suffix = "so";
    } else if (os == "osx") {
        suffix = "dylib";
    } else {
        KU_UNREACHABLE;
    }
    return common::stringFormat("{}.{}", libName, suffix);
}

std::string ExtensionUtils::getLocalPathForSharedLib(main::ClientContext* context,
    const std::string& libName) {
    return common::stringFormat("{}common/{}", context->getExtensionDir(), libName);
}

std::string ExtensionUtils::getLocalPathForSharedLib(main::ClientContext* context) {
    return common::stringFormat("{}common/", context->getExtensionDir());
}

bool ExtensionUtils::isOfficialExtension(const std::string& extension) {
    auto extensionUpperCase = common::StringUtils::getUpper(extension);
    for (auto& officialExtension : OFFICIAL_EXTENSION) {
        if (officialExtension == extensionUpperCase) {
            return true;
        }
    }
    return false;
}

void ExtensionUtils::registerIndexType(main::Database& database, storage::IndexType type) {
    database.getStorageManager()->registerIndexType(std::move(type));
}

ExtensionLibLoader::ExtensionLibLoader(const std::string& extensionName, const std::string& path)
    : extensionName{extensionName} {
    libHdl = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (libHdl == nullptr) {
        throw common::IOException(common::stringFormat(
            "Failed to load library: {} which is needed by extension: {}.\nError: {}.", path,
            extensionName, common::dlErrMessage()));
    }
}

ext_load_func_t ExtensionLibLoader::getLoadFunc() {
    return (ext_load_func_t)getDynamicLibFunc(EXTENSION_LOAD_FUNC_NAME);
}

ext_init_func_t ExtensionLibLoader::getInitFunc() {
    return (ext_init_func_t)getDynamicLibFunc(EXTENSION_INIT_FUNC_NAME);
}

ext_name_func_t ExtensionLibLoader::getNameFunc() {
    return (ext_name_func_t)getDynamicLibFunc(EXTENSION_NAME_FUNC_NAME);
}

ext_install_func_t ExtensionLibLoader::getInstallFunc() {
    return (ext_install_func_t)getDynamicLibFunc(EXTENSION_INSTALL_FUNC_NAME);
}

void ExtensionLibLoader::unload() {
    KU_ASSERT(libHdl != nullptr);
    dlclose(libHdl);
    libHdl = nullptr;
}

void* ExtensionLibLoader::getDynamicLibFunc(const std::string& funcName) {
    KU_ASSERT(libHdl != nullptr);
    auto sym = dlsym(libHdl, funcName.c_str());
    if (sym == nullptr) {
        throw common::IOException(
            common::stringFormat("Failed to load {} function in extension {}.\nError: {}", funcName,
                extensionName, common::dlErrMessage()));
    }
    return sym;
}

#ifdef _WIN32
std::wstring utf8ToUnicode(const char* input) {
    uint32_t result;

    result = MultiByteToWideChar(CP_UTF8, 0, input, -1, nullptr, 0);
    if (result == 0) {
        throw common::IOException("Failure in MultiByteToWideChar");
    }
    auto buffer = std::make_unique<wchar_t[]>(result);
    result = MultiByteToWideChar(CP_UTF8, 0, input, -1, buffer.get(), result);
    if (result == 0) {
        throw common::IOException("Failure in MultiByteToWideChar");
    }
    return std::wstring(buffer.get(), result);
}

void* dlopen(const char* file, int /*mode*/) {
    KU_ASSERT(file);
    auto fpath = utf8ToUnicode(file);
    return (void*)LoadLibraryW(fpath.c_str());
}

void* dlsym(void* handle, const char* name) {
    KU_ASSERT(handle);
    return (void*)GetProcAddress((HINSTANCE)handle, name);
}

void dlclose(void* handle) {
    KU_ASSERT(handle);
    FreeLibrary((HINSTANCE)handle);
}
#endif

} // namespace extension
} // namespace lbug
