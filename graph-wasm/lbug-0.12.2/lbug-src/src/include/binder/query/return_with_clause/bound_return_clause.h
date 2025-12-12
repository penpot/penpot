#pragma once

#include "binder/bound_statement_result.h"
#include "bound_projection_body.h"

namespace lbug {
namespace binder {

class BoundReturnClause {
public:
    explicit BoundReturnClause(BoundProjectionBody projectionBody)
        : projectionBody{std::move(projectionBody)} {}
    BoundReturnClause(BoundProjectionBody projectionBody, BoundStatementResult statementResult)
        : projectionBody{std::move(projectionBody)}, statementResult{std::move(statementResult)} {}
    DELETE_COPY_DEFAULT_MOVE(BoundReturnClause);
    virtual ~BoundReturnClause() = default;

    inline const BoundProjectionBody* getProjectionBody() const { return &projectionBody; }

    inline const BoundStatementResult* getStatementResult() const { return &statementResult; }

protected:
    BoundProjectionBody projectionBody;
    BoundStatementResult statementResult;
};

} // namespace binder
} // namespace lbug
