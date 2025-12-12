#pragma once

#include "common/system_config.h"
#include "storage/table/column.h"

namespace lbug {
namespace storage {

// Page size must be aligned to 8 byte chunks for the 64-bit NullMask algorithms to work
// without the possibility of memory errors from reading/writing off the end of a page.
static_assert(common::LBUG_PAGE_SIZE % 8 == 0);

class NullColumn final : public Column {
public:
    NullColumn(const std::string& name, FileHandle* dataFH, MemoryManager* mm,
        ShadowFile* shadowFile, bool enableCompression);
};

} // namespace storage
} // namespace lbug
