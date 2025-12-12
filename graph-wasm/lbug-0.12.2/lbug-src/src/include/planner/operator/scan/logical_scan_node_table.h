#pragma once

#include "binder/expression/expression_util.h"
#include "planner/operator/logical_operator.h"
#include "storage/predicate/column_predicate.h"

namespace lbug {
namespace planner {

enum class LogicalScanNodeTableType : uint8_t {
    SCAN = 0,
    PRIMARY_KEY_SCAN = 1,
};

struct ExtraScanNodeTableInfo {
    virtual ~ExtraScanNodeTableInfo() = default;
    virtual std::unique_ptr<ExtraScanNodeTableInfo> copy() const = 0;

    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }
};

struct PrimaryKeyScanInfo final : ExtraScanNodeTableInfo {
    std::shared_ptr<binder::Expression> key;

    explicit PrimaryKeyScanInfo(std::shared_ptr<binder::Expression> key) : key{std::move(key)} {}

    std::unique_ptr<ExtraScanNodeTableInfo> copy() const override {
        return std::make_unique<PrimaryKeyScanInfo>(key);
    }
};

struct LogicalScanNodeTablePrintInfo final : OPPrintInfo {
    std::shared_ptr<binder::Expression> nodeID;
    binder::expression_vector properties;

    LogicalScanNodeTablePrintInfo(std::shared_ptr<binder::Expression> nodeID,
        binder::expression_vector properties)
        : nodeID{std::move(nodeID)}, properties{std::move(properties)} {}

    std::string toString() const override {
        auto result = "Tables: " + nodeID->toString();
        if (nodeID->hasAlias()) {
            result += "Alias: " + nodeID->getAlias();
        }
        result += ",Properties :" + binder::ExpressionUtil::toString(properties);
        return result;
    }
};

class LogicalScanNodeTable final : public LogicalOperator {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::SCAN_NODE_TABLE;
    static constexpr LogicalScanNodeTableType defaultScanType = LogicalScanNodeTableType::SCAN;

public:
    LogicalScanNodeTable(std::shared_ptr<binder::Expression> nodeID,
        std::vector<common::table_id_t> nodeTableIDs, binder::expression_vector properties,
        common::cardinality_t cardinality = 0)
        : LogicalOperator{type_}, scanType{defaultScanType}, nodeID{std::move(nodeID)},
          nodeTableIDs{std::move(nodeTableIDs)}, properties{std::move(properties)} {
        this->cardinality = cardinality;
    }
    LogicalScanNodeTable(const LogicalScanNodeTable& other);

    void computeFactorizedSchema() override;
    void computeFlatSchema() override;

    std::string getExpressionsForPrinting() const override {
        return nodeID->toString() + " " + binder::ExpressionUtil::toString(properties);
    }

    LogicalScanNodeTableType getScanType() const { return scanType; }
    void setScanType(LogicalScanNodeTableType scanType_) { scanType = scanType_; }

    std::shared_ptr<binder::Expression> getNodeID() const { return nodeID; }
    std::vector<common::table_id_t> getTableIDs() const { return nodeTableIDs; }

    binder::expression_vector getProperties() const { return properties; }
    void addProperty(std::shared_ptr<binder::Expression> expr) {
        properties.push_back(std::move(expr));
    }
    void setPropertyPredicates(std::vector<storage::ColumnPredicateSet> predicates) {
        propertyPredicates = std::move(predicates);
    }
    const std::vector<storage::ColumnPredicateSet>& getPropertyPredicates() const {
        return propertyPredicates;
    }

    void setExtraInfo(std::unique_ptr<ExtraScanNodeTableInfo> info) { extraInfo = std::move(info); }

    ExtraScanNodeTableInfo* getExtraInfo() const { return extraInfo.get(); }

    std::unique_ptr<OPPrintInfo> getPrintInfo() const override {
        return std::make_unique<LogicalScanNodeTablePrintInfo>(nodeID, properties);
    }

    std::unique_ptr<LogicalOperator> copy() override;

private:
    LogicalScanNodeTableType scanType;
    std::shared_ptr<binder::Expression> nodeID;
    std::vector<common::table_id_t> nodeTableIDs;
    binder::expression_vector properties;
    std::vector<storage::ColumnPredicateSet> propertyPredicates;
    std::unique_ptr<ExtraScanNodeTableInfo> extraInfo;
};

} // namespace planner
} // namespace lbug
