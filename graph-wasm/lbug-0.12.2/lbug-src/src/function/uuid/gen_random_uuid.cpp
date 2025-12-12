#include "function/uuid/functions/gen_random_uuid.h"

#include "common/random_engine.h"
#include "function/function.h"

namespace lbug {
namespace function {

void GenRandomUUID::operation(common::ku_uuid_t& input, void* dataPtr) {
    auto clientContext = static_cast<FunctionBindData*>(dataPtr)->clientContext;
    input = common::UUID::generateRandomUUID(common::RandomEngine::Get(*clientContext));
}

} // namespace function
} // namespace lbug
