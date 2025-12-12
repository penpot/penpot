#pragma once

#include "common/types/types.h"
#include "parser/expression/parsed_expression.h"

namespace lbug {
namespace binder {

struct LBUG_API ColumnDefinition {
    std::string name;
    common::LogicalType type;

    ColumnDefinition() = default;
    ColumnDefinition(std::string name, common::LogicalType type)
        : name{std::move(name)}, type{std::move(type)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(ColumnDefinition);

private:
    ColumnDefinition(const ColumnDefinition& other) : name{other.name}, type{other.type.copy()} {}
};

struct LBUG_API PropertyDefinition {
    ColumnDefinition columnDefinition;
    std::unique_ptr<parser::ParsedExpression> defaultExpr;

    PropertyDefinition() = default;
    explicit PropertyDefinition(ColumnDefinition columnDefinition);
    PropertyDefinition(ColumnDefinition columnDefinition,
        std::unique_ptr<parser::ParsedExpression> defaultExpr)
        : columnDefinition{std::move(columnDefinition)}, defaultExpr{std::move(defaultExpr)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(PropertyDefinition);

    std::string getName() const { return columnDefinition.name; }
    const common::LogicalType& getType() const { return columnDefinition.type; }
    std::string getDefaultExpressionName() const { return defaultExpr->getRawName(); }
    void rename(const std::string& newName) { columnDefinition.name = newName; }

    void serialize(common::Serializer& serializer) const;
    static PropertyDefinition deserialize(common::Deserializer& deserializer);

private:
    PropertyDefinition(const PropertyDefinition& other)
        : columnDefinition{other.columnDefinition.copy()}, defaultExpr{other.defaultExpr->copy()} {}
};

} // namespace binder
} // namespace lbug
