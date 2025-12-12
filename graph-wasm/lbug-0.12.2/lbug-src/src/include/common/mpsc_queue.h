#pragma once

// Based on the following design:
// https://www.1024cores.net/home/lock-free-algorithms/queues/non-intrusive-mpsc-node-based-queue

#include <atomic>

#include "common/assert.h"
#include "common/copy_constructors.h"

namespace lbug {
namespace common {

// Producers are completely wait-free.
template<typename T>
class MPSCQueue {
    struct Node {
        T data;
        std::atomic<Node*> next;

        explicit Node(T data) : data(std::move(data)), next(nullptr) {}
    };

public:
    MPSCQueue() : head(nullptr), tail(nullptr), _approxSize(0) {
        // Allocate a dummy element.
        Node* stub = new Node(T());
        head = stub;

        // Ordering doesn't matter.
        tail.store(stub, std::memory_order_relaxed);
    }
    DELETE_BOTH_COPY(MPSCQueue);
    MPSCQueue(MPSCQueue&& other)
        : head(other.head), tail(other.tail.exchange(nullptr, std::memory_order_relaxed)),
          _approxSize(other._approxSize.load(std::memory_order_relaxed)) {
        other.head = nullptr;
    }
    // If this method existed, it wouldn't be atomic, and so would be rather error-prone. Maybe
    // there's a valid future use case.
    DELETE_MOVE_ASSN(MPSCQueue);

    // NOTE: It is NOT guaranteed that the result of a push() is accessible to a thread that calls
    // pop() after the push(), because of implementation details. See the body of the function for
    // details.
    void push(T elem) {
        Node* node = new Node(std::move(elem));
        _approxSize.fetch_add(1, std::memory_order_relaxed);
        // ORDERING: must acquire any updates to prev before modifying it, and release our updates
        // to node for other producers.
        Node* prev = tail.exchange(node, std::memory_order_acq_rel);
        // NOTE: If the thread is suspended here, then ALL FUTURE push() calls will be INACCESSIBLE
        // by pop() calls until the next line runs. In order to guarantee that a push() is visible
        // to a thread that calls pop(), ALL push() calls must have completed.
        // ORDERING: must make updates visible to consumers.
        prev->next.store(node, std::memory_order_release);
    }

    // NOTE: It is NOT safe to call pop() from multiple threads without synchronization.
    bool pop(T& elem) {
        // ORDERING: Acquire any updates made by producers.
        // Note that head is accessed only by the single consumer, so accesses to it need not be
        // synchronized.
        Node* next = head->next.load(std::memory_order_acquire);
        if (next == nullptr) {
            return false;
        }
        // Free the old element.
        delete head;
        head = next;
        elem = std::move(head->data);
        _approxSize.fetch_sub(1, std::memory_order_relaxed);
        // Now the current head has dummy data in it again (i.e., whatever was leftover after the
        // move()).
        return true;
    }

    // Return an approximation of the number of elements in the queue.
    // Due to implementation details, this number must not be relied on. However, it can be used to
    // get a rough estimate for the size of the queue.
    size_t approxSize() const { return _approxSize.load(std::memory_order_relaxed); }

    // Drain the queue. All operations on the queue MUST have finished. I.e., there must be NO
    // push() or pop() operations in progress of any kind.
    ~MPSCQueue() {
        // If we were moved out of, return.
        if (!head) {
            return;
        }

        T dummy;
        while (pop(dummy)) {}
        KU_ASSERT(head == tail.load(std::memory_order_relaxed));
        delete head;
    }

private:
    // Head is always present, but always has dummy data. This ensures that it is always easy to
    // append to the list, without branching in the methods.
    Node* head;
    std::atomic<Node*> tail;

    std::atomic<size_t> _approxSize;
};

} // namespace common
} // namespace lbug
