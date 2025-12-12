#include "binder/ddl/property_definition.h"

#include "common/serializer/deserializer.h"
#include "common/serializer/serializer.h"
#include "parser/expression/parsed_literal_expression.h"

using namespace lbug::common;
using namespace lbug::parser;

namespace lbug {
namespace binder {

PropertyDefinition::PropertyDefinition(ColumnDefinition columnDefinition)
    : columnDefinition{std::move(columnDefinition)} {
    defaultExpr = std::make_unique<ParsedLiteralExpression>(Value::createNullValue(), "NULL");
}

void PropertyDefinition::serialize(Serializer& serializer) const {
    serializer.serializeValue(columnDefinition.name);
    columnDefinition.type.serialize(serializer);
    defaultExpr->serialize(serializer);
}

PropertyDefinition PropertyDefinition::deserialize(Deserializer& deserializer) {
    std::string name;
    deserializer.deserializeValue(name);
    auto type = LogicalType::deserialize(deserializer);
    auto columnDefinition = ColumnDefinition(name, std::move(type));
    auto defaultExpr = ParsedExpression::deserialize(deserializer);
    return PropertyDefinition(std::move(columnDefinition), std::move(defaultExpr));
}

} // namespace binder
} // namespace lbug
