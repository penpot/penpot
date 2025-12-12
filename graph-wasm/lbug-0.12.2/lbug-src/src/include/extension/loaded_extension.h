#pragma once

#include <string>

#include "extension.h"

namespace lbug {
namespace extension {

class LoadedExtension {

public:
    LoadedExtension(std::string extensionName, std::string fullPath, ExtensionSource source)
        : extensionName{std::move(extensionName)}, fullPath{std::move(fullPath)}, source{source} {}

    std::string getExtensionName() const { return extensionName; }

    std::string getFullPath() const { return fullPath; }

    ExtensionSource getSource() const { return source; }

    std::string toCypher();

private:
    std::string extensionName;
    std::string fullPath;
    ExtensionSource source;
};

} // namespace extension
} // namespace lbug
