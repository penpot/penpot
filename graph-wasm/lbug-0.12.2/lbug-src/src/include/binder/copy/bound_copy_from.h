#pragma once

#include "binder/bound_scan_source.h"
#include "binder/expression/expression.h"
#include "common/enums/column_evaluate_type.h"
#include "common/enums/table_type.h"
#include "index_look_up_info.h"

namespace lbug {
namespace binder {

struct ExtraBoundCopyFromInfo {
    virtual ~ExtraBoundCopyFromInfo() = default;
    virtual std::unique_ptr<ExtraBoundCopyFromInfo> copy() const = 0;

    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }
};

struct LBUG_API BoundCopyFromInfo {
    // Name of table to copy into.
    std::string tableName;
    // Type of table.
    common::TableType tableType;
    // Data source.
    std::unique_ptr<BoundBaseScanSource> source;
    // Row offset.
    std::shared_ptr<Expression> offset;
    expression_vector columnExprs;
    std::vector<common::ColumnEvaluateType> columnEvaluateTypes;
    std::unique_ptr<ExtraBoundCopyFromInfo> extraInfo;

    BoundCopyFromInfo(std::string tableName, common::TableType tableType,
        std::unique_ptr<BoundBaseScanSource> source, std::shared_ptr<Expression> offset,
        expression_vector columnExprs, std::vector<common::ColumnEvaluateType> columnEvaluateTypes,
        std::unique_ptr<ExtraBoundCopyFromInfo> extraInfo)
        : tableName{std::move(tableName)}, tableType{tableType}, source{std::move(source)},
          offset{std::move(offset)}, columnExprs{std::move(columnExprs)},
          columnEvaluateTypes{std::move(columnEvaluateTypes)}, extraInfo{std::move(extraInfo)} {}

    EXPLICIT_COPY_DEFAULT_MOVE(BoundCopyFromInfo);

    expression_vector getSourceColumns() const {
        return source ? source->getColumns() : expression_vector{};
    }
    expression_vector getWarningColumns() const {
        return offset ? source->getWarningColumns() : expression_vector{};
    }

    bool getIgnoreErrorsOption() const { return source ? source->getIgnoreErrorsOption() : false; }

private:
    BoundCopyFromInfo(const BoundCopyFromInfo& other)
        : tableName{other.tableName}, tableType{other.tableType}, offset{other.offset},
          columnExprs{other.columnExprs}, columnEvaluateTypes{other.columnEvaluateTypes} {
        source = other.source ? other.source->copy() : nullptr;
        if (other.extraInfo) {
            extraInfo = other.extraInfo->copy();
        }
    }
};

struct ExtraBoundCopyRelInfo final : ExtraBoundCopyFromInfo {
    std::string fromTableName;
    std::string toTableName;
    // We process internal ID column as offset (INT64) column until partitioner. In partitioner,
    // we need to manually change offset(INT64) type to internal ID type.
    std::vector<common::idx_t> internalIDColumnIndices;
    std::vector<IndexLookupInfo> infos;

    ExtraBoundCopyRelInfo(std::string fromTableName, std::string toTableName,
        std::vector<common::idx_t> internalIDColumnIndices, std::vector<IndexLookupInfo> infos)
        : fromTableName{std::move(fromTableName)}, toTableName{std::move(toTableName)},
          internalIDColumnIndices{std::move(internalIDColumnIndices)}, infos{std::move(infos)} {}
    ExtraBoundCopyRelInfo(const ExtraBoundCopyRelInfo& other)
        : fromTableName{other.fromTableName}, toTableName{other.toTableName},
          internalIDColumnIndices{other.internalIDColumnIndices}, infos{other.infos} {}

    std::unique_ptr<ExtraBoundCopyFromInfo> copy() const override {
        return std::make_unique<ExtraBoundCopyRelInfo>(*this);
    }
};

class BoundCopyFrom final : public BoundStatement {
    static constexpr common::StatementType statementType_ = common::StatementType::COPY_FROM;

public:
    explicit BoundCopyFrom(BoundCopyFromInfo info)
        : BoundStatement{statementType_, BoundStatementResult::createSingleStringColumnResult()},
          info{std::move(info)} {}

    const BoundCopyFromInfo* getInfo() const { return &info; }

private:
    BoundCopyFromInfo info;
};

} // namespace binder
} // namespace lbug
