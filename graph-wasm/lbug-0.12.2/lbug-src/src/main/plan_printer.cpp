#include "main/plan_printer.h"

#include <sstream>

#include "json.hpp"
#include "planner/operator/logical_plan.h"
#include "processor/physical_plan.h"

using namespace lbug::common;
using namespace lbug::planner;
using namespace lbug::processor;

namespace lbug {
namespace main {

OpProfileBox::OpProfileBox(std::string opName, const std::string& paramsName,
    std::vector<std::string> attributes)
    : opName{std::move(opName)}, attributes{std::move(attributes)} {
    std::stringstream paramsStream{paramsName};
    std::string paramStr = "";
    std::string subStr;
    bool subParam = false;
    // This loop splits the parameters by commas, while not
    // splitting up parameters that are operators.
    while (paramsStream.good()) {
        getline(paramsStream, subStr, ',');
        if (subStr.find('(') != std::string::npos && subStr.find(')') == std::string::npos) {
            paramStr = subStr;
            subParam = true;
            continue;
        }
        if (subParam && subStr.find(')') == std::string::npos) {
            paramStr += "," + subStr;
            continue;
        }
        if (subParam) {
            subStr = paramStr + ")";
            paramStr = "";
            subParam = false;
        }
        // This if statement discards any strings that are completely whitespace.
        if (subStr.find_first_not_of(" \t\n\v\f\r") != std::string::npos) {
            paramsNames.push_back(subStr);
        }
    }
}

uint32_t OpProfileBox::getAttributeMaxLen() const {
    auto maxAttributeLen = opName.length();
    for (auto& param : paramsNames) {
        maxAttributeLen = std::max(param.length(), maxAttributeLen);
    }
    for (auto& attribute : attributes) {
        maxAttributeLen = std::max(attribute.length(), maxAttributeLen);
    }
    return maxAttributeLen;
}

std::string OpProfileBox::getParamsName(uint32_t idx) const {
    KU_ASSERT(idx < paramsNames.size());
    return paramsNames[idx];
}

std::string OpProfileBox::getAttribute(uint32_t idx) const {
    KU_ASSERT(idx < attributes.size());
    return attributes[idx];
}

OpProfileTree::OpProfileTree(const PhysicalOperator* op, Profiler& profiler) {
    auto numRows = 0u, numCols = 0u;
    calculateNumRowsAndColsForOp(op, numRows, numCols);
    opProfileBoxes.resize(numRows);
    for_each(opProfileBoxes.begin(), opProfileBoxes.end(),
        [numCols](std::vector<std::unique_ptr<OpProfileBox>>& profileBoxes) {
            profileBoxes.resize(numCols);
        });
    auto maxFieldWidth = 0u;
    fillOpProfileBoxes(op, 0 /* rowIdx */, 0 /* colIdx */, maxFieldWidth, profiler);
    // The width of each profileBox = fieldWidth + leftIndentWidth + boxLeftFrameWidth +
    // rightIndentWidth + boxRightFrameWidth;
    this->opProfileBoxWidth = maxFieldWidth + 2 * (INDENT_WIDTH + BOX_FRAME_WIDTH);
}

OpProfileTree::OpProfileTree(const LogicalOperator* op) {
    auto numRows = 0u, numCols = 0u;
    calculateNumRowsAndColsForOp(op, numRows, numCols);
    opProfileBoxes.resize(numRows);
    for_each(opProfileBoxes.begin(), opProfileBoxes.end(),
        [numCols](std::vector<std::unique_ptr<OpProfileBox>>& profileBoxes) {
            profileBoxes.resize(numCols);
        });
    auto maxFieldWidth = 0u;
    fillOpProfileBoxes(op, 0 /* rowIdx */, 0 /* colIdx */, maxFieldWidth);
    // The width of each profileBox = fieldWidth + leftIndentWidth + boxLeftFrameWidth +
    // rightIndentWidth + boxRightFrameWidth;
    this->opProfileBoxWidth = std::max<uint32_t>(
        maxFieldWidth + 2 * (INDENT_WIDTH + BOX_FRAME_WIDTH), MIN_LOGICAL_BOX_WIDTH);
}

void printSpaceIfNecessary(uint32_t idx, std::ostringstream& oss) {
    if (idx > 0) {
        oss << " ";
    }
}

std::ostringstream OpProfileTree::printPlanToOstream() const {
    std::ostringstream oss;
    prettyPrintPlanTitle(oss, "Physical Plan");
    for (auto i = 0u; i < opProfileBoxes.size(); i++) {
        printOpProfileBoxUpperFrame(i, oss);
        printOpProfileBoxes(i, oss);
        printOpProfileBoxLowerFrame(i, oss);
    }
    return oss;
}

std::ostringstream OpProfileTree::printLogicalPlanToOstream() const {
    std::ostringstream oss;
    prettyPrintPlanTitle(oss, "Logical Plan");
    for (auto i = 0u; i < opProfileBoxes.size(); i++) {
        printOpProfileBoxUpperFrame(i, oss);
        printOpProfileBoxes(i, oss);
        printOpProfileBoxLowerFrame(i, oss);
    }
    return oss;
}

void OpProfileTree::calculateNumRowsAndColsForOp(const PhysicalOperator* op, uint32_t& numRows,
    uint32_t& numCols) {
    if (!op->getNumChildren()) {
        numRows = 1;
        numCols = 1;
        return;
    }

    for (auto i = 0u; i < op->getNumChildren(); i++) {
        auto numRowsInChild = 0u, numColsInChild = 0u;
        calculateNumRowsAndColsForOp(op->getChild(i), numRowsInChild, numColsInChild);
        numCols += numColsInChild;
        numRows = std::max(numRowsInChild, numRows);
    }
    numRows++;
}

void OpProfileTree::calculateNumRowsAndColsForOp(const LogicalOperator* op, uint32_t& numRows,
    uint32_t& numCols) {
    if (!op->getNumChildren()) {
        numRows = 1;
        numCols = 1;
        return;
    }

    for (auto i = 0u; i < op->getNumChildren(); i++) {
        auto numRowsInChild = 0u, numColsInChild = 0u;
        calculateNumRowsAndColsForOp(op->getChild(i).get(), numRowsInChild, numColsInChild);
        numCols += numColsInChild;
        numRows = std::max(numRowsInChild, numRows);
    }
    numRows++;
}

uint32_t OpProfileTree::fillOpProfileBoxes(const PhysicalOperator* op, uint32_t rowIdx,
    uint32_t colIdx, uint32_t& maxFieldWidth, Profiler& profiler) {
    auto opProfileBox = std::make_unique<OpProfileBox>(PlanPrinter::getOperatorName(op),
        PlanPrinter::getOperatorParams(op), op->getProfilerAttributes(profiler));
    maxFieldWidth = std::max(opProfileBox->getAttributeMaxLen(), maxFieldWidth);
    insertOpProfileBox(rowIdx, colIdx, std::move(opProfileBox));
    if (!op->getNumChildren()) {
        return 1;
    }

    uint32_t colOffset = 0;
    for (auto i = 0u; i < op->getNumChildren(); i++) {
        colOffset += fillOpProfileBoxes(op->getChild(i), rowIdx + 1, colIdx + colOffset,
            maxFieldWidth, profiler);
    }
    return colOffset;
}

uint32_t OpProfileTree::fillOpProfileBoxes(const LogicalOperator* op, uint32_t rowIdx,
    uint32_t colIdx, uint32_t& maxFieldWidth) {
    auto opProfileBox = std::make_unique<OpProfileBox>(PlanPrinter::getOperatorName(op),
        PlanPrinter::getOperatorParams(op),
        std::vector<std::string>{"Cardinality: " + std::to_string(op->getCardinality())});
    maxFieldWidth = std::max(opProfileBox->getAttributeMaxLen(), maxFieldWidth);
    insertOpProfileBox(rowIdx, colIdx, std::move(opProfileBox));
    if (!op->getNumChildren()) {
        return 1;
    }

    uint32_t colOffset = 0;
    for (auto i = 0u; i < op->getNumChildren(); i++) {
        colOffset += fillOpProfileBoxes(op->getChild(i).get(), rowIdx + 1, colIdx + colOffset,
            maxFieldWidth);
    }
    return colOffset;
}

void OpProfileTree::printOpProfileBoxUpperFrame(uint32_t rowIdx, std::ostringstream& oss) const {
    for (auto i = 0u; i < opProfileBoxes[rowIdx].size(); i++) {
        printSpaceIfNecessary(i, oss);
        if (getOpProfileBox(rowIdx, i)) {
            // If the current box has a parent, we need to put a "┴" in the  box upper frame to
            // connect to its parent.
            if (hasOpProfileBoxOnUpperLeft(rowIdx, i)) {
                auto leftFrameLength = (opProfileBoxWidth - 2 * BOX_FRAME_WIDTH - 1) / 2;
                oss << "┌" << genHorizLine(leftFrameLength) << "┴"
                    << genHorizLine(opProfileBoxWidth - 2 * BOX_FRAME_WIDTH - 1 - leftFrameLength)
                    << "┐";
            } else {
                oss << "┌" << genHorizLine(opProfileBoxWidth - 2 * BOX_FRAME_WIDTH) << "┐";
            }
        } else {
            oss << std::string(opProfileBoxWidth, ' ');
        }
    }
    oss << '\n';
}

static std::string dashedLineAccountingForIndex(uint32_t width, uint32_t indent) {
    return std::string(width - (1 + indent) * 2, '-');
}

void OpProfileTree::printOpProfileBoxes(uint32_t rowIdx, std::ostringstream& oss) const {
    auto height = calculateRowHeight(rowIdx);
    auto halfWayPoint = height / 2;
    uint32_t offset = 0;
    for (auto i = 0u; i < height; i++) {
        for (auto j = 0u; j < opProfileBoxes[rowIdx].size(); j++) {
            auto opProfileBox = getOpProfileBox(rowIdx, j);
            if (opProfileBox &&
                i < 2 * (opProfileBox->getNumAttributes() + 1) + opProfileBox->getNumParams()) {
                printSpaceIfNecessary(j, oss);
                std::string textToPrint;
                unsigned int numParams = opProfileBox->getNumParams();
                if (i == 0) {
                    textToPrint = opProfileBox->getOpName();
                } else if (i == 1) { // NOLINT(bugprone-branch-clone): Merging these branches is a
                                     // logical error, and this conditional chain is pleasant.
                    textToPrint = dashedLineAccountingForIndex(opProfileBoxWidth, INDENT_WIDTH);
                } else if (i <= numParams + 1) {
                    textToPrint = opProfileBox->getParamsName(i - 2);
                } else if ((i - numParams - 1) % 2) {
                    textToPrint = dashedLineAccountingForIndex(opProfileBoxWidth, INDENT_WIDTH);
                } else {
                    textToPrint = opProfileBox->getAttribute((i - numParams - 1) / 2 - 1);
                }
                auto numLeftSpaces =
                    (opProfileBoxWidth - (1 + INDENT_WIDTH) * 2 - textToPrint.length()) / 2;
                auto numRightSpace = opProfileBoxWidth - (1 + INDENT_WIDTH) * 2 -
                                     textToPrint.length() - numLeftSpaces;
                oss << "│" << std::string(INDENT_WIDTH + numLeftSpaces, ' ') << textToPrint
                    << std::string(INDENT_WIDTH + numRightSpace, ' ') << "│";
            } else if (opProfileBox) {
                // If we have printed out all the attributes in the current opProfileBox, print
                // empty spaces as placeholders.
                printSpaceIfNecessary(j, oss);
                oss << "│" << std::string(opProfileBoxWidth - 2, ' ') << "│";
            } else {
                if (hasOpProfileBox(rowIdx + 1, j) && i >= halfWayPoint) {
                    auto leftHorizLineSize = (opProfileBoxWidth - 1) / 2;
                    if (i == halfWayPoint) {
                        oss << genHorizLine(leftHorizLineSize + 1);
                        if (hasOpProfileBox(rowIdx + 1, j + 4) && !hasOpProfileBox(rowIdx, j + 1)) {
                            oss << "┬" << genHorizLine(opProfileBoxWidth - 1 - leftHorizLineSize);
                        } else {
                            if ((hasOpProfileBox(rowIdx + 1, j + 1) &&
                                    !hasOpProfileBox(rowIdx, j) &&
                                    !hasOpProfileBox(rowIdx, j + 1)) ||
                                (hasOpProfileBox(rowIdx + 1, j + 2) &&
                                    !hasOpProfileBox(rowIdx, j + 1))) {
                                oss << "┬" << genHorizLine(opProfileBoxWidth / 2);
                            } else {
                                oss << "┐"
                                    << std::string(opProfileBoxWidth - 1 - leftHorizLineSize, ' ');
                            }
                        }
                    } else if (i > halfWayPoint) {
                        printSpaceIfNecessary(j, oss);
                        oss << std::string(leftHorizLineSize, ' ') << "│"
                            << std::string(opProfileBoxWidth - 1 - leftHorizLineSize, ' ');
                    }
                } else if (((hasOpProfileBox(rowIdx + 1, j + 1) &&
                                !hasOpProfileBox(rowIdx, j + 1)) ||
                               (hasOpProfileBox(rowIdx + 1, j + 3) &&
                                   !hasOpProfileBox(rowIdx, j + 3) &&
                                   !hasOpProfileBox(rowIdx, j + 1) &&
                                   !hasOpProfileBox(rowIdx, j + 2)) ||
                               (hasOpProfileBox(rowIdx + 1, j - 2) &&
                                   !hasOpProfileBox(rowIdx, j - 2) &&
                                   hasOpProfileBox(rowIdx + 1, j + 3))) &&
                           i == halfWayPoint && !hasOpProfileBox(rowIdx, j + 2)) {
                    oss << genHorizLine(opProfileBoxWidth + 1);
                    offset = offset == 0 ? 1 : 0;
                } else {
                    printSpaceIfNecessary(j, oss);
                    oss << std::string(opProfileBoxWidth, ' ');
                }
            }
        }
        oss << '\n';
    }
}

void OpProfileTree::printOpProfileBoxLowerFrame(uint32_t rowIdx, std::ostringstream& oss) const {
    for (auto i = 0u; i < opProfileBoxes[rowIdx].size(); i++) {
        if (getOpProfileBox(rowIdx, i)) {
            printSpaceIfNecessary(i, oss);
            // If the current opProfileBox has a child, we need to print out a connector to it.
            if (hasOpProfileBox(rowIdx + 1, i)) {
                auto leftFrameLength = (opProfileBoxWidth - 2 * BOX_FRAME_WIDTH - 1) / 2;
                oss << "└" << genHorizLine(leftFrameLength) << "┬"
                    << genHorizLine(opProfileBoxWidth - 2 * BOX_FRAME_WIDTH - 1 - leftFrameLength)
                    << "┘";
            } else {
                oss << "└" << genHorizLine(opProfileBoxWidth - 2) << "┘";
            }
        } else if (hasOpProfileBox(rowIdx + 1, i)) {
            // If there is a opProfileBox at the bottom, we need to print out a vertical line to
            // connect it.
            auto leftFrameLength = (opProfileBoxWidth - 1) / 2;
            printSpaceIfNecessary(i, oss);
            oss << std::string(leftFrameLength, ' ') << "│"
                << std::string(opProfileBoxWidth - leftFrameLength - 1, ' ');
        } else {
            printSpaceIfNecessary(i, oss);
            oss << std::string(opProfileBoxWidth, ' ');
        }
    }
    oss << '\n';
}

void OpProfileTree::prettyPrintPlanTitle(std::ostringstream& oss, std::string title) const {
    const std::string plan = title;
    oss << "┌" << genHorizLine(opProfileBoxWidth - 2) << "┐" << '\n';
    oss << "│┌" << genHorizLine(opProfileBoxWidth - 4) << "┐│" << '\n';
    auto numLeftSpaces = (opProfileBoxWidth - plan.length() - 2 * (2 + INDENT_WIDTH)) / 2;
    auto numRightSpaces =
        opProfileBoxWidth - plan.length() - 2 * (2 + INDENT_WIDTH) - numLeftSpaces;
    oss << "││" << std::string(INDENT_WIDTH + numLeftSpaces, ' ') << plan
        << std::string(INDENT_WIDTH + numRightSpaces, ' ') << "││" << '\n';
    oss << "│└" << genHorizLine(opProfileBoxWidth - 4) << "┘│" << '\n';
    oss << "└" << genHorizLine(opProfileBoxWidth - 2) << "┘" << '\n';
}

std::string OpProfileTree::genHorizLine(uint32_t len) {
    std::ostringstream tableFrame;
    for (auto i = 0u; i < len; i++) {
        tableFrame << "─";
    }
    return tableFrame.str();
}

void OpProfileTree::insertOpProfileBox(uint32_t rowIdx, uint32_t colIdx,
    std::unique_ptr<OpProfileBox> opProfileBox) {
    validateRowIdxAndColIdx(rowIdx, colIdx);
    opProfileBoxes[rowIdx][colIdx] = std::move(opProfileBox);
}

OpProfileBox* OpProfileTree::getOpProfileBox(uint32_t rowIdx, uint32_t colIdx) const {
    validateRowIdxAndColIdx(rowIdx, colIdx);
    return opProfileBoxes[rowIdx][colIdx].get();
}

bool OpProfileTree::hasOpProfileBoxOnUpperLeft(uint32_t rowIdx, uint32_t colIdx) const {
    validateRowIdxAndColIdx(rowIdx, colIdx);
    for (auto i = 0u; i <= colIdx; i++) {
        if (hasOpProfileBox(rowIdx - 1, i)) {
            return true;
        }
    }
    return false;
}

uint32_t OpProfileTree::calculateRowHeight(uint32_t rowIdx) const {
    validateRowIdxAndColIdx(rowIdx, 0 /* colIdx */);
    auto height = 0u;
    for (auto i = 0u; i < opProfileBoxes[rowIdx].size(); i++) {
        auto opProfileBox = getOpProfileBox(rowIdx, i);
        if (opProfileBox) {
            height = std::max(height,
                2 * opProfileBox->getNumAttributes() + opProfileBox->getNumParams());
        }
    }
    return height + 2;
}

nlohmann::json PlanPrinter::printPlanToJson(const PhysicalPlan* physicalPlan, Profiler* profiler) {
    return toJson(physicalPlan->lastOperator.get(), *profiler);
}

std::ostringstream PlanPrinter::printPlanToOstream(const PhysicalPlan* physicalPlan,
    Profiler* profiler) {
    return OpProfileTree(physicalPlan->lastOperator.get(), *profiler).printPlanToOstream();
}

nlohmann::json PlanPrinter::printPlanToJson(const LogicalPlan* logicalPlan) {
    return toJson(logicalPlan->getLastOperator().get());
}

std::ostringstream PlanPrinter::printPlanToOstream(const LogicalPlan* logicalPlan) {
    return OpProfileTree(logicalPlan->getLastOperator().get()).printLogicalPlanToOstream();
}

std::string PlanPrinter::getOperatorName(const PhysicalOperator* physicalOperator) {
    return PhysicalOperatorUtils::operatorToString(physicalOperator);
}

std::string PlanPrinter::getOperatorParams(const PhysicalOperator* physicalOperator) {
    return physicalOperator->getPrintInfo()->toString();
}

std::string PlanPrinter::getOperatorName(const LogicalOperator* logicalOperator) {
    return LogicalOperatorUtils::logicalOperatorTypeToString(logicalOperator->getOperatorType());
}

std::string PlanPrinter::getOperatorParams(const LogicalOperator* logicalOperator) {
    return logicalOperator->getPrintInfo()->toString();
}

nlohmann::json PlanPrinter::toJson(const PhysicalOperator* physicalOperator, Profiler& profiler_) {
    auto json = nlohmann::json();
    json["Name"] = getOperatorName(physicalOperator);
    if (profiler_.enabled) {
        for (auto& [key, val] : physicalOperator->getProfilerKeyValAttributes(profiler_)) {
            json[key] = val;
        }
    }
    for (auto i = 0u; i < physicalOperator->getNumChildren(); ++i) {
        json["Child" + std::to_string(i)] = toJson(physicalOperator->getChild(i), profiler_);
    }
    return json;
}

nlohmann::json PlanPrinter::toJson(const LogicalOperator* logicalOperator) {
    auto json = nlohmann::json();
    json["Name"] = getOperatorName(logicalOperator);
    for (auto i = 0u; i < logicalOperator->getNumChildren(); ++i) {
        json["Child" + std::to_string(i)] = toJson(logicalOperator->getChild(i).get());
    }
    return json;
}

} // namespace main
} // namespace lbug
