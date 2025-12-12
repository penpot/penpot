#pragma once

#include "loaded_extension.h"
#include "storage/storage_extension.h"

namespace lbug {
namespace main {}
namespace extension {

struct ExtensionEntry {
    const char* name;
    const char* extensionName;
};

class ExtensionManager {
public:
    void loadExtension(const std::string& path, main::ClientContext* context);

    LBUG_API std::string toCypher();

    LBUG_API void addExtensionOption(std::string name, common::LogicalTypeID type,
        common::Value defaultValue, bool isConfidential);

    const main::ExtensionOption* getExtensionOption(std::string name) const;

    LBUG_API void registerStorageExtension(std::string name,
        std::unique_ptr<storage::StorageExtension> storageExtension);

    std::vector<storage::StorageExtension*> getStorageExtensions();

    LBUG_API const std::vector<LoadedExtension>& getLoadedExtensions() const {
        return loadedExtensions;
    }

    static std::optional<ExtensionEntry> lookupExtensionsByFunctionName(
        std::string_view functionName);
    static std::optional<ExtensionEntry> lookupExtensionsByTypeName(std::string_view typeName);

    void autoLoadLinkedExtensions(main::ClientContext* context);

    LBUG_API static ExtensionManager* Get(const main::ClientContext& context);

private:
    std::vector<LoadedExtension> loadedExtensions;
    std::unordered_map<std::string, main::ExtensionOption> extensionOptions;
    common::case_insensitive_map_t<std::unique_ptr<storage::StorageExtension>> storageExtensions;
};

} // namespace extension
} // namespace lbug
