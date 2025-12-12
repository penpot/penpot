#pragma once

#include <string>

#include "parser/expression/parsed_expression.h"

namespace lbug {
namespace parser {

struct AttachInfo {
    AttachInfo(std::string dbPath, std::string dbAlias, std::string dbType, options_t options)
        : dbPath{std::move(dbPath)}, dbAlias{std::move(dbAlias)}, dbType{std::move(dbType)},
          options{std::move(options)} {}

    std::string dbPath, dbAlias, dbType;
    options_t options;
};

} // namespace parser
} // namespace lbug
