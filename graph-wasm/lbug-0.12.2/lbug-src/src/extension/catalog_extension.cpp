#include "extension/catalog_extension.h"

namespace lbug {
namespace extension {

void CatalogExtension::invalidateCache() {
    tables = std::make_unique<catalog::CatalogSet>();
    init();
}

} // namespace extension
} // namespace lbug
