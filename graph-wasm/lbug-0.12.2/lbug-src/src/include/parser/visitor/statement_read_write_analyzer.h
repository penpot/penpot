#pragma once

#include "parser/expression/parsed_expression.h"
#include "parser/parsed_statement_visitor.h"

namespace lbug {
namespace parser {

class StatementReadWriteAnalyzer final : public StatementVisitor {
public:
    explicit StatementReadWriteAnalyzer(main::ClientContext* context)
        : StatementVisitor{}, readOnly{true}, context{context} {}

    bool isReadOnly() const { return readOnly; }

private:
    void visitCreateSequence(const Statement& /*statement*/) override { readOnly = false; }
    void visitDrop(const Statement& /*statement*/) override { readOnly = false; }
    void visitCreateTable(const Statement& /*statement*/) override { readOnly = false; }
    void visitCreateType(const Statement& /*statement*/) override { readOnly = false; }
    void visitAlter(const Statement& /*statement*/) override { readOnly = false; }
    void visitCopyFrom(const Statement& /*statement*/) override { readOnly = false; }
    void visitStandaloneCall(const Statement& /*statement*/) override { readOnly = true; }
    void visitStandaloneCallFunction(const Statement& /*statement*/) override { readOnly = false; }
    void visitCreateMacro(const Statement& /*statement*/) override { readOnly = false; }
    void visitExtension(const Statement& /*statement*/) override;

    void visitReadingClause(const ReadingClause* readingClause) override;
    void visitWithClause(const WithClause* withClause) override;
    void visitReturnClause(const ReturnClause* returnClause) override;

    void visitUpdatingClause(const UpdatingClause* /*updatingClause*/) override {
        readOnly = false;
    }

    void visitExtensionClause(const Statement& /* statement*/) override { readOnly = false; }

    bool isExprReadOnly(const ParsedExpression* expr);

private:
    bool readOnly;
    main::ClientContext* context;
};

} // namespace parser
} // namespace lbug
