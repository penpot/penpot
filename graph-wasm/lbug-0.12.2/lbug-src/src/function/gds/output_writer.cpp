#include "common/exception/interrupt.h"
#include "function/gds/rj_output_writer.h"
#include "main/client_context.h"
#include <function/gds/gds_frontier.h>

using namespace lbug::common;
using namespace lbug::processor;

namespace lbug {
namespace function {

RJOutputWriter::RJOutputWriter(main::ClientContext* context, NodeOffsetMaskMap* outputNodeMask,
    nodeID_t sourceNodeID)
    : context{context}, outputNodeMask{outputNodeMask}, sourceNodeID_{sourceNodeID} {
    srcNodeIDVector = createVector(LogicalType::INTERNAL_ID());
    dstNodeIDVector = createVector(LogicalType::INTERNAL_ID());
    srcNodeIDVector->setValue<nodeID_t>(0, sourceNodeID);
}

void RJOutputWriter::pinOutputNodeMask(table_id_t tableID) {
    if (outputNodeMask != nullptr) {
        outputNodeMask->pin(tableID);
    }
}

bool RJOutputWriter::inOutputNodeMask(common::offset_t offset) {
    if (outputNodeMask == nullptr) { // No mask
        return true;
    }
    auto mask = outputNodeMask->getPinnedMask();
    if (!mask->isEnabled()) { // No mask
        return true;
    }
    return mask->isMasked(offset);
}

std::unique_ptr<ValueVector> RJOutputWriter::createVector(const LogicalType& type) {
    auto vector = std::make_unique<ValueVector>(type.copy(), storage::MemoryManager::Get(*context));
    vector->state = DataChunkState::getSingleValueDataChunkState();
    vectors.push_back(vector.get());
    return vector;
}

PathsOutputWriter::PathsOutputWriter(main::ClientContext* context,
    NodeOffsetMaskMap* outputNodeMask, nodeID_t sourceNodeID, PathsOutputWriterInfo info,
    BaseBFSGraph& bfsGraph)
    : RJOutputWriter{context, outputNodeMask, sourceNodeID}, info{info}, bfsGraph{bfsGraph} {
    lengthVector = createVector(LogicalType::UINT16());
    if (info.writeEdgeDirection) {
        directionVector = createVector(LogicalType::LIST(LogicalType::BOOL()));
    }
    if (info.writePath) {
        pathNodeIDsVector = createVector(LogicalType::LIST(LogicalType::INTERNAL_ID()));
        pathEdgeIDsVector = createVector(LogicalType::LIST(LogicalType::INTERNAL_ID()));
    }
}

static void addListEntry(ValueVector* vector, uint64_t length) {
    vector->resetAuxiliaryBuffer();
    auto entry = ListVector::addList(vector, length);
    KU_ASSERT(entry.offset == 0);
    vector->setValue(0, entry);
}

static ParentList* getTop(const std::vector<ParentList*>& path) {
    return path[path.size() - 1];
}

void PathsOutputWriter::write(FactorizedTable& fTable, table_id_t tableID, LimitCounter* counter) {
    auto& sparseGraph = bfsGraph.cast<SparseBFSGraph>();
    for (auto& [offset, _] : sparseGraph.getCurrentData()) {
        write(fTable, {offset, tableID}, counter);
    }
    if (info.lowerBound == 0 && sourceNodeID_.tableID == tableID) {
        write(fTable, sourceNodeID_, counter);
    }
}

void PathsOutputWriter::write(FactorizedTable& fTable, nodeID_t dstNodeID, LimitCounter* counter) {
    if (!inOutputNodeMask(dstNodeID.offset)) {
        return;
    }
    dstNodeIDVector->setValue<nodeID_t>(0, dstNodeID);
    writeInternal(fTable, dstNodeID, counter);
}

void PathsOutputWriter::dfsFast(ParentList* firstParent, FactorizedTable& fTable,
    LimitCounter* counter) {
    std::vector<ParentList*> curPath;
    curPath.push_back(firstParent);
    auto backtracking = false;
    while (!curPath.empty()) {
        if (context->interrupted()) {
            throw InterruptException{};
        }
        auto top = curPath[curPath.size() - 1];
        auto topNodeID = top->getNodeID();
        if (top->getIter() == 1) {
            writePath(curPath);
            fTable.append(vectors);
            if (updateCounterAndTerminate(counter)) {
                return;
            }
            backtracking = true;
        }
        if (backtracking) {
            auto next = getTop(curPath)->getNextPtr();
            if (isNextViable(next, curPath)) {
                curPath[curPath.size() - 1] = next;
                backtracking = false;
            } else {
                curPath.pop_back();
            }
        } else {
            auto parent = bfsGraph.getParentListHead(topNodeID);
            while (parent->getIter() != top->getIter() - 1) {
                parent = parent->getNextPtr();
            }
            curPath.push_back(parent);
            backtracking = false;
        }
    }
}

void PathsOutputWriter::dfsSlow(ParentList* firstParent, FactorizedTable& fTable,
    LimitCounter* counter) {
    std::vector<ParentList*> curPath;
    curPath.push_back(firstParent);
    auto backtracking = false;
    while (!curPath.empty()) {
        if (context->interrupted()) {
            throw InterruptException{};
        }
        if (getTop(curPath)->getIter() == 1) {
            writePath(curPath);
            fTable.append(vectors);
            if (updateCounterAndTerminate(counter)) {
                return;
            }
            backtracking = true;
        }
        if (backtracking) {
            auto next = getTop(curPath)->getNextPtr();
            while (true) {
                if (!isNextViable(next, curPath)) {
                    curPath.pop_back();
                    break;
                }
                // Further check next against path node mask (predicate).
                if (!checkPathNodeMask(next) || !checkReplaceTopSemantic(curPath, next)) {
                    next = next->getNextPtr();
                    continue;
                }
                // Next is a valid path element. Push into stack and switch to forward track.
                curPath[curPath.size() - 1] = next;
                backtracking = false;
                break;
            }
        } else {
            auto top = getTop(curPath);
            auto parent = bfsGraph.getParentListHead(top->getNodeID());
            while (true) {
                if (parent == nullptr) {
                    // No more forward tracking candidates. Switch to backward tracking.
                    backtracking = true;
                    break;
                }
                if (parent->getIter() == top->getIter() - 1 && checkPathNodeMask(parent) &&
                    checkAppendSemantic(curPath, parent)) {
                    // A forward tracking candidate should decrease the iteration by one and also
                    // pass node predicate checking.
                    curPath.push_back(parent);
                    backtracking = false;
                    break;
                }
                parent = parent->getNextPtr();
            }
        }
    }
}

bool PathsOutputWriter::updateCounterAndTerminate(LimitCounter* counter) {
    if (counter != nullptr) {
        counter->increase(1);
        return counter->exceedLimit();
    }
    return false;
}

ParentList* PathsOutputWriter::findFirstParent(offset_t dstOffset) const {
    auto result = bfsGraph.getParentListHead(dstOffset);
    if (!info.hasNodeMask() && info.semantic == PathSemantic::WALK) {
        // Fast path when there is no node predicate or semantic check
        return result;
    }
    while (result) {
        // A valid parent should
        // (1) satisfies path node semi mask (i.e. path node predicate)
        // (2) since first parent has the largest iteration number which decides path length, we
        //     also need to check if path length is greater than lower bound.
        if (checkPathNodeMask(result) && result->getIter() >= info.lowerBound) {
            break;
        }
        result = result->getNextPtr();
    }
    return result;
}

// This code checks if we should switch from backtracking to forward-tracking, i.e.,
// moving forward in the DFS logic to find paths. We switch from backtracking if:
bool PathsOutputWriter::isNextViable(ParentList* next, const std::vector<ParentList*>& path) const {
    if (next == nullptr) {
        return false;
    }
    auto nextIter = next->getIter();
    // (1) if this is the first element in the stack (curPath.size() == 1), i.e., we
    // are enumerating the parents of the destination, then we should switch to
    // forward-tracking if the next parent has visited the destination at a length
    // that's greater than or equal to the lower bound of the recursive join. Otherwise, we would
    // enumerate paths that are smaller than the lower bound from the start element, so we can stop
    // here.; OR
    if (path.size() == 1) {
        return nextIter >= info.lowerBound;
    }
    // (2) if this is not the first element in the stack, i.e., then we should switch
    // to forward tracking only if the next parent of the top node in the stack has the
    // same iter value as the current parent. That's because the levels/iter need to
    // decrease by 1 each time we add a new node in the stack.
    if (nextIter == getTop(path)->getIter()) {
        return true;
    }
    return false;
}

bool PathsOutputWriter::checkPathNodeMask(ParentList* element) const {
    if (!info.hasNodeMask() || element->getIter() == 1) {
        return true;
    }
    return info.pathNodeMask->valid(element->getNodeID());
}

bool PathsOutputWriter::checkAppendSemantic(const std::vector<ParentList*>& path,
    ParentList* candidate) const {
    switch (info.semantic) {
    case PathSemantic::WALK:
        return true;
    case PathSemantic::TRAIL:
        return isAppendTrail(path, candidate);
    case PathSemantic::ACYCLIC:
        return isAppendAcyclic(path, candidate);
    default:
        KU_UNREACHABLE;
    }
}

bool PathsOutputWriter::checkReplaceTopSemantic(const std::vector<ParentList*>& path,
    ParentList* candidate) const {
    switch (info.semantic) {
    case PathSemantic::WALK:
        return true;
    case PathSemantic::TRAIL:
        return isReplaceTopTrail(path, candidate);
    case PathSemantic::ACYCLIC:
        return isReplaceTopAcyclic(path, candidate);
    default:
        KU_UNREACHABLE;
    }
}

bool PathsOutputWriter::isAppendTrail(const std::vector<ParentList*>& path,
    ParentList* candidate) const {
    for (auto& element : path) {
        if (candidate->getEdgeID() == element->getEdgeID()) {
            return false;
        }
    }
    return true;
}

bool PathsOutputWriter::isAppendAcyclic(const std::vector<ParentList*>& path,
    ParentList* candidate) const {
    // Skip dst for semantic checking
    for (auto i = 1u; i < path.size() - 1; ++i) {
        if (candidate->getNodeID() == path[i]->getNodeID()) {
            return false;
        }
    }
    return true;
}

bool PathsOutputWriter::isReplaceTopTrail(const std::vector<ParentList*>& path,
    ParentList* candidate) const {
    for (auto i = 0u; i < path.size() - 1; ++i) {
        if (candidate->getEdgeID() == path[i]->getEdgeID()) {
            return false;
        }
    }
    return true;
}

bool PathsOutputWriter::isReplaceTopAcyclic(const std::vector<ParentList*>& path,
    ParentList* candidate) const {
    // Skip dst for semantic checking
    for (auto i = 1u; i < path.size() - 1; ++i) {
        if (candidate->getNodeID() == path[i]->getNodeID()) {
            return false;
        }
    }
    return true;
}

static void setLength(ValueVector* vector, uint16_t length) {
    KU_ASSERT(vector->dataType.getLogicalTypeID() == LogicalTypeID::UINT16);
    vector->setValue<uint16_t>(0, length);
}

void PathsOutputWriter::beginWritePath(idx_t length) const {
    KU_ASSERT(info.writePath);
    addListEntry(pathNodeIDsVector.get(), length > 1 ? length - 1 : 0);
    addListEntry(pathEdgeIDsVector.get(), length);
    if (info.writeEdgeDirection) {
        addListEntry(directionVector.get(), length);
    }
}

void PathsOutputWriter::writePath(const std::vector<ParentList*>& path) const {
    setLength(lengthVector.get(), path.size());
    if (!info.writePath) {
        return;
    }
    beginWritePath(path.size());
    if (path.size() == 0) {
        return;
    }
    if (!info.flipPath) {
        // By default, write path in reverse direction because we append ParentList from dst to src.
        writePathBwd(path);
    } else {
        // Write path in original direction because computation started from dst node.
        // We want to present result in src->dst order.
        writePathFwd(path);
    }
}

void PathsOutputWriter::writePathFwd(const std::vector<ParentList*>& path) const {
    auto length = path.size();
    for (auto i = 0u; i < length - 1; i++) {
        auto p = path[i];
        addNode(p->getNodeID(), i);
        addEdge(p->getEdgeID(), p->isFwdEdge(), i);
    }
    auto lastPathElement = path[length - 1];
    addEdge(lastPathElement->getEdgeID(), lastPathElement->isFwdEdge(), length - 1);
}

void PathsOutputWriter::writePathBwd(const std::vector<ParentList*>& path) const {
    auto length = path.size();
    for (auto i = 1u; i < length; i++) {
        auto p = path[length - 1 - i];
        addNode(p->getNodeID(), i - 1);
        addEdge(p->getEdgeID(), p->isFwdEdge(), i);
    }
    auto lastPathElement = path[length - 1];
    addEdge(lastPathElement->getEdgeID(), lastPathElement->isFwdEdge(), 0);
}

void PathsOutputWriter::addEdge(relID_t edgeID, bool fwdEdge, sel_t pos) const {
    ListVector::getDataVector(pathEdgeIDsVector.get())->setValue(pos, edgeID);
    if (info.writeEdgeDirection) {
        ListVector::getDataVector(directionVector.get())->setValue(pos, fwdEdge);
    }
}

void PathsOutputWriter::addNode(nodeID_t nodeID, sel_t pos) const {
    ListVector::getDataVector(pathNodeIDsVector.get())->setValue(pos, nodeID);
}

void SPPathsOutputWriter::writeInternal(FactorizedTable& fTable, nodeID_t dstNodeID,
    LimitCounter* counter) {
    auto firstParent = findFirstParent(dstNodeID.offset);
    if (firstParent == nullptr) {
        return;
    }
    if (dstNodeID == sourceNodeID_) { // Avoid writing source
        KU_ASSERT(firstParent->getIter() == FRONTIER_INITIAL_VISITED);
        return;
    }
    if (!info.hasNodeMask() && info.semantic == PathSemantic::WALK) {
        dfsFast(firstParent, fTable, counter);
        return;
    }
    dfsSlow(firstParent, fTable, counter);
}

} // namespace function
} // namespace lbug
