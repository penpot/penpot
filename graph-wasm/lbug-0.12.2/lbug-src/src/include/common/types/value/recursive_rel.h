#pragma once

#include "common/api.h"

namespace lbug {
namespace common {

class Value;

/**
 * @brief RecursiveRelVal represents a path in the graph and stores the corresponding rels and nodes
 * of that path.
 */
class RecursiveRelVal {
public:
    /**
     * @return the list of nodes in the recursive rel as a Value.
     */
    LBUG_API static Value* getNodes(const Value* val);

    /**
     * @return the list of rels in the recursive rel as a Value.
     */
    LBUG_API static Value* getRels(const Value* val);

private:
    static void throwIfNotRecursiveRel(const Value* val);
};

} // namespace common
} // namespace lbug
