#pragma once

#include "processor/operator/sink.h"

namespace lbug {
namespace processor {

class ImportDB final : public SimpleSink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::IMPORT_DATABASE;

public:
    ImportDB(std::string query, std::string indexQuery,
        std::shared_ptr<FactorizedTable> messageTable, physical_op_id id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : SimpleSink{type_, std::move(messageTable), id, std::move(printInfo)},
          query{std::move(query)}, indexQuery{std::move(indexQuery)} {}

    void executeInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<ImportDB>(query, indexQuery, messageTable, id, printInfo->copy());
    }

private:
    std::string query;
    std::string indexQuery;
};

} // namespace processor
} // namespace lbug
