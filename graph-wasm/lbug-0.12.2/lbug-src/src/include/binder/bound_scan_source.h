#pragma once

#include "binder/bound_statement.h"
#include "binder/copy/bound_query_scan_info.h"
#include "bound_table_scan_info.h"
#include "common/enums/scan_source_type.h"
#include "function/table/scan_file_function.h"

namespace lbug {
namespace binder {

struct BoundBaseScanSource {
    common::ScanSourceType type;

    explicit BoundBaseScanSource(common::ScanSourceType type) : type{type} {}
    virtual ~BoundBaseScanSource() = default;

    virtual expression_vector getColumns() = 0;
    virtual expression_vector getWarningColumns() const { return expression_vector{}; };
    virtual bool getIgnoreErrorsOption() const {
        return common::CopyConstants::DEFAULT_IGNORE_ERRORS;
    };
    virtual common::column_id_t getNumWarningDataColumns() const { return 0; }

    virtual std::unique_ptr<BoundBaseScanSource> copy() const = 0;

    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }

protected:
    BoundBaseScanSource(const BoundBaseScanSource& other) : type{other.type} {}
};

struct BoundTableScanSource final : BoundBaseScanSource {
    BoundTableScanInfo info;

    explicit BoundTableScanSource(common::ScanSourceType type, BoundTableScanInfo info)
        : BoundBaseScanSource{type}, info{std::move(info)} {}
    BoundTableScanSource(const BoundTableScanSource& other)
        : BoundBaseScanSource{other}, info{other.info.copy()} {}

    expression_vector getColumns() override { return info.bindData->columns; }
    expression_vector getWarningColumns() const override;
    bool getIgnoreErrorsOption() const override;
    common::column_id_t getNumWarningDataColumns() const override {
        switch (type) {
        case common::ScanSourceType::FILE:
            return info.bindData->constPtrCast<function::ScanFileBindData>()->numWarningDataColumns;
        default:
            return 0;
        }
    }

    std::unique_ptr<BoundBaseScanSource> copy() const override {
        return std::make_unique<BoundTableScanSource>(*this);
    }
};

struct BoundQueryScanSource final : BoundBaseScanSource {
    // Use shared ptr to avoid copy BoundStatement.
    // We should consider implement a copy constructor though.
    std::shared_ptr<BoundStatement> statement;
    BoundQueryScanSourceInfo info;

    explicit BoundQueryScanSource(std::shared_ptr<BoundStatement> statement,
        BoundQueryScanSourceInfo info)
        : BoundBaseScanSource{common::ScanSourceType::QUERY}, statement{std::move(statement)},
          info(std::move(info)) {}
    BoundQueryScanSource(const BoundQueryScanSource& other) = default;

    bool getIgnoreErrorsOption() const override;

    expression_vector getColumns() override {
        return statement->getStatementResult()->getColumns();
    }

    std::unique_ptr<BoundBaseScanSource> copy() const override {
        return std::make_unique<BoundQueryScanSource>(*this);
    }
};

} // namespace binder
} // namespace lbug
