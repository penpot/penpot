#include "function/date/date_functions.h"

#include "function/function.h"
#include "transaction/transaction.h"

namespace lbug {
namespace function {

void CurrentDate::operation(common::date_t& result, void* dataPtr) {
    auto clientContext = reinterpret_cast<FunctionBindData*>(dataPtr)->clientContext;
    auto transaction = transaction::Transaction::Get(*clientContext);
    auto currentTS = transaction->getCurrentTS();
    result = common::Timestamp::getDate(common::timestamp_tz_t(currentTS));
}

void CurrentTimestamp::operation(common::timestamp_tz_t& result, void* dataPtr) {
    auto clientContext = reinterpret_cast<FunctionBindData*>(dataPtr)->clientContext;
    auto transaction = transaction::Transaction::Get(*clientContext);
    auto currentTS = transaction->getCurrentTS();
    result = common::timestamp_tz_t(currentTS);
}

} // namespace function
} // namespace lbug
