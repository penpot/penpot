#include "function/table/bind_input.h"

#include "binder/expression/expression_util.h"
#include "binder/expression/literal_expression.h"

namespace lbug {
namespace function {

void TableFuncBindInput::addLiteralParam(common::Value value) {
    params.push_back(std::make_shared<binder::LiteralExpression>(std::move(value), ""));
}

common::Value TableFuncBindInput::getValue(common::idx_t idx) const {
    binder::ExpressionUtil::validateExpressionType(*params[idx], common::ExpressionType::LITERAL);
    return params[idx]->constCast<binder::LiteralExpression>().getValue();
}

template<typename T>
T TableFuncBindInput::getLiteralVal(common::idx_t idx) const {
    return getValue(idx).getValue<T>();
}

template LBUG_API std::string TableFuncBindInput::getLiteralVal<std::string>(
    common::idx_t idx) const;
template LBUG_API int64_t TableFuncBindInput::getLiteralVal<int64_t>(common::idx_t idx) const;
template LBUG_API uint64_t TableFuncBindInput::getLiteralVal<uint64_t>(common::idx_t idx) const;
template LBUG_API uint32_t TableFuncBindInput::getLiteralVal<uint32_t>(common::idx_t idx) const;
template LBUG_API uint8_t* TableFuncBindInput::getLiteralVal<uint8_t*>(common::idx_t idx) const;

} // namespace function
} // namespace lbug
