#include "binder/expression/expression.h"

#include "common/exception/binder.h"

using namespace lbug::common;

namespace lbug {
namespace binder {

Expression::~Expression() = default;

void Expression::cast(const LogicalType&) {
    // LCOV_EXCL_START
    throw BinderException(
        stringFormat("Data type of expression {} should not be modified.", toString()));
    // LCOV_EXCL_STOP
}

expression_vector Expression::splitOnAND() {
    expression_vector result;
    if (ExpressionType::AND == expressionType) {
        for (auto& child : children) {
            for (auto& exp : child->splitOnAND()) {
                result.push_back(exp);
            }
        }
    } else {
        result.push_back(shared_from_this());
    }
    return result;
}

} // namespace binder
} // namespace lbug
