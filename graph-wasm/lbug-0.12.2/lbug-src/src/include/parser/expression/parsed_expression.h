#pragma once

#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include "common/cast.h"
#include "common/copy_constructors.h"
#include "common/enums/expression_type.h"
#include "common/types/types.h"

namespace lbug {

namespace common {
struct FileInfo;
class Serializer;
class Deserializer;
} // namespace common

namespace parser {

class ParsedExpression;
class ParsedExpressionChildrenVisitor;
using parsed_expr_vector = std::vector<std::unique_ptr<ParsedExpression>>;
using parsed_expr_pair =
    std::pair<std::unique_ptr<ParsedExpression>, std::unique_ptr<ParsedExpression>>;
using s_parsed_expr_pair = std::pair<std::string, std::unique_ptr<ParsedExpression>>;

class LBUG_API ParsedExpression {
    friend class ParsedExpressionChildrenVisitor;

public:
    ParsedExpression(common::ExpressionType type, std::unique_ptr<ParsedExpression> child,
        std::string rawName);
    ParsedExpression(common::ExpressionType type, std::unique_ptr<ParsedExpression> left,
        std::unique_ptr<ParsedExpression> right, std::string rawName);
    ParsedExpression(common::ExpressionType type, std::string rawName)
        : type{type}, rawName{std::move(rawName)} {}
    explicit ParsedExpression(common::ExpressionType type) : type{type} {}

    ParsedExpression(common::ExpressionType type, std::string alias, std::string rawName,
        parsed_expr_vector children)
        : type{type}, alias{std::move(alias)}, rawName{std::move(rawName)},
          children{std::move(children)} {}
    DELETE_COPY_DEFAULT_MOVE(ParsedExpression);
    virtual ~ParsedExpression() = default;

    common::ExpressionType getExpressionType() const { return type; }

    void setAlias(std::string name) { alias = std::move(name); }
    bool hasAlias() const { return !alias.empty(); }
    std::string getAlias() const { return alias; }

    std::string getRawName() const { return rawName; }

    common::idx_t getNumChildren() const { return children.size(); }
    ParsedExpression* getChild(common::idx_t idx) const { return children[idx].get(); }
    void setChild(common::idx_t idx, std::unique_ptr<ParsedExpression> child) {
        KU_ASSERT(idx < children.size());
        children[idx] = std::move(child);
    }

    std::string toString() const { return rawName; }

    virtual std::unique_ptr<ParsedExpression> copy() const {
        return std::make_unique<ParsedExpression>(type, alias, rawName, copyVector(children));
    }

    void serialize(common::Serializer& serializer) const;

    static std::unique_ptr<ParsedExpression> deserialize(common::Deserializer& deserializer);

    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }
    template<class TARGET>
    const TARGET* constPtrCast() const {
        return common::ku_dynamic_cast<const TARGET*>(this);
    }

private:
    virtual void serializeInternal(common::Serializer&) const {}

protected:
    common::ExpressionType type;
    std::string alias;
    std::string rawName;
    parsed_expr_vector children;
};

using options_t = std::unordered_map<std::string, std::unique_ptr<parser::ParsedExpression>>;

} // namespace parser
} // namespace lbug
