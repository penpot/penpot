#pragma once

#include "common/counter.h"
#include "common/mask.h"
#include "graph/graph.h"
#include "processor/result/factorized_table_pool.h"

namespace lbug {
namespace processor {

struct RecursiveExtendSharedState {
    std::unique_ptr<graph::Graph> graph;
    std::unique_ptr<common::LimitCounter> counter = nullptr;

    RecursiveExtendSharedState(std::shared_ptr<FactorizedTable> fTable,
        std::unique_ptr<graph::Graph> graph, common::offset_t limitNumber)
        : graph{std::move(graph)}, factorizedTablePool{std::move(fTable)} {
        if (limitNumber != common::INVALID_LIMIT) {
            counter = std::make_unique<common::LimitCounter>(limitNumber);
        }
    }

    void setInputNodeMask(std::unique_ptr<common::NodeOffsetMaskMap> maskMap) {
        inputNodeMask = std::move(maskMap);
    }
    common::NodeOffsetMaskMap* getInputNodeMaskMap() const { return inputNodeMask.get(); }

    void setOutputNodeMask(std::unique_ptr<common::NodeOffsetMaskMap> maskMap) {
        outputNodeMask = std::move(maskMap);
    }
    common::NodeOffsetMaskMap* getOutputNodeMaskMap() const { return outputNodeMask.get(); }

    void setPathNodeMask(std::unique_ptr<common::NodeOffsetMaskMap> maskMap) {
        pathNodeMask = std::move(maskMap);
    }
    common::NodeOffsetMaskMap* getPathNodeMaskMap() const { return pathNodeMask.get(); }

    bool exceedLimit() const { return !(counter == nullptr) && counter->exceedLimit(); }

public:
    FactorizedTablePool factorizedTablePool;

private:
    std::unique_ptr<common::NodeOffsetMaskMap> inputNodeMask = nullptr;
    std::unique_ptr<common::NodeOffsetMaskMap> outputNodeMask = nullptr;
    std::unique_ptr<common::NodeOffsetMaskMap> pathNodeMask = nullptr;
};

} // namespace processor
} // namespace lbug
