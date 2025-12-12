#pragma once

#include <sstream>
#include <string>

#include "common/assert.h"
#include "common/profiler.h"
#include "json_fwd.hpp"
#include "lbug_fwd.h"

namespace lbug {
namespace main {

class OpProfileBox {
public:
    OpProfileBox(std::string opName, const std::string& paramsName,
        std::vector<std::string> attributes);

    inline std::string getOpName() const { return opName; }

    inline uint32_t getNumParams() const { return paramsNames.size(); }

    std::string getParamsName(uint32_t idx) const;

    std::string getAttribute(uint32_t idx) const;

    inline uint32_t getNumAttributes() const { return attributes.size(); }

    uint32_t getAttributeMaxLen() const;

private:
    std::string opName;
    std::vector<std::string> paramsNames;
    std::vector<std::string> attributes;
};

class OpProfileTree {
public:
    OpProfileTree(const processor::PhysicalOperator* op, common::Profiler& profiler);

    explicit OpProfileTree(const planner::LogicalOperator* op);

    std::ostringstream printPlanToOstream() const;

    std::ostringstream printLogicalPlanToOstream() const;

private:
    static void calculateNumRowsAndColsForOp(const processor::PhysicalOperator* op,
        uint32_t& numRows, uint32_t& numCols);

    static void calculateNumRowsAndColsForOp(const planner::LogicalOperator* op, uint32_t& numRows,
        uint32_t& numCols);

    uint32_t fillOpProfileBoxes(const processor::PhysicalOperator* op, uint32_t rowIdx,
        uint32_t colIdx, uint32_t& maxFieldWidth, common::Profiler& profiler);

    uint32_t fillOpProfileBoxes(const planner::LogicalOperator* op, uint32_t rowIdx,
        uint32_t colIdx, uint32_t& maxFieldWidth);

    void printOpProfileBoxUpperFrame(uint32_t rowIdx, std::ostringstream& oss) const;

    void printOpProfileBoxes(uint32_t rowIdx, std::ostringstream& oss) const;

    void printOpProfileBoxLowerFrame(uint32_t rowIdx, std::ostringstream& oss) const;

    void prettyPrintPlanTitle(std::ostringstream& oss, std::string title) const;

    static std::string genHorizLine(uint32_t len);

    inline void validateRowIdxAndColIdx(uint32_t rowIdx, uint32_t colIdx) const {
        KU_ASSERT(rowIdx < opProfileBoxes.size() && colIdx < opProfileBoxes[rowIdx].size());
        (void)rowIdx;
        (void)colIdx;
    }

    void insertOpProfileBox(uint32_t rowIdx, uint32_t colIdx,
        std::unique_ptr<OpProfileBox> opProfileBox);

    OpProfileBox* getOpProfileBox(uint32_t rowIdx, uint32_t colIdx) const;

    bool hasOpProfileBox(uint32_t rowIdx, uint32_t colIdx) const {
        return rowIdx < opProfileBoxes.size() && colIdx < opProfileBoxes[rowIdx].size() &&
               getOpProfileBox(rowIdx, colIdx);
    }

    //! Returns true if there is a valid OpProfileBox on the upper left side of the OpProfileBox
    //! located at (rowIdx, colIdx).
    bool hasOpProfileBoxOnUpperLeft(uint32_t rowIdx, uint32_t colIdx) const;

    uint32_t calculateRowHeight(uint32_t rowIdx) const;

private:
    std::vector<std::vector<std::unique_ptr<OpProfileBox>>> opProfileBoxes;
    uint32_t opProfileBoxWidth;
    static constexpr uint32_t INDENT_WIDTH = 3u;
    static constexpr uint32_t BOX_FRAME_WIDTH = 1u;
    static constexpr uint32_t MIN_LOGICAL_BOX_WIDTH = 22u;
};

struct PlanPrinter {
    static nlohmann::json printPlanToJson(const processor::PhysicalPlan* physicalPlan,
        common::Profiler* profiler);
    static std::ostringstream printPlanToOstream(const processor::PhysicalPlan* physicalPlan,
        common::Profiler* profiler);
    static std::string getOperatorName(const processor::PhysicalOperator* physicalOperator);
    static std::string getOperatorParams(const processor::PhysicalOperator* physicalOperator);

    static nlohmann::json printPlanToJson(const planner::LogicalPlan* logicalPlan);
    static std::ostringstream printPlanToOstream(const planner::LogicalPlan* logicalPlan);
    static std::string getOperatorName(const planner::LogicalOperator* logicalOperator);
    static std::string getOperatorParams(const planner::LogicalOperator* logicalOperator);

private:
    static nlohmann::json toJson(const processor::PhysicalOperator* physicalOperator,
        common::Profiler& profiler_);
    static nlohmann::json toJson(const planner::LogicalOperator* logicalOperator);
};

} // namespace main
} // namespace lbug
