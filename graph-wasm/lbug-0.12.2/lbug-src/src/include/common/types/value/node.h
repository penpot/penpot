#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "common/api.h"

namespace lbug {
namespace common {

class Value;

/**
 * @brief NodeVal represents a node in the graph and stores the nodeID, label and properties of that
 * node.
 */
class NodeVal {
public:
    /**
     * @return all properties of the NodeVal.
     * @note this function copies all the properties into a vector, which is not efficient. use
     * `getPropertyName` and `getPropertyVal` instead if possible.
     */
    LBUG_API static std::vector<std::pair<std::string, std::unique_ptr<Value>>> getProperties(
        const Value* val);
    /**
     * @return number of properties of the RelVal.
     */
    LBUG_API static uint64_t getNumProperties(const Value* val);

    /**
     * @return the name of the property at the given index.
     */
    LBUG_API static std::string getPropertyName(const Value* val, uint64_t index);

    /**
     * @return the value of the property at the given index.
     */
    LBUG_API static Value* getPropertyVal(const Value* val, uint64_t index);
    /**
     * @return the nodeID as a Value.
     */
    LBUG_API static Value* getNodeIDVal(const Value* val);
    /**
     * @return the name of the node as a Value.
     */
    LBUG_API static Value* getLabelVal(const Value* val);
    /**
     * @return the current node values in string format.
     */
    LBUG_API static std::string toString(const Value* val);

private:
    static void throwIfNotNode(const Value* val);
    // 2 offsets for id and label.
    static constexpr uint64_t OFFSET = 2;
};

} // namespace common
} // namespace lbug
