#include "common/task_system/task.h"

namespace lbug {
namespace common {

bool Task::registerThread() {
    lock_t lck{taskMtx};
    if (!hasExceptionNoLock() && canRegisterNoLock()) {
        numThreadsRegistered++;
        return true;
    }
    return false;
}

void Task::deRegisterThreadAndFinalizeTask() {
    lock_t lck{taskMtx};
    ++numThreadsFinished;
    if (!hasExceptionNoLock() && isCompletedNoLock()) {
        try {
            finalize();
        } catch (std::exception& e) {
            setExceptionNoLock(std::current_exception());
        }
    }
    if (isCompletedNoLock()) {
        lck.unlock();
        cv.notify_all();
    }
}

} // namespace common
} // namespace lbug
