#pragma once

#include "processor/operator/scan/scan_rel_table.h"

namespace lbug {
namespace processor {

struct DirectionInfo {
    bool extendFromSource;
    DataPos directionPos;

    DirectionInfo() : extendFromSource{false}, directionPos{DataPos::getInvalidPos()} {}
    EXPLICIT_COPY_DEFAULT_MOVE(DirectionInfo);

    bool needFlip(common::RelDataDirection relDataDirection) const;

private:
    DirectionInfo(const DirectionInfo& other)
        : extendFromSource{other.extendFromSource}, directionPos{other.directionPos} {}
};

class RelTableCollectionScanner {
    friend class ScanMultiRelTable;

public:
    explicit RelTableCollectionScanner(std::vector<ScanRelTableInfo> relInfos)
        : relInfos{std::move(relInfos)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(RelTableCollectionScanner);

    bool empty() const { return relInfos.empty(); }

    void resetState() {
        currentTableIdx = 0;
        nextTableIdx = 0;
    }

    void addRelInfos(std::vector<ScanRelTableInfo> relInfos_) {
        for (auto& relInfo : relInfos_) {
            relInfos.push_back(std::move(relInfo));
        }
    }

    bool scan(main::ClientContext* context, storage::RelTableScanState& scanState,
        const std::vector<common::ValueVector*>& outVectors);

private:
    RelTableCollectionScanner(const RelTableCollectionScanner& other)
        : relInfos{copyVector(other.relInfos)} {}

private:
    std::vector<ScanRelTableInfo> relInfos;
    std::vector<bool> directionValues;
    common::ValueVector* directionVector = nullptr;
    common::idx_t currentTableIdx = common::INVALID_IDX;
    uint32_t nextTableIdx = 0;
};

class ScanMultiRelTable final : public ScanTable {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::SCAN_REL_TABLE;

public:
    ScanMultiRelTable(ScanOpInfo info, DirectionInfo directionInfo,
        common::table_id_map_t<RelTableCollectionScanner> scanners,
        std::unique_ptr<PhysicalOperator> child, physical_op_id id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : ScanTable{type_, std::move(info), std::move(child), id, std::move(printInfo)},
          directionInfo{std::move(directionInfo)}, scanState{nullptr}, boundNodeIDVector{nullptr},
          scanners{std::move(scanners)}, currentScanner{nullptr} {}

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    bool getNextTuplesInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return make_unique<ScanMultiRelTable>(opInfo.copy(), directionInfo.copy(),
            copyUnorderedMap(scanners), children[0]->copy(), id, printInfo->copy());
    }

private:
    void resetState();
    void initCurrentScanner(const common::nodeID_t& nodeID);

private:
    DirectionInfo directionInfo;
    std::unique_ptr<storage::RelTableScanState> scanState;

    common::ValueVector* boundNodeIDVector;
    common::table_id_map_t<RelTableCollectionScanner> scanners;
    RelTableCollectionScanner* currentScanner;
};

} // namespace processor
} // namespace lbug
