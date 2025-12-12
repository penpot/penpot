#pragma once

#include "catalog_entry.h"
#include "function/function.h"

namespace lbug {
namespace catalog {

class LBUG_API FunctionCatalogEntry : public CatalogEntry {
public:
    //===--------------------------------------------------------------------===//
    // constructors
    //===--------------------------------------------------------------------===//
    FunctionCatalogEntry() = default;
    FunctionCatalogEntry(CatalogEntryType entryType, std::string name,
        function::function_set functionSet);

    //===--------------------------------------------------------------------===//
    // getters & setters
    //===--------------------------------------------------------------------===//
    const function::function_set& getFunctionSet() const { return functionSet; }

    //===--------------------------------------------------------------------===//
    // serialization & deserialization
    //===--------------------------------------------------------------------===//
    // We always register functions while initializing the catalog, so we don't have to
    // serialize functions.
    void serialize(common::Serializer& /*serializer*/) const override { KU_UNREACHABLE; }

protected:
    function::function_set functionSet;
};

} // namespace catalog
} // namespace lbug
