#pragma once

#include "common/case_insensitive_map.h"
#include "common/constants.h"
#include "common/copy_constructors.h"
#include "common/types/value/value.h"

namespace lbug {
namespace common {

struct CSVOption {
    // TODO(Xiyang): Add newline character option and delimiter can be a string.
    char escapeChar;
    char delimiter;
    char quoteChar;
    bool hasHeader;
    uint64_t skipNum;
    uint64_t sampleSize;
    bool allowUnbracedList;
    bool ignoreErrors;

    bool autoDetection;
    // These fields aim to identify whether the options are set by user, or set by default.
    bool setEscape;
    bool setDelim;
    bool setQuote;
    bool setHeader;
    std::vector<std::string> nullStrings;

    CSVOption()
        : escapeChar{CopyConstants::DEFAULT_CSV_ESCAPE_CHAR},
          delimiter{CopyConstants::DEFAULT_CSV_DELIMITER},
          quoteChar{CopyConstants::DEFAULT_CSV_QUOTE_CHAR},
          hasHeader{CopyConstants::DEFAULT_CSV_HAS_HEADER},
          skipNum{CopyConstants::DEFAULT_CSV_SKIP_NUM},
          sampleSize{CopyConstants::DEFAULT_CSV_TYPE_DEDUCTION_SAMPLE_SIZE},
          allowUnbracedList{CopyConstants::DEFAULT_CSV_ALLOW_UNBRACED_LIST},
          ignoreErrors(CopyConstants::DEFAULT_IGNORE_ERRORS),
          autoDetection{CopyConstants::DEFAULT_CSV_AUTO_DETECT},
          setEscape{CopyConstants::DEFAULT_CSV_SET_DIALECT},
          setDelim{CopyConstants::DEFAULT_CSV_SET_DIALECT},
          setQuote{CopyConstants::DEFAULT_CSV_SET_DIALECT},
          setHeader{CopyConstants::DEFAULT_CSV_SET_DIALECT},
          nullStrings{CopyConstants::DEFAULT_CSV_NULL_STRINGS[0]} {}

    EXPLICIT_COPY_DEFAULT_MOVE(CSVOption);

    // TODO: COPY FROM and COPY TO should support transform special options, like '\'.
    std::unordered_map<std::string, std::string> toOptionsMap(const bool& parallel) const {
        std::unordered_map<std::string, std::string> result;
        result["parallel"] = parallel ? "true" : "false";
        if (setHeader) {
            result["header"] = hasHeader ? "true" : "false";
        }
        if (setEscape) {
            result["escape"] = stringFormat("'\\{}'", escapeChar);
        }
        if (setDelim) {
            result["delim"] = stringFormat("'{}'", delimiter);
        }
        if (setQuote) {
            result["quote"] = stringFormat("'\\{}'", quoteChar);
        }
        if (autoDetection != CopyConstants::DEFAULT_CSV_AUTO_DETECT) {
            result["auto_detect"] = autoDetection ? "true" : "false";
        }
        return result;
    }

    static std::string toCypher(const std::unordered_map<std::string, std::string>& options) {
        if (options.empty()) {
            return "";
        }
        std::string result = "";
        for (const auto& [key, value] : options) {
            if (!result.empty()) {
                result += ", ";
            }
            result += key + "=" + value;
        }
        return "(" + result + ")";
    }

    // Explicit copy constructor
    CSVOption(const CSVOption& other)
        : escapeChar{other.escapeChar}, delimiter{other.delimiter}, quoteChar{other.quoteChar},
          hasHeader{other.hasHeader}, skipNum{other.skipNum},
          sampleSize{other.sampleSize == 0 ?
                         CopyConstants::DEFAULT_CSV_TYPE_DEDUCTION_SAMPLE_SIZE :
                         other.sampleSize}, // Set to DEFAULT_CSV_TYPE_DEDUCTION_SAMPLE_SIZE if
                                            // sampleSize is 0
          allowUnbracedList{other.allowUnbracedList}, ignoreErrors{other.ignoreErrors},
          autoDetection{other.autoDetection}, setEscape{other.setEscape}, setDelim{other.setDelim},
          setQuote{other.setQuote}, setHeader{other.setHeader}, nullStrings{other.nullStrings} {}
};

struct CSVReaderConfig {
    CSVOption option;
    bool parallel;

    CSVReaderConfig() : option{}, parallel{CopyConstants::DEFAULT_CSV_PARALLEL} {}
    EXPLICIT_COPY_DEFAULT_MOVE(CSVReaderConfig);

    static CSVReaderConfig construct(const case_insensitive_map_t<Value>& options);

private:
    CSVReaderConfig(const CSVReaderConfig& other)
        : option{other.option.copy()}, parallel{other.parallel} {}
};

} // namespace common
} // namespace lbug
