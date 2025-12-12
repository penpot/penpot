#include "binder/binder.h"
#include "binder/bound_attach_database.h"
#include "common/exception/binder.h"
#include "common/string_utils.h"
#include "parser/attach_database.h"
#include "parser/expression/parsed_literal_expression.h"

namespace lbug {
namespace binder {

static AttachInfo bindAttachInfo(const parser::AttachInfo& attachInfo) {
    binder::AttachOption attachOption;
    for (auto& [name, value] : attachInfo.options) {
        if (value->getExpressionType() != common::ExpressionType::LITERAL) {
            throw common::BinderException{"Attach option must be a literal expression."};
        }
        auto val = value->constPtrCast<parser::ParsedLiteralExpression>()->getValue();
        attachOption.options.emplace(name, std::move(val));
    }

    if (common::StringUtils::getUpper(attachInfo.dbType) == common::ATTACHED_LBUG_DB_TYPE &&
        attachInfo.dbAlias.empty()) {
        throw common::BinderException{"Attaching a lbug database without an alias is not allowed."};
    }
    return binder::AttachInfo{attachInfo.dbPath, attachInfo.dbAlias, attachInfo.dbType,
        std::move(attachOption)};
}

std::unique_ptr<BoundStatement> Binder::bindAttachDatabase(const parser::Statement& statement) {
    auto& attachDatabase = statement.constCast<parser::AttachDatabase>();
    auto boundAttachInfo = bindAttachInfo(attachDatabase.getAttachInfo());
    return std::make_unique<BoundAttachDatabase>(std::move(boundAttachInfo));
}

} // namespace binder
} // namespace lbug
