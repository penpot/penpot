#pragma once

#include "binder/bound_statement_visitor.h"

namespace lbug {
namespace binder {

class ConfidentialStatementAnalyzer final : public BoundStatementVisitor {
public:
    bool isConfidential() const { return confidentialStatement; }

private:
    void visitStandaloneCall(const BoundStatement& boundStatement) override;

private:
    bool confidentialStatement = false;
};

} // namespace binder
} // namespace lbug
