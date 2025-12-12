#pragma once

#include "processor/operator/filtering_operator.h"
#include "processor/operator/physical_operator.h"

namespace lbug {
namespace processor {

struct FlattenLocalState {
    uint64_t currentIdx = 0;
    uint64_t sizeToFlatten = 0;
};

class Flatten final : public PhysicalOperator, SelVectorOverWriter {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::FLATTEN;

public:
    Flatten(data_chunk_pos_t dataChunkToFlattenPos, std::unique_ptr<PhysicalOperator> child,
        uint32_t id, std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{type_, std::move(child), id, std::move(printInfo)},
          dataChunkToFlattenPos{dataChunkToFlattenPos}, dataChunkState{nullptr} {}

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    bool getNextTuplesInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return make_unique<Flatten>(dataChunkToFlattenPos, children[0]->copy(), id,
            printInfo->copy());
    }

private:
    void resetCurrentSelVector(const common::SelectionVector& selVector) override;

private:
    data_chunk_pos_t dataChunkToFlattenPos;
    common::DataChunkState* dataChunkState;
    std::unique_ptr<FlattenLocalState> localState;
};

} // namespace processor
} // namespace lbug
