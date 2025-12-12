#pragma once

#include <condition_variable>
#include <mutex>
#include <vector>

#include "common/api.h"

namespace lbug {
namespace common {

using lock_t = std::unique_lock<std::mutex>;

/**
 * Task represents a task that can be executed by multiple threads in the TaskScheduler. Task is a
 * virtual class. Users of TaskScheduler need to extend the Task class and implement at
 * least a virtual run() function. Users can assume that before T.run() is called, a worker thread W
 * has grabbed task T from the TaskScheduler's queue and registered itself to T. They can also
 * assume that after run() is called W will deregister itself from T. When deregistering, if W is
 * the last worker to finish on T, i.e., once W finishes, T will be completed, the
 * finalize() will be called. So the run() and finalize() calls are separate
 * calls and if there is some state from the run() function execution that will be needed by
 * finalize, users should save it somewhere that can be accessed in
 * finalize(). See ProcessorTask for an example of this.
 */
class LBUG_API Task {
    friend class TaskScheduler;

public:
    explicit Task(uint64_t maxNumThreads)
        : parent{nullptr}, maxNumThreads{maxNumThreads}, numThreadsFinished{0},
          numThreadsRegistered{0}, exceptionsPtr{nullptr}, ID{UINT64_MAX} {}

    virtual ~Task() = default;
    virtual void run() = 0;
    // This function is called from inside deRegisterThreadAndFinalizeTaskIfNecessary() only
    // once by the last registered worker that is completing this task. So the task lock is
    // already acquired. So do not attempt to acquire the task lock inside. If needed we can
    // make the deregister function release the lock before calling finalize and
    // drop this assumption.
    virtual void finalize() {}
    // If task should terminate all subsequent tasks.
    virtual bool terminate() { return false; }

    void addChildTask(std::unique_ptr<Task> child) {
        child->parent = this;
        children.push_back(std::move(child));
    }

    bool isCompletedSuccessfully() {
        lock_t lck{taskMtx};
        return isCompletedNoLock() && !hasExceptionNoLock();
    }

    bool isCompletedNoLock() const {
        return numThreadsRegistered > 0 && numThreadsFinished == numThreadsRegistered;
    }

    void setSingleThreadedTask() { maxNumThreads = 1; }

    bool registerThread();

    void deRegisterThreadAndFinalizeTask();

    void setException(const std::exception_ptr& exceptionPtr) {
        lock_t lck{taskMtx};
        setExceptionNoLock(exceptionPtr);
    }

    bool hasException() {
        lock_t lck{taskMtx};
        return exceptionsPtr != nullptr;
    }

    std::exception_ptr getExceptionPtr() {
        lock_t lck{taskMtx};
        return exceptionsPtr;
    }

private:
    bool canRegisterNoLock() const {
        return 0 == numThreadsFinished && maxNumThreads > numThreadsRegistered;
    }

    bool hasExceptionNoLock() const { return exceptionsPtr != nullptr; }

    void setExceptionNoLock(const std::exception_ptr& exceptionPtr) {
        if (exceptionsPtr == nullptr) {
            exceptionsPtr = exceptionPtr;
        }
    }

public:
    Task* parent;
    std::vector<std::shared_ptr<Task>>
        children; // Dependency tasks that needs to be executed first.

protected:
    std::mutex taskMtx;
    std::condition_variable cv;
    uint64_t maxNumThreads, numThreadsFinished, numThreadsRegistered;
    std::exception_ptr exceptionsPtr;
    uint64_t ID;
};

} // namespace common
} // namespace lbug
