#include "main/prepared_statement.h"

#include "c_api/helpers.h"
#include "c_api/lbug.h"
#include "common/types/value/value.h"

using namespace lbug::common;
using namespace lbug::main;

void lbug_prepared_statement_bind_cpp_value(lbug_prepared_statement* prepared_statement,
    const char* param_name, std::unique_ptr<Value> value) {
    auto* bound_values = static_cast<std::unordered_map<std::string, std::unique_ptr<Value>>*>(
        prepared_statement->_bound_values);
    bound_values->erase(param_name);
    bound_values->insert({param_name, std::move(value)});
}

void lbug_prepared_statement_destroy(lbug_prepared_statement* prepared_statement) {
    if (prepared_statement == nullptr) {
        return;
    }
    if (prepared_statement->_prepared_statement != nullptr) {
        delete static_cast<PreparedStatement*>(prepared_statement->_prepared_statement);
    }
    if (prepared_statement->_bound_values != nullptr) {
        delete static_cast<std::unordered_map<std::string, std::unique_ptr<Value>>*>(
            prepared_statement->_bound_values);
    }
}

bool lbug_prepared_statement_is_success(lbug_prepared_statement* prepared_statement) {
    return static_cast<PreparedStatement*>(prepared_statement->_prepared_statement)->isSuccess();
}

char* lbug_prepared_statement_get_error_message(lbug_prepared_statement* prepared_statement) {
    auto error_message =
        static_cast<PreparedStatement*>(prepared_statement->_prepared_statement)->getErrorMessage();
    if (error_message.empty()) {
        return nullptr;
    }
    return convertToOwnedCString(error_message);
}

