#pragma once

#include "function.h"

namespace lbug {
namespace binder {
class ExpressionBinder;
}
namespace function {

struct RewriteFunctionBindInput {
    main::ClientContext* context;
    binder::ExpressionBinder* expressionBinder;
    binder::expression_vector arguments;

    RewriteFunctionBindInput(main::ClientContext* context,
        binder::ExpressionBinder* expressionBinder, binder::expression_vector arguments)
        : context{context}, expressionBinder{expressionBinder}, arguments{std::move(arguments)} {}
};

// Rewrite function to a different expression, e.g. id(n) -> n._id.
using rewrite_func_rewrite_t =
    std::function<std::shared_ptr<binder::Expression>(const RewriteFunctionBindInput&)>;

// We write for the following functions
// ID(n) -> n._id
struct RewriteFunction final : Function {
    rewrite_func_rewrite_t rewriteFunc;

    RewriteFunction(std::string name, std::vector<common::LogicalTypeID> parameterTypeIDs,
        rewrite_func_rewrite_t rewriteFunc)
        : Function{std::move(name), std::move(parameterTypeIDs)},
          rewriteFunc{std::move(rewriteFunc)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(RewriteFunction)

private:
    RewriteFunction(const RewriteFunction& other)
        : Function{other}, rewriteFunc{other.rewriteFunc} {}
};

} // namespace function
} // namespace lbug
