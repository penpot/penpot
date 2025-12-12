#pragma once

#include "parser/statement.h"

namespace lbug {
namespace parser {

class CreateType final : public Statement {
    static constexpr common::StatementType type_ = common::StatementType::CREATE_TYPE;

public:
    CreateType(std::string name, std::string dataType)
        : Statement{type_}, name{std::move(name)}, dataType{std::move(dataType)} {}

    std::string getName() const { return name; }

    std::string getDataType() const { return dataType; }

private:
    std::string name;
    std::string dataType;
};

} // namespace parser
} // namespace lbug
