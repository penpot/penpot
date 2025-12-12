#include "binder/query/query_graph.h"

#include "binder/expression_visitor.h"

namespace lbug {
namespace binder {

std::size_t SubqueryGraphHasher::operator()(const SubqueryGraph& key) const {
    if (0 == key.queryRelsSelector.count()) {
        return std::hash<std::bitset<MAX_NUM_QUERY_VARIABLES>>{}(key.queryNodesSelector);
    }
    return std::hash<std::bitset<MAX_NUM_QUERY_VARIABLES>>{}(key.queryRelsSelector);
}

bool SubqueryGraph::containAllVariables(const std::unordered_set<std::string>& variables) const {
    for (auto& var : variables) {
        if (queryGraph.containsQueryNode(var) &&
            !queryNodesSelector[queryGraph.getQueryNodeIdx(var)]) {
            return false;
        }
        if (queryGraph.containsQueryRel(var) &&
            !queryRelsSelector[queryGraph.getQueryRelIdx(var)]) {
            return false;
        }
    }
    return true;
}

std::unordered_set<uint32_t> SubqueryGraph::getNodeNbrPositions() const {
    std::unordered_set<uint32_t> result;
    for (auto relPos = 0u; relPos < queryGraph.getNumQueryRels(); ++relPos) {
        if (!queryRelsSelector[relPos]) { // rel not in subgraph, no need to check
            continue;
        }
        auto rel = queryGraph.getQueryRel(relPos);
        auto srcNodePos = queryGraph.getQueryNodeIdx(*rel->getSrcNode());
        if (!queryNodesSelector[srcNodePos]) {
            result.insert(srcNodePos);
        }
        auto dstNodePos = queryGraph.getQueryNodeIdx(*rel->getDstNode());
        if (!queryNodesSelector[dstNodePos]) {
            result.insert(dstNodePos);
        }
    }
    return result;
}

std::unordered_set<uint32_t> SubqueryGraph::getRelNbrPositions() const {
    std::unordered_set<uint32_t> result;
    for (auto relPos = 0u; relPos < queryGraph.getNumQueryRels(); ++relPos) {
        if (queryRelsSelector[relPos]) { // rel already in subgraph, cannot be rel neighbour
            continue;
        }
        auto rel = queryGraph.getQueryRel(relPos);
        auto srcNodePos = queryGraph.getQueryNodeIdx(*rel->getSrcNode());
        auto dstNodePos = queryGraph.getQueryNodeIdx(*rel->getDstNode());
        if (queryNodesSelector[srcNodePos] || queryNodesSelector[dstNodePos]) {
            result.insert(relPos);
        }
    }
    return result;
}

subquery_graph_set_t SubqueryGraph::getNbrSubgraphs(uint32_t size) const {
    auto result = getBaseNbrSubgraph();
    for (auto i = 1u; i < size; ++i) {
        std::unordered_set<SubqueryGraph, SubqueryGraphHasher> tmp;
        for (auto& prevNbr : result) {
            for (auto& subgraph : getNextNbrSubgraphs(prevNbr)) {
                tmp.insert(subgraph);
            }
        }
        result = std::move(tmp);
    }
    return result;
}

std::vector<uint32_t> SubqueryGraph::getConnectedNodePos(const SubqueryGraph& nbr) const {
    std::vector<uint32_t> result;
    for (auto& nodePos : getNodeNbrPositions()) {
        if (nbr.queryNodesSelector[nodePos]) {
            result.push_back(nodePos);
        }
    }
    for (auto& nodePos : nbr.getNodeNbrPositions()) {
        if (queryNodesSelector[nodePos]) {
            result.push_back(nodePos);
        }
    }
    return result;
}

std::unordered_set<uint32_t> SubqueryGraph::getNodePositionsIgnoringNodeSelector() const {
    std::unordered_set<uint32_t> result;
    for (auto nodePos = 0u; nodePos < queryGraph.getNumQueryNodes(); ++nodePos) {
        if (queryNodesSelector[nodePos]) {
            result.insert(nodePos);
        }
    }
    for (auto relPos = 0u; relPos < queryGraph.getNumQueryRels(); ++relPos) {
        auto rel = queryGraph.getQueryRel(relPos);
        if (queryRelsSelector[relPos]) {
            result.insert(queryGraph.getQueryNodeIdx(rel->getSrcNodeName()));
            result.insert(queryGraph.getQueryNodeIdx(rel->getDstNodeName()));
        }
    }
    return result;
}

std::vector<common::idx_t> SubqueryGraph::getNbrNodeIndices() const {
    std::unordered_set<common::idx_t> result;
    for (auto i = 0u; i < queryGraph.getNumQueryRels(); ++i) {
        if (!queryRelsSelector[i]) {
            continue;
        }
        auto rel = queryGraph.getQueryRel(i);
        auto srcNodePos = queryGraph.getQueryNodeIdx(rel->getSrcNodeName());
        auto dstNodePos = queryGraph.getQueryNodeIdx(rel->getDstNodeName());
        if (!queryNodesSelector[srcNodePos]) {
            result.insert(srcNodePos);
        }
        if (!queryNodesSelector[dstNodePos]) {
            result.insert(dstNodePos);
        }
    }
    return std::vector<common::idx_t>{result.begin(), result.end()};
}

subquery_graph_set_t SubqueryGraph::getBaseNbrSubgraph() const {
    subquery_graph_set_t result;
    for (auto& nodePos : getNodeNbrPositions()) {
        auto nbr = SubqueryGraph(queryGraph);
        nbr.addQueryNode(nodePos);
        result.insert(nbr);
    }
    for (auto& relPos : getRelNbrPositions()) {
        auto nbr = SubqueryGraph(queryGraph);
        nbr.addQueryRel(relPos);
        result.insert(nbr);
    }
    return result;
}

subquery_graph_set_t SubqueryGraph::getNextNbrSubgraphs(const SubqueryGraph& prevNbr) const {
    subquery_graph_set_t result;
    for (auto& nodePos : prevNbr.getNodeNbrPositions()) {
        if (queryNodesSelector[nodePos]) {
            continue;
        }
        auto nbr = prevNbr;
        nbr.addQueryNode(nodePos);
        result.insert(nbr);
    }
    for (auto& relPos : prevNbr.getRelNbrPositions()) {
        if (queryRelsSelector[relPos]) {
            continue;
        }
        auto nbr = prevNbr;
        nbr.addQueryRel(relPos);
        result.insert(nbr);
    }
    return result;
}

bool QueryGraph::isEmpty() const {
    for (auto& n : queryNodes) {
        if (n->isEmpty()) {
            return true;
        }
    }
    for (auto& r : queryRels) {
        if (r->isEmpty()) {
            return true;
        }
    }
    return false;
}

std::vector<std::shared_ptr<NodeOrRelExpression>> QueryGraph::getAllPatterns() const {
    std::vector<std::shared_ptr<NodeOrRelExpression>> patterns;
    for (auto& p : queryNodes) {
        patterns.push_back(p);
    }
    for (auto& p : queryRels) {
        patterns.push_back(p);
    }
    return patterns;
}

void QueryGraph::addQueryNode(std::shared_ptr<NodeExpression> queryNode) {
    // Note that a node may be added multiple times. We should only keep one of it.
    // E.g. MATCH (a:person)-[:knows]->(b:person), (a)-[:knows]->(c:person)
    // a will be added twice during binding
    if (containsQueryNode(queryNode->getUniqueName())) {
        return;
    }
    queryNodeNameToPosMap.insert({queryNode->getUniqueName(), queryNodes.size()});
    queryNodes.push_back(std::move(queryNode));
}

void QueryGraph::addQueryRel(std::shared_ptr<RelExpression> queryRel) {
    if (containsQueryRel(queryRel->getUniqueName())) {
        return;
    }
    queryRelNameToPosMap.insert({queryRel->getUniqueName(), queryRels.size()});
    queryRels.push_back(std::move(queryRel));
}

void QueryGraph::merge(const QueryGraph& other) {
    for (auto& otherNode : other.queryNodes) {
        addQueryNode(otherNode);
    }
    for (auto& otherRel : other.queryRels) {
        addQueryRel(otherRel);
    }
}

bool QueryGraph::canProjectExpression(const std::shared_ptr<Expression>& expression) const {
    auto collector = DependentVarNameCollector();
    collector.visit(expression);
    for (auto& variable : collector.getVarNames()) {
        if (!containsQueryNode(variable) && !containsQueryRel(variable)) {
            return false;
        }
    }
    return true;
}

bool QueryGraph::isConnected(const QueryGraph& other) const {
    for (auto& queryNode : queryNodes) {
        if (other.containsQueryNode(queryNode->getUniqueName())) {
            return true;
        }
    }
    return false;
}

void QueryGraphCollection::merge(const QueryGraphCollection& other) {
    for (auto& queryGraph : other.queryGraphs) {
        addAndMergeQueryGraphIfConnected(queryGraph);
    }
    finalize();
}

void QueryGraphCollection::addAndMergeQueryGraphIfConnected(QueryGraph queryGraphToAdd) {
    auto newQueryGraphSet = std::vector<QueryGraph>();
    for (auto i = 0u; i < queryGraphs.size(); i++) {
        auto queryGraph = std::move(queryGraphs[i]);
        if (queryGraph.isConnected(queryGraphToAdd)) {
            queryGraphToAdd.merge(queryGraph);
        } else {
            newQueryGraphSet.push_back(std::move(queryGraph));
        }
    }
    newQueryGraphSet.push_back(std::move(queryGraphToAdd));
    queryGraphs = std::move(newQueryGraphSet);
}

void QueryGraphCollection::finalize() {
    common::idx_t baseGraphIdx = 0;
    while (true) {
        auto prevNumGraphs = queryGraphs.size();
        queryGraphs = mergeGraphs(baseGraphIdx++);
        if (queryGraphs.size() == prevNumGraphs || baseGraphIdx == queryGraphs.size()) {
            return;
        }
    }
}

std::vector<QueryGraph> QueryGraphCollection::mergeGraphs(common::idx_t baseGraphIdx) {
    KU_ASSERT(baseGraphIdx < queryGraphs.size());
    auto& baseGraph = queryGraphs[baseGraphIdx];
    std::unordered_set<common::idx_t> mergedGraphIndices;
    mergedGraphIndices.insert(baseGraphIdx);
    while (true) {
        // find graph to merge
        common::idx_t graphToMergeIdx = common::INVALID_IDX;
        for (auto i = 0u; i < queryGraphs.size(); ++i) {
            if (mergedGraphIndices.contains(i)) { // graph has been merged.
                continue;
            }
            if (baseGraph.isConnected(queryGraphs[i])) { // find graph to merge.
                graphToMergeIdx = i;
                break;
            }
        }
        if (graphToMergeIdx == common::INVALID_IDX) { // No graph can be merged. Terminate.
            break;
        }
        // Perform merge
        baseGraph.merge(queryGraphs[graphToMergeIdx]);
        mergedGraphIndices.insert(graphToMergeIdx);
    }
    std::vector<QueryGraph> finalGraphs;
    for (auto i = 0u; i < queryGraphs.size(); ++i) {
        if (i == baseGraphIdx) {
            finalGraphs.push_back(baseGraph);
            continue;
        }
        if (mergedGraphIndices.contains(i)) {
            continue;
        }
        finalGraphs.push_back(std::move(queryGraphs[i]));
    }
    return finalGraphs;
}

bool QueryGraphCollection::contains(const std::string& name) const {
    for (auto& queryGraph : queryGraphs) {
        if (queryGraph.containsQueryNode(name) || queryGraph.containsQueryRel(name)) {
            return true;
        }
    }
    return false;
}

std::vector<std::shared_ptr<NodeExpression>> QueryGraphCollection::getQueryNodes() const {
    std::vector<std::shared_ptr<NodeExpression>> result;
    for (auto& queryGraph : queryGraphs) {
        for (auto& node : queryGraph.getQueryNodes()) {
            result.push_back(node);
        }
    }
    return result;
}

std::vector<std::shared_ptr<RelExpression>> QueryGraphCollection::getQueryRels() const {
    std::vector<std::shared_ptr<RelExpression>> result;
    for (auto& queryGraph : queryGraphs) {
        for (auto& rel : queryGraph.getQueryRels()) {
            result.push_back(rel);
        }
    }
    return result;
}

} // namespace binder
} // namespace lbug
