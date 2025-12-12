#pragma once

#include <string>
#include <vector>

#include "common/arrow/arrow.h"
#include "common/arrow/arrow_nullmask_tree.h"

struct ArrowSchema;

namespace lbug {
namespace common {

struct ArrowSchemaHolder {
    std::vector<ArrowSchema> children;
    std::vector<ArrowSchema*> childrenPtrs;
    std::vector<std::vector<ArrowSchema>> nestedChildren;
    std::vector<std::vector<ArrowSchema*>> nestedChildrenPtr;
    std::vector<std::unique_ptr<char[]>> ownedTypeNames;
    std::vector<std::unique_ptr<char[]>> ownedMetadatas;
};

class ArrowConverter {
public:
    static std::unique_ptr<ArrowSchema> toArrowSchema(const std::vector<LogicalType>& dataTypes,
        const std::vector<std::string>& columnNames, bool fallbackExtensionTypes);

    static LogicalType fromArrowSchema(const ArrowSchema* schema);
    static void fromArrowArray(const ArrowSchema* schema, const ArrowArray* array,
        ValueVector& outputVector, ArrowNullMaskTree* mask, uint64_t srcOffset, uint64_t dstOffset,
        uint64_t count);
    static void fromArrowArray(const ArrowSchema* schema, const ArrowArray* array,
        ValueVector& outputVector);

private:
    static void initializeChild(ArrowSchema& child, const std::string& name = "");
    static void setArrowFormatForStruct(ArrowSchemaHolder& rootHolder, ArrowSchema& child,
        const LogicalType& dataType, bool fallbackExtensionTypes);
    static void setArrowFormatForUnion(ArrowSchemaHolder& rootHolder, ArrowSchema& child,
        const LogicalType& dataType, bool fallbackExtensionTypes);
    static void setArrowFormatForInternalID(ArrowSchemaHolder& rootHolder, ArrowSchema& child,
        const LogicalType& dataType, bool fallbackExtensionTypes);
    static void setArrowFormat(ArrowSchemaHolder& rootHolder, ArrowSchema& child,
        const LogicalType& dataType, bool fallbackExtensionTypes);
};

} // namespace common
} // namespace lbug
