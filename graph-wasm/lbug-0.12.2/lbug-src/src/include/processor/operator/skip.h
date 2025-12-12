#pragma once

#include <atomic>

#include "processor/operator/filtering_operator.h"
#include "processor/operator/physical_operator.h"

namespace lbug {
namespace processor {

struct SkipPrintInfo final : OPPrintInfo {
    uint64_t number;

    explicit SkipPrintInfo(std::int64_t number) : number(number) {}
    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<SkipPrintInfo>(new SkipPrintInfo(*this));
    }

private:
    SkipPrintInfo(const SkipPrintInfo& other) : OPPrintInfo(other), number(other.number) {}
};

class Skip final : public PhysicalOperator, public SelVectorOverWriter {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::SKIP;

public:
    Skip(uint64_t skipNumber, std::shared_ptr<std::atomic_uint64_t> counter,
        uint32_t dataChunkToSelectPos, std::unordered_set<uint32_t> dataChunksPosInScope,
        std::unique_ptr<PhysicalOperator> child, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{type_, std::move(child), id, std::move(printInfo)},
          skipNumber{skipNumber}, counter{std::move(counter)},
          dataChunkToSelectPos{dataChunkToSelectPos},
          dataChunksPosInScope{std::move(dataChunksPosInScope)} {}

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    bool getNextTuplesInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return make_unique<Skip>(skipNumber, counter, dataChunkToSelectPos, dataChunksPosInScope,
            children[0]->copy(), id, printInfo->copy());
    }

private:
    uint64_t skipNumber;
    std::shared_ptr<std::atomic_uint64_t> counter;
    uint32_t dataChunkToSelectPos;
    std::shared_ptr<common::DataChunk> dataChunkToSelect;
    std::unordered_set<uint32_t> dataChunksPosInScope;
};

} // namespace processor
} // namespace lbug
