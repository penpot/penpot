#include "function/gds/rec_joins.h"

namespace lbug {
namespace function {

RJBindData::RJBindData(const RJBindData& other) {
    graphEntry = other.graphEntry.copy();
    nodeInput = other.nodeInput;
    nodeOutput = other.nodeOutput;
    lowerBound = other.lowerBound;
    upperBound = other.upperBound;
    semantic = other.semantic;
    extendDirection = other.extendDirection;
    flipPath = other.flipPath;
    writePath = other.writePath;
    directionExpr = other.directionExpr;
    lengthExpr = other.lengthExpr;
    pathNodeIDsExpr = other.pathNodeIDsExpr;
    pathEdgeIDsExpr = other.pathEdgeIDsExpr;
    weightPropertyExpr = other.weightPropertyExpr;
    weightOutputExpr = other.weightOutputExpr;
}

PathsOutputWriterInfo RJBindData::getPathWriterInfo() const {
    auto info = PathsOutputWriterInfo();
    info.semantic = semantic;
    info.lowerBound = lowerBound;
    info.flipPath = flipPath;
    info.writeEdgeDirection = writePath && extendDirection == common::ExtendDirection::BOTH;
    info.writePath = writePath;
    return info;
}

} // namespace function
} // namespace lbug
