#pragma once

#include "storage/page_allocator.h"

namespace lbug {
namespace storage {

class PageManager;

/**
 * Manages any optimistically allocated pages (e.g. during COPY) so that they can be freed if a
 * rollback occurs.
 * This class is designed to be thread-local so accesses are not guaranteed to be thread-safe.
 */
class OptimisticAllocator : public PageAllocator {
public:
    explicit OptimisticAllocator(PageManager& pageManager);

    PageRange allocatePageRange(common::page_idx_t numPages) override;

    void freePageRange(PageRange block) override;

    void rollback();
    void commit();

private:
    PageManager& pageManager;
    std::vector<PageRange> optimisticallyAllocatedPages;
};
} // namespace storage
} // namespace lbug
