#include "processor/operator/standalone_call.h"

#include "common/cast.h"
#include "main/client_context.h"
#include "main/db_config.h"
#include "processor/execution_context.h"

namespace lbug {
namespace processor {

std::string StandaloneCallPrintInfo::toString() const {
    std::string result = "Function: ";
    result += functionName;
    return result;
}

bool StandaloneCall::getNextTuplesInternal(ExecutionContext* context) {
    if (standaloneCallInfo.hasExecuted) {
        return false;
    }
    standaloneCallInfo.hasExecuted = true;
    switch (standaloneCallInfo.option->optionType) {
    case main::OptionType::CONFIGURATION: {
        const auto configurationOption =
            common::ku_dynamic_cast<const main::ConfigurationOption*>(standaloneCallInfo.option);
        configurationOption->setContext(context->clientContext, standaloneCallInfo.optionValue);
        break;
    }
    case main::OptionType::EXTENSION:
        context->clientContext->setExtensionOption(standaloneCallInfo.option->name,
            standaloneCallInfo.optionValue);
        break;
    }
    metrics->numOutputTuple.incrementByOne();
    return true;
}

} // namespace processor
} // namespace lbug
