#pragma once

#include <mutex>

#include "common/enums/extend_direction.h"
#include "common/mask.h"
#include "processor/operator/physical_operator.h"

namespace lbug {
namespace processor {

class BaseSemiMasker;

struct SemiMaskerLocalState {
    common::table_id_map_t<std::unique_ptr<common::SemiMask>> localMasksPerTable;
    common::SemiMask* singleTableRef = nullptr;

    void maskSingleTable(common::offset_t offset) const { singleTableRef->mask(offset); }
    void maskMultiTable(common::nodeID_t nodeID) const {
        KU_ASSERT(localMasksPerTable.contains(nodeID.tableID));
        localMasksPerTable.at(nodeID.tableID)->mask(nodeID.offset);
    }
};

class SemiMaskerSharedState {
public:
    explicit SemiMaskerSharedState(
        common::table_id_map_t<std::vector<common::SemiMask*>> masksPerTable)
        : masksPerTable{std::move(masksPerTable)} {}

    SemiMaskerLocalState* appendLocalState();

    void mergeToGlobal();

private:
    common::table_id_map_t<std::vector<common::SemiMask*>> masksPerTable;
    std::vector<std::shared_ptr<SemiMaskerLocalState>> localInfos;
    std::mutex mtx;
};

struct SemiMaskerPrintInfo final : OPPrintInfo {
    std::vector<std::string> operatorNames;

    explicit SemiMaskerPrintInfo(std::vector<std::string> operatorNames)
        : operatorNames{std::move(operatorNames)} {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<SemiMaskerPrintInfo>(new SemiMaskerPrintInfo(*this));
    }

private:
    SemiMaskerPrintInfo(const SemiMaskerPrintInfo& other)
        : OPPrintInfo{other}, operatorNames{other.operatorNames} {}
};

class BaseSemiMasker : public PhysicalOperator {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::SEMI_MASKER;

protected:
    BaseSemiMasker(DataPos keyPos, std::shared_ptr<SemiMaskerSharedState> sharedState,
        std::unique_ptr<PhysicalOperator> child, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{type_, std::move(child), id, std::move(printInfo)}, keyPos{keyPos},
          keyVector{nullptr}, sharedState{std::move(sharedState)}, localState{nullptr} {}

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    void finalizeInternal(ExecutionContext* context) final;

protected:
    DataPos keyPos;
    common::ValueVector* keyVector;
    std::shared_ptr<SemiMaskerSharedState> sharedState;
    SemiMaskerLocalState* localState;
};

class SingleTableSemiMasker final : public BaseSemiMasker {
public:
    SingleTableSemiMasker(DataPos keyPos, std::shared_ptr<SemiMaskerSharedState> sharedState,
        std::unique_ptr<PhysicalOperator> child, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : BaseSemiMasker{keyPos, std::move(sharedState), std::move(child), id,
              std::move(printInfo)} {}

    bool getNextTuplesInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<SingleTableSemiMasker>(keyPos, sharedState, children[0]->copy(), id,
            printInfo->copy());
    }
};

class MultiTableSemiMasker final : public BaseSemiMasker {
public:
    MultiTableSemiMasker(DataPos keyPos, std::shared_ptr<SemiMaskerSharedState> sharedState,
        std::unique_ptr<PhysicalOperator> child, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : BaseSemiMasker{keyPos, std::move(sharedState), std::move(child), id,
              std::move(printInfo)} {}

    bool getNextTuplesInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<MultiTableSemiMasker>(keyPos, sharedState, children[0]->copy(), id,
            printInfo->copy());
    }
};

class NodeIDsSemiMask : public BaseSemiMasker {
protected:
    NodeIDsSemiMask(DataPos keyPos, DataPos srcNodeIDPos, DataPos dstNodeIDPos,
        std::shared_ptr<SemiMaskerSharedState> sharedState, std::unique_ptr<PhysicalOperator> child,
        uint32_t id, std::unique_ptr<OPPrintInfo> printInfo)
        : BaseSemiMasker{keyPos, std::move(sharedState), std::move(child), id,
              std::move(printInfo)},
          srcNodeIDPos{srcNodeIDPos}, dstNodeIDPos{dstNodeIDPos} {}

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) final;

protected:
    DataPos srcNodeIDPos;
    DataPos dstNodeIDPos;

