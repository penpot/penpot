#pragma once

#include <atomic>

#include "common/assert.h"

// Alternative variant of the buffer manager which doesn't rely on MADV_DONTNEED (on Unix) for
// evicting pages (which is unavailable in Webassembly runtimes)
#if BM_MALLOC
#include <memory>
#endif

namespace lbug {
namespace storage {

// Keeps the state information of a page in a file.
class PageState {
    static constexpr uint64_t DIRTY_MASK = 0x0080000000000000;
    static constexpr uint64_t STATE_MASK = 0xFF00000000000000;
    static constexpr uint64_t VERSION_MASK = 0x00FFFFFFFFFFFFFF;
    static constexpr uint64_t NUM_BITS_TO_SHIFT_FOR_STATE = 56;

public:
    static constexpr uint64_t UNLOCKED = 0;
    static constexpr uint64_t LOCKED = 1;
    static constexpr uint64_t MARKED = 2;
    static constexpr uint64_t EVICTED = 3;

    PageState() { stateAndVersion.store(EVICTED << NUM_BITS_TO_SHIFT_FOR_STATE); }

    uint64_t getState() const { return getState(stateAndVersion.load()); }
    static uint64_t getState(uint64_t stateAndVersion) {
        return (stateAndVersion & STATE_MASK) >> NUM_BITS_TO_SHIFT_FOR_STATE;
    }
    static uint64_t getVersion(uint64_t stateAndVersion) { return stateAndVersion & VERSION_MASK; }
    static uint64_t updateStateWithSameVersion(uint64_t oldStateAndVersion, uint64_t newState) {
        return ((oldStateAndVersion << 8) >> 8) | (newState << NUM_BITS_TO_SHIFT_FOR_STATE);
    }
    static uint64_t updateStateAndIncrementVersion(uint64_t oldStateAndVersion, uint64_t newState) {
        return (((oldStateAndVersion << 8) >> 8) + 1) | (newState << NUM_BITS_TO_SHIFT_FOR_STATE);
    }
    void spinLock(uint64_t oldStateAndVersion) {
        while (true) {
            if (tryLock(oldStateAndVersion)) {
                return;
            }
        }
    }
    bool tryLock(uint64_t oldStateAndVersion) {
        return stateAndVersion.compare_exchange_strong(oldStateAndVersion,
            updateStateWithSameVersion(oldStateAndVersion, LOCKED));
    }
    void unlock() {
        // TODO(Keenan / Guodong): Track down this rare bug and re-enable the assert. Ref #2289.
        // KU_ASSERT(getState(stateAndVersion.load()) == LOCKED);
        stateAndVersion.store(updateStateAndIncrementVersion(stateAndVersion.load(), UNLOCKED));
    }
    void unlockUnchanged() {
        // TODO(Keenan / Guodong): Track down this rare bug and re-enable the assert. Ref #2289.
        // KU_ASSERT(getState(stateAndVersion.load()) == LOCKED);
        stateAndVersion.store(updateStateWithSameVersion(stateAndVersion.load(), UNLOCKED));
    }
    // Change page state from Mark to Unlocked.
    bool tryClearMark(uint64_t oldStateAndVersion) {
        KU_ASSERT(getState(oldStateAndVersion) == MARKED);
        return stateAndVersion.compare_exchange_strong(oldStateAndVersion,
            updateStateWithSameVersion(oldStateAndVersion, UNLOCKED));
    }
    bool tryMark(uint64_t oldStateAndVersion) {
        return stateAndVersion.compare_exchange_strong(oldStateAndVersion,
            updateStateWithSameVersion(oldStateAndVersion, MARKED));
    }

    void setDirty() {
        KU_ASSERT(getState(stateAndVersion.load()) == LOCKED);
        stateAndVersion |= DIRTY_MASK;
    }
    void clearDirty() {
        KU_ASSERT(getState(stateAndVersion.load()) == LOCKED);
        stateAndVersion &= ~DIRTY_MASK;
    }
    // Meant to be used when flushing in a single thread.
    // Should not be used if other threads are modifying the page state
    void clearDirtyWithoutLock() { stateAndVersion &= ~DIRTY_MASK; }
    bool isDirty() const { return stateAndVersion & DIRTY_MASK; }
    uint64_t getStateAndVersion() const { return stateAndVersion.load(); }

    void resetToEvicted() {
        stateAndVersion.store(EVICTED << NUM_BITS_TO_SHIFT_FOR_STATE);
#if BM_MALLOC
        page.reset();
#endif
    }

#if BM_MALLOC
    uint8_t* getPage() const { return page.get(); }
    uint8_t* allocatePage(uint64_t pageSize) {
        page = std::make_unique<uint8_t[]>(pageSize);
        return page.get();
    }
    uint16_t getReaderCount() const { return readerCount; }
    void addReader() { readerCount++; }
    void removeReader() { readerCount--; }
#endif

private:
    // Highest 1 bit is dirty bit, and the rest are page state and version bits.
    // In the rest bits, the lowest 1 byte is state, and the rest are version.
    std::atomic<uint64_t> stateAndVersion;
#if BM_MALLOC
    std::unique_ptr<uint8_t[]> page;
    std::atomic<uint16_t> readerCount;
#endif
};

} // namespace storage
} // namespace lbug
