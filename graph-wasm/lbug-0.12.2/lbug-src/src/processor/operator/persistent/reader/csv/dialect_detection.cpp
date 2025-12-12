#include "processor/operator/persistent/reader/csv/dialect_detection.h"

namespace lbug {
namespace processor {

std::vector<DialectOption> generateDialectOptions(const common::CSVOption& option) {
    std::vector<DialectOption> options;
    std::string delimiters = "";
    std::string quoteChars = "";
    std::string escapeChars = "";

    if (option.setDelim) {
        delimiters += option.delimiter;
    } else {
        delimiters.assign(common::CopyConstants::DEFAULT_CSV_DELIMITER_SEARCH_SPACE.begin(),
            common::CopyConstants::DEFAULT_CSV_DELIMITER_SEARCH_SPACE.end());
    }

    if (option.setQuote) {
        quoteChars += option.quoteChar;
    } else {
        quoteChars.resize(common::CopyConstants::DEFAULT_CSV_QUOTE_SEARCH_SPACE.size());
        quoteChars.assign(common::CopyConstants::DEFAULT_CSV_QUOTE_SEARCH_SPACE.begin(),
            common::CopyConstants::DEFAULT_CSV_QUOTE_SEARCH_SPACE.end());
    }

    if (option.setEscape) {
        escapeChars += option.escapeChar;
    } else {
        escapeChars.assign(common::CopyConstants::DEFAULT_CSV_ESCAPE_SEARCH_SPACE.begin(),
            common::CopyConstants::DEFAULT_CSV_ESCAPE_SEARCH_SPACE.end());
    }

    for (auto& delim : delimiters) {
        for (auto& quote : quoteChars) {
            for (auto& escape : escapeChars) {
                DialectOption option{delim, quote, escape};
                options.push_back(option);
            }
        }
    }
    return options;
}

} // namespace processor
} // namespace lbug
