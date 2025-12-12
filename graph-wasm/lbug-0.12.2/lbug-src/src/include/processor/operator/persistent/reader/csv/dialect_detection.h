#pragma once

#include <vector>

#include "common/copier_config/csv_reader_config.h"

namespace lbug {
namespace processor {

struct DialectOption {
    char delimiter = ',';
    char quoteChar = '"';
    char escapeChar = '"';
    bool everQuoted = false;
    bool everEscaped = false;
    bool doDialectDetection = true;

    DialectOption() = default;
    DialectOption(char delim, char quote, char escape)
        : delimiter(delim), quoteChar(quote), escapeChar(escape), everQuoted(false),
          everEscaped(false), doDialectDetection(true) {}
};

std::vector<DialectOption> generateDialectOptions(const common::CSVOption& option);

} // namespace processor
} // namespace lbug
