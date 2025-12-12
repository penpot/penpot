#pragma once

#include "binder/copy/bound_copy_from.h"
#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

struct LogicalPartitioningInfo {
    common::idx_t keyIdx;

    explicit LogicalPartitioningInfo(common::idx_t keyIdx) : keyIdx{keyIdx} {}
    LogicalPartitioningInfo(const LogicalPartitioningInfo& other) : keyIdx{other.keyIdx} {}

    EXPLICIT_COPY_DEFAULT_MOVE(LogicalPartitioningInfo);
};

struct LogicalPartitionerInfo {
    std::shared_ptr<binder::Expression> offset;
    std::vector<LogicalPartitioningInfo> partitioningInfos;

    explicit LogicalPartitionerInfo(std::shared_ptr<binder::Expression> offset)
        : offset{std::move(offset)} {}
    LogicalPartitionerInfo(const LogicalPartitionerInfo& other) : offset{other.offset} {
        for (auto& partitioningInfo : other.partitioningInfos) {
            partitioningInfos.push_back(partitioningInfo.copy());
        }
    }

    EXPLICIT_COPY_DEFAULT_MOVE(LogicalPartitionerInfo);

    common::idx_t getNumInfos() const { return partitioningInfos.size(); }
    LogicalPartitioningInfo& getInfo(common::idx_t idx) {
        KU_ASSERT(idx < partitioningInfos.size());
        return partitioningInfos[idx];
    }
    const LogicalPartitioningInfo& getInfo(common::idx_t idx) const {
        KU_ASSERT(idx < partitioningInfos.size());
        return partitioningInfos[idx];
    }
};

class LogicalPartitioner final : public LogicalOperator {
    static constexpr LogicalOperatorType type = LogicalOperatorType::PARTITIONER;

public:
    LogicalPartitioner(LogicalPartitionerInfo info, binder::BoundCopyFromInfo copyFromInfo,
        std::shared_ptr<LogicalOperator> child)
        : LogicalOperator{type, std::move(child)}, info{std::move(info)},
          copyFromInfo{std::move(copyFromInfo)} {}

    void computeFactorizedSchema() override;
    void computeFlatSchema() override;

    std::string getExpressionsForPrinting() const override;

    LogicalPartitionerInfo& getInfo() { return info; }
    const LogicalPartitionerInfo& getInfo() const { return info; }

    std::unique_ptr<LogicalOperator> copy() override {
        return make_unique<LogicalPartitioner>(info.copy(), copyFromInfo.copy(),
            children[0]->copy());
    }

private:
    LogicalPartitionerInfo info;

public:
    binder::BoundCopyFromInfo copyFromInfo;
};

} // namespace planner
} // namespace lbug