    common::ValueVector* srcNodeIDVector = nullptr;
    common::ValueVector* dstNodeIDVector = nullptr;
};

class NodeIDsSingleTableSemiMasker final : public NodeIDsSemiMask {
public:
    NodeIDsSingleTableSemiMasker(DataPos keyPos, DataPos srcNodeIDPos, DataPos dstNodeIDPos,
        std::shared_ptr<SemiMaskerSharedState> sharedState, std::unique_ptr<PhysicalOperator> child,
        uint32_t id, std::unique_ptr<OPPrintInfo> printInfo)
        : NodeIDsSemiMask{keyPos, srcNodeIDPos, dstNodeIDPos, std::move(sharedState),
              std::move(child), id, std::move(printInfo)} {}

    bool getNextTuplesInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<NodeIDsSingleTableSemiMasker>(keyPos, srcNodeIDPos, dstNodeIDPos,
            sharedState, children[0]->copy(), id, printInfo->copy());
    }
};

class NodeIDsMultipleTableSemiMasker final : public NodeIDsSemiMask {
public:
    NodeIDsMultipleTableSemiMasker(DataPos keyPos, DataPos srcNodeIDPos, DataPos dstNodeIDPos,
        std::shared_ptr<SemiMaskerSharedState> sharedState, std::unique_ptr<PhysicalOperator> child,
        uint32_t id, std::unique_ptr<OPPrintInfo> printInfo)
        : NodeIDsSemiMask{keyPos, srcNodeIDPos, dstNodeIDPos, std::move(sharedState),
              std::move(child), id, std::move(printInfo)} {}

    bool getNextTuplesInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<NodeIDsMultipleTableSemiMasker>(keyPos, srcNodeIDPos, dstNodeIDPos,
            sharedState, children[0]->copy(), id, printInfo->copy());
    }
};

class PathSemiMasker : public BaseSemiMasker {
protected:
    PathSemiMasker(DataPos keyPos, std::shared_ptr<SemiMaskerSharedState> sharedState,
        std::unique_ptr<PhysicalOperator> child, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo, common::ExtendDirection direction)
        : BaseSemiMasker{keyPos, std::move(sharedState), std::move(child), id,
              std::move(printInfo)},
          direction{direction} {}

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) final;

protected:
    common::ValueVector* pathRelsVector = nullptr;
    common::ValueVector* pathRelsSrcIDDataVector = nullptr;
    common::ValueVector* pathRelsDstIDDataVector = nullptr;
    common::ExtendDirection direction;
};

class PathSingleTableSemiMasker final : public PathSemiMasker {
public:
    PathSingleTableSemiMasker(DataPos keyPos, std::shared_ptr<SemiMaskerSharedState> sharedState,
        std::unique_ptr<PhysicalOperator> child, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo, common::ExtendDirection direction)
        : PathSemiMasker{keyPos, std::move(sharedState), std::move(child), id, std::move(printInfo),
              direction} {}

    bool getNextTuplesInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<PathSingleTableSemiMasker>(keyPos, sharedState, children[0]->copy(),
            id, printInfo->copy(), direction);
    }
};

class PathMultipleTableSemiMasker final : public PathSemiMasker {
public:
    PathMultipleTableSemiMasker(DataPos keyPos, std::shared_ptr<SemiMaskerSharedState> sharedState,
        std::unique_ptr<PhysicalOperator> child, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo, common::ExtendDirection direction)
        : PathSemiMasker{keyPos, std::move(sharedState), std::move(child), id, std::move(printInfo),
              direction} {}

    bool getNextTuplesInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<PathMultipleTableSemiMasker>(keyPos, sharedState,
            children[0]->copy(), id, printInfo->copy(), direction);
    }
};

} // namespace processor
} // namespace lbug
