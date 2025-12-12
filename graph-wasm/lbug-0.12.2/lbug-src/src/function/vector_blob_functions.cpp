#include "function/blob/vector_blob_functions.h"

#include "function/blob/functions/decode_function.h"
#include "function/blob/functions/encode_function.h"
#include "function/blob/functions/octet_length_function.h"
#include "function/scalar_function.h"

using namespace lbug::common;

namespace lbug {
namespace function {

function_set OctetLengthFunctions::getFunctionSet() {
    function_set definitions;
    definitions.push_back(
        make_unique<ScalarFunction>(name, std::vector<LogicalTypeID>{LogicalTypeID::BLOB},
            LogicalTypeID::INT64, ScalarFunction::UnaryExecFunction<blob_t, int64_t, OctetLength>));
    return definitions;
}

function_set EncodeFunctions::getFunctionSet() {
    function_set definitions;
    definitions.push_back(make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::STRING}, LogicalTypeID::BLOB,
        ScalarFunction::UnaryStringExecFunction<ku_string_t, blob_t, Encode>));
    return definitions;
}

function_set DecodeFunctions::getFunctionSet() {
    function_set definitions;
    definitions.push_back(make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::BLOB}, LogicalTypeID::STRING,
        ScalarFunction::UnaryStringExecFunction<blob_t, ku_string_t, Decode>));
    return definitions;
}

} // namespace function
} // namespace lbug
