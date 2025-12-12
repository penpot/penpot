#pragma once

#include "catalog/catalog.h"

namespace lbug {
namespace extension {

class LBUG_API CatalogExtension : public catalog::Catalog {
public:
    CatalogExtension() : Catalog() {}

    virtual void init() = 0;

    void invalidateCache();
};

} // namespace extension
} // namespace lbug
