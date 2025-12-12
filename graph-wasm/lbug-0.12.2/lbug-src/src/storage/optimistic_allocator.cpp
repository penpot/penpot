#include "storage/optimistic_allocator.h"

#include "storage/page_manager.h"

namespace lbug::storage {
OptimisticAllocator::OptimisticAllocator(PageManager& pageManager)
    : PageAllocator(pageManager.getDataFH()), pageManager(pageManager) {}

PageRange OptimisticAllocator::allocatePageRange(common::page_idx_t numPages) {
    auto pageRange = pageManager.allocatePageRange(numPages);
    if (numPages > 0) {
        optimisticallyAllocatedPages.push_back(pageRange);
    }
    return pageRange;
}

void OptimisticAllocator::freePageRange(PageRange block) {
    pageManager.freePageRange(block);
}

void OptimisticAllocator::rollback() {
    for (const auto& entry : optimisticallyAllocatedPages) {
        pageManager.freeImmediatelyRewritablePageRange(pageManager.getDataFH(), entry);
    }
    optimisticallyAllocatedPages.clear();
}

void OptimisticAllocator::commit() {
    optimisticallyAllocatedPages.clear();
}
} // namespace lbug::storage
