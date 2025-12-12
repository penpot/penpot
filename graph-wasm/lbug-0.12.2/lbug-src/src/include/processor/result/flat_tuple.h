#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "common/api.h"
#include "common/types/value/value.h"

namespace lbug {
namespace processor {

/**
 * @brief Stores a vector of Values.
 */
class FlatTuple {
public:
    explicit FlatTuple(const std::vector<common::LogicalType>& types);

    DELETE_COPY_AND_MOVE(FlatTuple);

    /**
     * @return number of values in the FlatTuple.
     */
    LBUG_API common::idx_t len() const;
    /**
     * @brief Get a pointer to the value at the specified index.
     * @param idx The index of the value to retrieve.
     * @return A pointer to the Value at the specified index.
     */
    LBUG_API common::Value* getValue(common::idx_t idx);

    /**
     * @brief Access the value at the specified index by reference.
     * @param idx The index of the value to access.
     * @return A reference to the Value at the specified index.
     */
    LBUG_API common::Value& operator[](common::idx_t idx);

    /**
     * @brief Access the value at the specified index by const reference.
     * @param idx The index of the value to access.
     * @return A const reference to the Value at the specified index.
     */
    LBUG_API const common::Value& operator[](common::idx_t idx) const;

    /**
     * @brief Convert the FlatTuple to a string representation.
     * @return A string representation of all values in the FlatTuple.
     */
    LBUG_API std::string toString() const;

    /**
     * @param colsWidth The length of each column
     * @param delimiter The delimiter to separate each value.
     * @param maxWidth The maximum length of each column. Only the first maxWidth number of
     * characters of each column will be displayed.
     * @return all values in string format.
     */
    LBUG_API std::string toString(const std::vector<uint32_t>& colsWidth,
        const std::string& delimiter = "|", uint32_t maxWidth = -1);

private:
    std::vector<common::Value> values;
};

} // namespace processor
} // namespace lbug
