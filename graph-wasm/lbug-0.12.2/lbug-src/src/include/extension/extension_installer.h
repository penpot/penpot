#pragma once

#include <string>

#include "common/api.h"
#include "extension.h"

namespace lbug {
namespace main {
class ClientContext;
}
namespace extension {

struct InstallExtensionInfo {
    std::string name;
    std::string repo;
    bool forceInstall;

    InstallExtensionInfo(std::string name, std::string repo, bool forceInstall)
        : name{std::move(name)}, repo{std::move(repo)}, forceInstall{forceInstall} {}
};

class LBUG_API ExtensionInstaller {
public:
    ExtensionInstaller(const InstallExtensionInfo& info, main::ClientContext& context)
        : info{info}, context{context} {}

    virtual ~ExtensionInstaller() = default;

    virtual bool install();

protected:
    void tryDownloadExtensionFile(const ExtensionRepoInfo& info, const std::string& localFilePath);

private:
    bool installExtension();
    void installDependencies();

protected:
    const InstallExtensionInfo& info;
    main::ClientContext& context;
};

} // namespace extension
} // namespace lbug
