#pragma once

#include "parser/expression/parsed_expression.h"
#include "parser/statement.h"

namespace lbug {
namespace parser {

class ExportDB : public Statement {
public:
    explicit ExportDB(std::string filePath)
        : Statement{common::StatementType::EXPORT_DATABASE}, filePath{std::move(filePath)} {}

    inline void setParsingOption(options_t options) { parsingOptions = std::move(options); }
    inline const options_t& getParsingOptionsRef() const { return parsingOptions; }
    inline std::string getFilePath() const { return filePath; }

private:
    options_t parsingOptions;
    std::string filePath;
};

class ImportDB : public Statement {
public:
    explicit ImportDB(std::string filePath)
        : Statement{common::StatementType::IMPORT_DATABASE}, filePath{std::move(filePath)} {}

    inline std::string getFilePath() const { return filePath; }

private:
    std::string filePath;
};

} // namespace parser
} // namespace lbug
