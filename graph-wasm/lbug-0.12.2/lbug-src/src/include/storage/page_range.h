#pragma once

#include "common/types/types.h"

namespace lbug::storage {

struct PageRange {
    PageRange() : startPageIdx(common::INVALID_PAGE_IDX), numPages(0){};
    PageRange(common::page_idx_t startPageIdx, common::page_idx_t numPages)
        : startPageIdx(startPageIdx), numPages(numPages) {}

    PageRange subrange(common::page_idx_t newStartPage) const {
        KU_ASSERT(newStartPage <= numPages);
        return {startPageIdx + newStartPage, numPages - newStartPage};
    }

    common::page_idx_t startPageIdx;
    common::page_idx_t numPages;
};
} // namespace lbug::storage
