#pragma once

#include "common/task_system/task.h"
#include "processor/operator/sink.h"

namespace lbug {
namespace processor {

class ProcessorTask : public common::Task {
    friend class QueryProcessor;

public:
    ProcessorTask(Sink* sink, ExecutionContext* executionContext);

    void run() override;

    void finalize() override;

    bool terminate() override;

private:
    bool sharedStateInitialized;
    Sink* sink;
    ExecutionContext* executionContext;
};

} // namespace processor
} // namespace lbug