lbug_state lbug_prepared_statement_bind_bool(lbug_prepared_statement* prepared_statement,
    const char* param_name, bool value) {
    try {
        auto value_ptr = std::make_unique<Value>(value);
        lbug_prepared_statement_bind_cpp_value(prepared_statement, param_name,
            std::move(value_ptr));
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_prepared_statement_bind_int64(lbug_prepared_statement* prepared_statement,
    const char* param_name, int64_t value) {
    try {
        auto value_ptr = std::make_unique<Value>(value);
        lbug_prepared_statement_bind_cpp_value(prepared_statement, param_name,
            std::move(value_ptr));
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_prepared_statement_bind_int32(lbug_prepared_statement* prepared_statement,
    const char* param_name, int32_t value) {
    try {
        auto value_ptr = std::make_unique<Value>(value);
        lbug_prepared_statement_bind_cpp_value(prepared_statement, param_name,
            std::move(value_ptr));
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_prepared_statement_bind_int16(lbug_prepared_statement* prepared_statement,
    const char* param_name, int16_t value) {
    try {
        auto value_ptr = std::make_unique<Value>(value);
        lbug_prepared_statement_bind_cpp_value(prepared_statement, param_name,
            std::move(value_ptr));
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_prepared_statement_bind_int8(lbug_prepared_statement* prepared_statement,
    const char* param_name, int8_t value) {
    try {
        auto value_ptr = std::make_unique<Value>(value);
        lbug_prepared_statement_bind_cpp_value(prepared_statement, param_name,
            std::move(value_ptr));
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_prepared_statement_bind_uint64(lbug_prepared_statement* prepared_statement,
    const char* param_name, uint64_t value) {
    try {
        auto value_ptr = std::make_unique<Value>(value);
        lbug_prepared_statement_bind_cpp_value(prepared_statement, param_name,
            std::move(value_ptr));
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_prepared_statement_bind_uint32(lbug_prepared_statement* prepared_statement,
    const char* param_name, uint32_t value) {
    try {
        auto value_ptr = std::make_unique<Value>(value);
        lbug_prepared_statement_bind_cpp_value(prepared_statement, param_name,
            std::move(value_ptr));
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_prepared_statement_bind_uint16(lbug_prepared_statement* prepared_statement,
    const char* param_name, uint16_t value) {
    try {
        auto value_ptr = std::make_unique<Value>(value);
        lbug_prepared_statement_bind_cpp_value(prepared_statement, param_name,
            std::move(value_ptr));
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_prepared_statement_bind_uint8(lbug_prepared_statement* prepared_statement,
    const char* param_name, uint8_t value) {
    try {
        auto value_ptr = std::make_unique<Value>(value);
        lbug_prepared_statement_bind_cpp_value(prepared_statement, param_name,
            std::move(value_ptr));
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_prepared_statement_bind_double(lbug_prepared_statement* prepared_statement,
    const char* param_name, double value) {
    try {
        auto value_ptr = std::make_unique<Value>(value);
        lbug_prepared_statement_bind_cpp_value(prepared_statement, param_name,
            std::move(value_ptr));
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_prepared_statement_bind_float(lbug_prepared_statement* prepared_statement,
    const char* param_name, float value) {
    try {
        auto value_ptr = std::make_unique<Value>(value);
        lbug_prepared_statement_bind_cpp_value(prepared_statement, param_name,
            std::move(value_ptr));
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_prepared_statement_bind_date(lbug_prepared_statement* prepared_statement,
    const char* param_name, lbug_date_t value) {
    try {
        auto value_ptr = std::make_unique<Value>(date_t(value.days));
        lbug_prepared_statement_bind_cpp_value(prepared_statement, param_name,
            std::move(value_ptr));
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_prepared_statement_bind_timestamp_ns(lbug_prepared_statement* prepared_statement,
    const char* param_name, lbug_timestamp_ns_t value) {
    try {
        auto value_ptr = std::make_unique<Value>(timestamp_ns_t(value.value));
        lbug_prepared_statement_bind_cpp_value(prepared_statement, param_name,
            std::move(value_ptr));
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_prepared_statement_bind_timestamp_ms(lbug_prepared_statement* prepared_statement,
    const char* param_name, lbug_timestamp_ms_t value) {
    try {
        auto value_ptr = std::make_unique<Value>(timestamp_ms_t(value.value));
        lbug_prepared_statement_bind_cpp_value(prepared_statement, param_name,
            std::move(value_ptr));
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_prepared_statement_bind_timestamp_sec(lbug_prepared_statement* prepared_statement,
    const char* param_name, lbug_timestamp_sec_t value) {
    try {
        auto value_ptr = std::make_unique<Value>(timestamp_sec_t(value.value));
        lbug_prepared_statement_bind_cpp_value(prepared_statement, param_name,
            std::move(value_ptr));
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_prepared_statement_bind_timestamp_tz(lbug_prepared_statement* prepared_statement,
    const char* param_name, lbug_timestamp_tz_t value) {
    try {
        auto value_ptr = std::make_unique<Value>(timestamp_tz_t(value.value));
        lbug_prepared_statement_bind_cpp_value(prepared_statement, param_name,
            std::move(value_ptr));
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_prepared_statement_bind_timestamp(lbug_prepared_statement* prepared_statement,
    const char* param_name, lbug_timestamp_t value) {
    try {
        auto value_ptr = std::make_unique<Value>(timestamp_t(value.value));
        lbug_prepared_statement_bind_cpp_value(prepared_statement, param_name,
            std::move(value_ptr));
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_prepared_statement_bind_interval(lbug_prepared_statement* prepared_statement,
    const char* param_name, lbug_interval_t value) {
    try {
        auto value_ptr =
            std::make_unique<Value>(interval_t(value.months, value.days, value.micros));
        lbug_prepared_statement_bind_cpp_value(prepared_statement, param_name,
            std::move(value_ptr));
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_prepared_statement_bind_string(lbug_prepared_statement* prepared_statement,
    const char* param_name, const char* value) {
    try {
        auto value_ptr = std::make_unique<Value>(std::string(value));
        lbug_prepared_statement_bind_cpp_value(prepared_statement, param_name,
            std::move(value_ptr));
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_prepared_statement_bind_value(lbug_prepared_statement* prepared_statement,
    const char* param_name, lbug_value* value) {
    try {
        auto value_ptr = std::make_unique<Value>(*static_cast<Value*>(value->_value));
        lbug_prepared_statement_bind_cpp_value(prepared_statement, param_name,
            std::move(value_ptr));
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}
