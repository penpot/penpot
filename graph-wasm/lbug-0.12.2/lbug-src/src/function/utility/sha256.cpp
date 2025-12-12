#include "common/sha256.h"

#include "function/hash/vector_hash_functions.h"
#include "function/scalar_function.h"

using namespace lbug::common;

namespace lbug {
namespace function {

struct SHA256Operator {
    static void operation(ku_string_t& operand, ku_string_t& result, ValueVector& resultVector) {
        StringVector::reserveString(&resultVector, result, SHA256::SHA256_HASH_LENGTH_TEXT);
        SHA256 hasher;
        hasher.addString(operand.getAsString());
        hasher.finishSHA256(reinterpret_cast<char*>(result.getDataUnsafe()));
        if (!ku_string_t::isShortString(result.len)) {
            memcpy(result.prefix, result.getData(), ku_string_t::PREFIX_LENGTH);
        }
    }
};

function_set SHA256Function::getFunctionSet() {
    function_set functionSet;
    functionSet.push_back(std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::STRING}, LogicalTypeID::STRING,
        ScalarFunction::UnaryStringExecFunction<ku_string_t, ku_string_t, SHA256Operator>));
    return functionSet;
}

} // namespace function
} // namespace lbug
