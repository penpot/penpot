#pragma once

#include <vector>

#include "pattern_element_chain.h"

namespace lbug {
namespace parser {

class PatternElement {
public:
    explicit PatternElement(NodePattern nodePattern) : nodePattern{std::move(nodePattern)} {}
    DELETE_COPY_DEFAULT_MOVE(PatternElement);

    inline void setPathName(std::string name) { pathName = std::move(name); }
    inline bool hasPathName() const { return !pathName.empty(); }
    inline std::string getPathName() const { return pathName; }

    inline const NodePattern* getFirstNodePattern() const { return &nodePattern; }

    inline void addPatternElementChain(PatternElementChain chain) {
        patternElementChains.push_back(std::move(chain));
    }
    inline uint32_t getNumPatternElementChains() const { return patternElementChains.size(); }
    inline const PatternElementChain* getPatternElementChain(uint32_t idx) const {
        return &patternElementChains[idx];
    }

private:
    std::string pathName;
    NodePattern nodePattern;
    std::vector<PatternElementChain> patternElementChains;
};

} // namespace parser
} // namespace lbug
