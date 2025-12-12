#pragma once

#include "common/cast.h"
#include "common/enums/statement_type.h"

namespace lbug {
namespace parser {

class Statement {
public:
    explicit Statement(common::StatementType statementType)
        : parsingTime{0}, statementType{statementType}, internal{false} {}

    virtual ~Statement() = default;

    common::StatementType getStatementType() const { return statementType; }
    void setToInternal() { internal = true; }
    bool isInternal() const { return internal; }
    void setParsingTime(double time) { parsingTime = time; }
    double getParsingTime() const { return parsingTime; }

    bool requireTransaction() const {
        switch (statementType) {
        case common::StatementType::TRANSACTION:
            return false;
        default:
            return true;
        }
    }

    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }
    template<class TARGET>
    const TARGET* constPtrCast() const {
        return common::ku_dynamic_cast<const TARGET*>(this);
    }

private:
    double parsingTime;
    common::StatementType statementType;
    // By setting the statement to internal, we still execute the statement, but will not return the
    // executio result as part of the query result returned to users.
    // The use case for this is when a query internally generates other queries to finish first,
    // e.g., `TableFunction::rewriteFunc`.
    bool internal;
};

} // namespace parser
} // namespace lbug
