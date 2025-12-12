#include "binder/binder.h"
#include "binder/bound_standalone_call.h"
#include "binder/expression/expression_util.h"
#include "binder/expression_visitor.h"
#include "common/cast.h"
#include "common/exception/binder.h"
#include "main/client_context.h"
#include "main/db_config.h"
#include "parser/standalone_call.h"

using namespace lbug::common;

namespace lbug {
namespace binder {

std::unique_ptr<BoundStatement> Binder::bindStandaloneCall(const parser::Statement& statement) {
    auto& callStatement = ku_dynamic_cast<const parser::StandaloneCall&>(statement);
    const main::Option* option = main::DBConfig::getOptionByName(callStatement.getOptionName());
    if (option == nullptr) {
        option = clientContext->getExtensionOption(callStatement.getOptionName());
    }
    if (option == nullptr) {
        throw BinderException{"Invalid option name: " + callStatement.getOptionName() + "."};
    }
    auto optionValue = expressionBinder.bindExpression(*callStatement.getOptionValue());
    ExpressionUtil::validateExpressionType(*optionValue, ExpressionType::LITERAL);
    if (LogicalTypeUtils::isFloatingPoint(optionValue->dataType.getLogicalTypeID()) &&
        LogicalTypeUtils::isIntegral(LogicalType(option->parameterType))) {
        throw BinderException{stringFormat(
            "Expression {} has data type {} but expected {}. Implicit cast is not supported.",
            optionValue->toString(),
            LogicalTypeUtils::toString(optionValue->dataType.getLogicalTypeID()),
            LogicalTypeUtils::toString(option->parameterType))};
    }
    optionValue =
        expressionBinder.implicitCastIfNecessary(optionValue, LogicalType(option->parameterType));
    if (ConstantExpressionVisitor::needFold(*optionValue)) {
        optionValue = expressionBinder.foldExpression(optionValue);
    }
    return std::make_unique<BoundStandaloneCall>(option, std::move(optionValue));
}

} // namespace binder
} // namespace lbug
