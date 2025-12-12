#include "common/copier_config/csv_reader_config.h"

#include <algorithm>

#include "common/exception/binder.h"
#include "common/exception/runtime.h"
#include "common/string_utils.h"
#include "common/types/value/nested.h"

namespace lbug {
namespace common {

static char bindParsingOptionValue(const std::string& value) {
    if (value == "\\t") {
        return '\t';
    }
    if (value.length() < 1 || value.length() > 2 || (value.length() == 2 && value[0] != '\\')) {
        throw BinderException("Copy csv option value must be a single character with an "
                              "optional escape character.");
    }
    return value[value.length() - 1];
}

static void bindBoolParsingOption(CSVReaderConfig& config, const std::string& optionName,
    bool optionValue) {
    if (optionName == "HEADER") {
        config.option.hasHeader = optionValue;
        config.option.setHeader = true;
    } else if (optionName == "PARALLEL") {
        config.parallel = optionValue;
    } else if (optionName == "LIST_UNBRACED") {
        config.option.allowUnbracedList = optionValue;
    } else if (optionName == CopyConstants::IGNORE_ERRORS_OPTION_NAME) {
        config.option.ignoreErrors = optionValue;
    } else if (optionName == "AUTODETECT" || optionName == "AUTO_DETECT") {
        config.option.autoDetection = optionValue;
    } else {
        KU_UNREACHABLE;
    }
}

static void bindStringParsingOption(CSVReaderConfig& config, const std::string& optionName,
    const std::string& optionValue) {
    auto parsingOptionValue = bindParsingOptionValue(optionValue);
    if (optionName == "ESCAPE") {
        config.option.escapeChar = parsingOptionValue;
        config.option.setEscape = true;
    } else if (optionName == "DELIM" || optionName == "DELIMITER") {
        config.option.delimiter = parsingOptionValue;
        config.option.setDelim = true;
    } else if (optionName == "QUOTE") {
        config.option.quoteChar = parsingOptionValue;
        config.option.setQuote = true;
    } else {
        KU_UNREACHABLE;
    }
}

static void bindIntParsingOption(CSVReaderConfig& config, const std::string& optionName,
    const int64_t& optionValue) {
    if (optionName == "SKIP") {
        if (optionValue < 0) {
            throw RuntimeException{"Skip number must be a non-negative integer"};
        }
        config.option.skipNum = optionValue;
    } else if (optionName == "SAMPLE_SIZE") {
        if (optionValue < 0) {
            // technically impossible at the moment since negative values aren't supported
            // in parameters
            throw RuntimeException{"Sample size must be a non-negative integer"};
        }
        config.option.sampleSize = optionValue;
    } else {
        KU_UNREACHABLE;
    }
}

static void bindListParsingOption(CSVReaderConfig& config, const std::string& optionName,
    const std::vector<std::string>& optionValue) {
    if (optionName == "NULL_STRINGS") {
        config.option.nullStrings = optionValue;
    } else {
        KU_UNREACHABLE;
    }
}

template<uint64_t size>
static bool hasOption(const char* const (&arr)[size], const std::string& option) {
    return std::find(std::begin(arr), std::end(arr), option) != std::end(arr);
}

static bool validateBoolParsingOptionName(const std::string& parsingOptionName) {
    return hasOption(CopyConstants::BOOL_CSV_PARSING_OPTIONS, parsingOptionName);
}

static bool validateStringParsingOptionName(const std::string& parsingOptionName) {
    return hasOption(CopyConstants::STRING_CSV_PARSING_OPTIONS, parsingOptionName);
}

static bool validateIntParsingOptionName(const std::string& parsingOptionName) {
    return hasOption(CopyConstants::INT_CSV_PARSING_OPTIONS, parsingOptionName);
}

static bool validateListParsingOptionName(const std::string& parsingOptionName) {
    return hasOption(CopyConstants::LIST_CSV_PARSING_OPTIONS, parsingOptionName);
}

static bool isValidBooleanOptionValue(const Value& value, const std::string& name) {
    // Normalize and check if the string is a valid Boolean representation
    auto strValue = value.toString();
    StringUtils::toUpper(strValue);

    // Check for valid Boolean string representations
    if (strValue == "TRUE" || strValue == "1") {
        return true;
    } else if (strValue == "FALSE" || strValue == "0") {
        return false;
    } else {
        // In this case the boolean is not valid
        throw BinderException(
            stringFormat("The type of csv parsing option {} must be a boolean.", name));
    }
}

CSVReaderConfig CSVReaderConfig::construct(const case_insensitive_map_t<Value>& options) {
    auto config = CSVReaderConfig();
    for (auto& op : options) {
        auto name = op.first;
        auto isValidStringParsingOption = validateStringParsingOptionName(name);
        auto isValidBoolParsingOption = validateBoolParsingOptionName(name);
        auto isValidIntParsingOption = validateIntParsingOptionName(name);
        auto isValidListParsingOption = validateListParsingOptionName(name);
        if (isValidBoolParsingOption) {
            bindBoolParsingOption(config, name, isValidBooleanOptionValue(op.second, name));
        } else if (isValidStringParsingOption) {
            if (op.second.getDataType() != LogicalType::STRING()) {
                throw BinderException(
                    stringFormat("The type of csv parsing option {} must be a string.", name));
            }
            bindStringParsingOption(config, name, op.second.getValue<std::string>());
        } else if (isValidIntParsingOption) {
            if (op.second.getDataType() != LogicalType::INT64()) {
                throw BinderException(
                    stringFormat("The type of csv parsing option {} must be a INT64.", name));
            }
            bindIntParsingOption(config, name, op.second.getValue<int64_t>());
        } else if (isValidListParsingOption) {
            if (op.second.getDataType() != LogicalType::LIST(LogicalType::STRING())) {
                throw BinderException(
                    stringFormat("The type of csv parsing option {} must be a STRING[].", name));
            }
            std::vector<std::string> optionValues;
            for (auto i = 0u; i < op.second.getChildrenSize(); i++) {
                optionValues.push_back(
                    NestedVal::getChildVal(&op.second, i)->getValue<std::string>());
            }
            bindListParsingOption(config, name, optionValues);
        } else {
            throw BinderException(stringFormat("Unrecognized csv parsing option: {}.", name));
        }
    }
    if (config.option.skipNum > 0) {
        // If the user sets the number of rows to skip, we cannot read in parallel mode.
        config.parallel = false;
    }
    return config;
}

} // namespace common
} // namespace lbug
