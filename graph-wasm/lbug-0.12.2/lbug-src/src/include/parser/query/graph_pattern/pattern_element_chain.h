#pragma once

#include "rel_pattern.h"

namespace lbug {
namespace parser {

class PatternElementChain {
public:
    PatternElementChain(RelPattern relPattern, NodePattern nodePattern)
        : relPattern{std::move(relPattern)}, nodePattern{std::move(nodePattern)} {}
    DELETE_COPY_DEFAULT_MOVE(PatternElementChain);

    inline const RelPattern* getRelPattern() const { return &relPattern; }

    inline const NodePattern* getNodePattern() const { return &nodePattern; }

private:
    RelPattern relPattern;
    NodePattern nodePattern;
};

} // namespace parser
} // namespace lbug
