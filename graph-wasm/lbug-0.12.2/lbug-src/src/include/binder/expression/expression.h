#pragma once

#include <functional>
#include <memory>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "common/assert.h"
#include "common/cast.h"
#include "common/copy_constructors.h"
#include "common/enums/expression_type.h"
#include "common/types/types.h"

namespace lbug {
namespace binder {

class Expression;
using expression_vector = std::vector<std::shared_ptr<Expression>>;
using expression_pair = std::pair<std::shared_ptr<Expression>, std::shared_ptr<Expression>>;

struct ExpressionHasher;
struct ExpressionEquality;
using expression_set =
    std::unordered_set<std::shared_ptr<Expression>, ExpressionHasher, ExpressionEquality>;
template<typename T>
using expression_map =
    std::unordered_map<std::shared_ptr<Expression>, T, ExpressionHasher, ExpressionEquality>;

class LBUG_API Expression : public std::enable_shared_from_this<Expression> {
    friend class ExpressionChildrenCollector;

public:
    Expression(common::ExpressionType expressionType, common::LogicalType dataType,
        expression_vector children, std::string uniqueName)
        : expressionType{expressionType}, dataType{std::move(dataType)},
          uniqueName{std::move(uniqueName)}, children{std::move(children)} {}
    // Create binary expression.
    Expression(common::ExpressionType expressionType, common::LogicalType dataType,
        const std::shared_ptr<Expression>& left, const std::shared_ptr<Expression>& right,
        std::string uniqueName)
        : Expression{expressionType, std::move(dataType), expression_vector{left, right},
              std::move(uniqueName)} {}
    // Create unary expression.
    Expression(common::ExpressionType expressionType, common::LogicalType dataType,
        const std::shared_ptr<Expression>& child, std::string uniqueName)
        : Expression{expressionType, std::move(dataType), expression_vector{child},
              std::move(uniqueName)} {}
    // Create leaf expression
    Expression(common::ExpressionType expressionType, common::LogicalType dataType,
        std::string uniqueName)
        : Expression{expressionType, std::move(dataType), expression_vector{},
              std::move(uniqueName)} {}
    DELETE_COPY_DEFAULT_MOVE(Expression);
    virtual ~Expression();

    void setUniqueName(const std::string& name) { uniqueName = name; }
    std::string getUniqueName() const {
        KU_ASSERT(!uniqueName.empty());
        return uniqueName;
    }

    virtual void cast(const common::LogicalType& type);
    const common::LogicalType& getDataType() const { return dataType; }

    void setAlias(const std::string& newAlias) { alias = newAlias; }
    bool hasAlias() const { return !alias.empty(); }
    std::string getAlias() const { return alias; }

    common::idx_t getNumChildren() const { return children.size(); }
    std::shared_ptr<Expression> getChild(common::idx_t idx) const {
        KU_ASSERT(idx < children.size());
        return children[idx];
    }
    expression_vector getChildren() const { return children; }
    void setChild(common::idx_t idx, std::shared_ptr<Expression> child) {
        KU_ASSERT(idx < children.size());
        children[idx] = std::move(child);
    }

    expression_vector splitOnAND();

    bool operator==(const Expression& rhs) const { return uniqueName == rhs.uniqueName; }

    std::string toString() const { return hasAlias() ? alias : toStringInternal(); }

    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
    template<class TARGET>
    TARGET* ptrCast() {
        return common::ku_dynamic_cast<TARGET*>(this);
    }
    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }
    template<class TARGET>
    const TARGET* constPtrCast() const {
        return common::ku_dynamic_cast<const TARGET*>(this);
    }

protected:
    virtual std::string toStringInternal() const = 0;

public:
    common::ExpressionType expressionType;
    common::LogicalType dataType;

protected:
    // Name that serves as the unique identifier.
    std::string uniqueName;
    std::string alias;
    expression_vector children;
};

struct ExpressionHasher {
    std::size_t operator()(const std::shared_ptr<Expression>& expression) const {
        return std::hash<std::string>{}(expression->getUniqueName());
    }
};

struct ExpressionEquality {
    bool operator()(const std::shared_ptr<Expression>& left,
        const std::shared_ptr<Expression>& right) const {
        return left->getUniqueName() == right->getUniqueName();
    }
};

} // namespace binder
} // namespace lbug
