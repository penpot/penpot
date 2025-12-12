#include "extension/extension.h"
#include "parser/extension_statement.h"
#include "parser/transformer.h"

using namespace lbug::common;
using namespace lbug::extension;

namespace lbug {
namespace parser {

std::unique_ptr<Statement> Transformer::transformExtension(CypherParser::KU_ExtensionContext& ctx) {
    if (ctx.kU_InstallExtension()) {
        auto extensionRepo =
            ctx.kU_InstallExtension()->StringLiteral() ?
                transformStringLiteral(*ctx.kU_InstallExtension()->StringLiteral()) :
                ExtensionUtils::OFFICIAL_EXTENSION_REPO;

        auto installExtensionAuxInfo = std::make_unique<InstallExtensionAuxInfo>(
            std::move(extensionRepo), transformVariable(*ctx.kU_InstallExtension()->oC_Variable()),
            ctx.kU_InstallExtension()->FORCE());
        return std::make_unique<ExtensionStatement>(std::move(installExtensionAuxInfo));
    } else if (ctx.kU_UpdateExtension()) {
        // Update extension is a syntax sugar for force install extension.
        auto installExtensionAuxInfo = std::make_unique<InstallExtensionAuxInfo>(
            ExtensionUtils::OFFICIAL_EXTENSION_REPO,
            transformVariable(*ctx.kU_UpdateExtension()->oC_Variable()), true /* forceInstall */);
        return std::make_unique<ExtensionStatement>(std::move(installExtensionAuxInfo));
    } else if (ctx.kU_UninstallExtension()) {
        auto path = transformVariable(*ctx.kU_UninstallExtension()->oC_Variable());
        return std::make_unique<ExtensionStatement>(
            std::make_unique<ExtensionAuxInfo>(ExtensionAction::UNINSTALL, std::move(path)));
    } else {
        auto path = ctx.kU_LoadExtension()->StringLiteral() ?
                        transformStringLiteral(*ctx.kU_LoadExtension()->StringLiteral()) :
                        transformVariable(*ctx.kU_LoadExtension()->oC_Variable());
        auto installExtensionAuxInfo =
            std::make_unique<ExtensionAuxInfo>(ExtensionAction::LOAD, std::move(path));
        return std::make_unique<ExtensionStatement>(std::move(installExtensionAuxInfo));
    }
}

} // namespace parser
} // namespace lbug
