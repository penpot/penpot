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
 * @brief RelVal represents a rel in the graph and stores the relID, src/dst nodes and properties of
 * that rel.
 */
class RelVal {
public:
    /**
     * @return all properties of the RelVal.
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
     * @return the src nodeID value of the RelVal in Value.
     */
    LBUG_API static Value* getSrcNodeIDVal(const Value* val);
    /**
     * @return the dst nodeID value of the RelVal in Value.
     */
    LBUG_API static Value* getDstNodeIDVal(const Value* val);
    /**
     * @return the internal ID value of the RelVal in Value.
     */
    LBUG_API static Value* getIDVal(const Value* val);
    /**
     * @return the label value of the RelVal.
     */
    LBUG_API static Value* getLabelVal(const Value* val);
    /**
     * @return the value of the RelVal in string format.
     */
    LBUG_API static std::string toString(const Value* val);

private:
    static void throwIfNotRel(const Value* val);
    // 4 offset for id, label, src, dst.
    static constexpr uint64_t OFFSET = 4;
};

} // namespace common
} // namespace lbug
