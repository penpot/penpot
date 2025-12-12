#include "main/query_summary.h"

#include "common/enums/statement_type.h"

using namespace lbug::common;

namespace lbug {
namespace main {

double QuerySummary::getCompilingTime() const {
    return preparedSummary.compilingTime;
}

double QuerySummary::getExecutionTime() const {
    return executionTime;
}

void QuerySummary::setExecutionTime(double time) {
    executionTime = time;
}

void QuerySummary::incrementCompilingTime(double increment) {
    preparedSummary.compilingTime += increment;
}

void QuerySummary::incrementExecutionTime(double increment) {
    executionTime += increment;
}

bool QuerySummary::isExplain() const {
    return preparedSummary.statementType == StatementType::EXPLAIN;
}

StatementType QuerySummary::getStatementType() const {
    return preparedSummary.statementType;
}

} // namespace main
} // namespace lbug
