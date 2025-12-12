#include "common/task_system/task_scheduler.h"

#include "main/client_context.h"
#include "main/database.h"
#include "processor/processor.h"

#if defined(__APPLE__)
#include <pthread.h>

#include <pthread/qos.h>
#endif

namespace lbug {
namespace common {

#ifndef __SINGLE_THREADED__

#if defined(__APPLE__)
TaskScheduler::TaskScheduler(uint64_t numWorkerThreads, uint32_t threadQos)
#else
TaskScheduler::TaskScheduler(uint64_t numWorkerThreads)
#endif
    : stopWorkerThreads{false}, nextScheduledTaskID{0} {
#if defined(__APPLE__)
    this->threadQos = threadQos;
#endif
    for (auto n = 0u; n < numWorkerThreads; ++n) {
        workerThreads.emplace_back([&] { runWorkerThread(); });
    }
}

TaskScheduler::~TaskScheduler() {
    lock_t lck{taskSchedulerMtx};
    stopWorkerThreads = true;
    lck.unlock();
    cv.notify_all();
    for (auto& thread : workerThreads) {
        thread.join();
    }
}

void TaskScheduler::scheduleTaskAndWaitOrError(const std::shared_ptr<Task>& task,
    processor::ExecutionContext* context, bool launchNewWorkerThread) {
    for (auto& dependency : task->children) {
        scheduleTaskAndWaitOrError(dependency, context);
        if (dependency->terminate()) {
            return;
        }
    }
    std::thread newWorkerThread;
    if (launchNewWorkerThread) {
        // Note that newWorkerThread is not executing yet. However, we still call
        // task->registerThread() function because the call in the next line will guarantee
        // that the thread starts working on it. registerThread() function only increases the
        // numThreadsRegistered field of the task, tt does not keep track of the thread ids or
        // anything specific to the thread.
        task->registerThread();
        newWorkerThread = std::thread(runTask, task.get());
    }
    auto scheduledTask = pushTaskIntoQueue(task);
    cv.notify_all();
    std::unique_lock<std::mutex> taskLck{task->taskMtx, std::defer_lock};
    while (true) {
        taskLck.lock();
        bool timedWait = false;
        auto timeout = 0u;
        if (task->isCompletedNoLock()) {
            // Note: we do not remove completed tasks from the queue in this function. They will be
            // removed by the worker threads when they traverse down the queue for a task to work on
            // (see getTaskAndRegister()).
            taskLck.unlock();
            break;
        }
        if (context->clientContext->hasTimeout()) {
            timeout = context->clientContext->getTimeoutRemainingInMS();
            if (timeout == 0) {
                context->clientContext->interrupt();
            } else {
                timedWait = true;
            }
        } else if (task->hasExceptionNoLock()) {
            // Interrupt tasks that errored, so other threads can stop working on them early.
            context->clientContext->interrupt();
        }
        if (timedWait) {
            task->cv.wait_for(taskLck, std::chrono::milliseconds(timeout));
        } else {
            task->cv.wait(taskLck);
        }
        taskLck.unlock();
    }
    if (launchNewWorkerThread) {
        newWorkerThread.join();
    }
    if (task->hasException()) {
        removeErroringTask(scheduledTask->ID);
        std::rethrow_exception(task->getExceptionPtr());
    }
}

void TaskScheduler::runWorkerThread() {
#if defined(__APPLE__)
    qos_class_t qosClass = (qos_class_t)threadQos;
    if (qosClass != QOS_CLASS_DEFAULT && qosClass != QOS_CLASS_UNSPECIFIED) {
        auto pthreadQosStatus = pthread_set_qos_class_self_np(qosClass, 0);
        KU_UNUSED(pthreadQosStatus);
    }
#endif
    std::unique_lock<std::mutex> lck{taskSchedulerMtx, std::defer_lock};
    std::exception_ptr exceptionPtr = nullptr;
    std::shared_ptr<ScheduledTask> scheduledTask = nullptr;
    while (true) {
        // Warning: Threads acquire a global lock (using taskSchedulerMutex) right before
        // deregistering themselves from a task (and they immediately register themselves for
        // another task without releasing the lock). This acquire-right-before-deregistering ensures
        // that all writes that were done by threads in Task_j happen before a Task_{j+1} which
        // depends on Task_j can start. That's because before Task_{j+1} can start, each thread T_i
        // working on Task_j will need to deregister itself using the global lock. Therefore, by the
        // time any thread gets to start on Task_{j+1}, all writes made to Task_j by T_i will become
        // globally visible because T_i grabbed the global lock before deregistering (and without
        // T_i deregistering Task_{j+1} cannot start).
        lck.lock();
        if (scheduledTask != nullptr) {
            if (exceptionPtr != nullptr) {
                scheduledTask->task->setException(exceptionPtr);
                exceptionPtr = nullptr;
            }
            scheduledTask->task->deRegisterThreadAndFinalizeTask();
            scheduledTask = nullptr;
        }
        cv.wait(lck, [&] {
            scheduledTask = getTaskAndRegister();
            return scheduledTask != nullptr || stopWorkerThreads;
        });
        lck.unlock();
        if (stopWorkerThreads) {
            return;
        }
        try {
            scheduledTask->task->run();
        } catch (std::exception& e) {
            exceptionPtr = std::current_exception();
        }
    }
}
#else
// Single-threaded version of TaskScheduler
TaskScheduler::TaskScheduler(uint64_t) : stopWorkerThreads{false}, nextScheduledTaskID{0} {}

TaskScheduler::~TaskScheduler() {
    stopWorkerThreads = true;
}

void TaskScheduler::scheduleTaskAndWaitOrError(const std::shared_ptr<Task>& task,
    processor::ExecutionContext* context, bool) {
    for (auto& dependency : task->children) {
        scheduleTaskAndWaitOrError(dependency, context);
        if (dependency->terminate()) {
            return;
        }
    }
    task->registerThread();
    // runTask deregisters, so we don't need to deregister explicitly here
    runTask(task.get());
    if (task->hasException()) {
        removeErroringTask(task->ID);
        std::rethrow_exception(task->getExceptionPtr());
    }
}
#endif

std::shared_ptr<ScheduledTask> TaskScheduler::pushTaskIntoQueue(const std::shared_ptr<Task>& task) {
    lock_t lck{taskSchedulerMtx};
    auto scheduledTask = std::make_shared<ScheduledTask>(task, nextScheduledTaskID++);
    taskQueue.push_back(scheduledTask);
    return scheduledTask;
}

std::shared_ptr<ScheduledTask> TaskScheduler::getTaskAndRegister() {
    if (taskQueue.empty()) {
        return nullptr;
    }
    auto it = taskQueue.begin();
    while (it != taskQueue.end()) {
        auto task = (*it)->task;
        if (!task->registerThread()) {
            // If we cannot register for a thread it is because of three possibilities:
            // (i) maximum number of threads have registered for task and the task is completed
            // without an exception; or (ii) same as (i) but the task has not yet successfully
            // completed; or (iii) task has an exception; Only in (i) we remove the task from the
            // queue. For (ii) and (iii) we keep the task in queue. Recall erroring tasks need to be
            // manually removed.
            if (task->isCompletedSuccessfully()) { // option (i)
                it = taskQueue.erase(it);
            } else { // option (ii) or (iii): keep the task in the queue.
                ++it;
            }
        } else {
            return *it;
        }
    }
    return nullptr;
}

void TaskScheduler::removeErroringTask(uint64_t scheduledTaskID) {
    lock_t lck{taskSchedulerMtx};
    for (auto it = taskQueue.begin(); it != taskQueue.end(); ++it) {
        if (scheduledTaskID == (*it)->ID) {
            taskQueue.erase(it);
            return;
        }
    }
}

void TaskScheduler::runTask(Task* task) {
    try {
        task->run();
        task->deRegisterThreadAndFinalizeTask();
    } catch (std::exception& e) {
        task->setException(std::current_exception());
        task->deRegisterThreadAndFinalizeTask();
    }
}

TaskScheduler* TaskScheduler::Get(const main::ClientContext& context) {
    return context.getDatabase()->getQueryProcessor()->getTaskScheduler();
}

} // namespace common
} // namespace lbug
