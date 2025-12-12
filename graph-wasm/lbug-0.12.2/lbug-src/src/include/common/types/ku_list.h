#pragma once

#include "types.h"

namespace lbug {
namespace common {

struct ku_list_t {
    ku_list_t() : size{0}, overflowPtr{0} {}
    ku_list_t(uint64_t size, uint64_t overflowPtr) : size{size}, overflowPtr{overflowPtr} {}

    void set(const uint8_t* values, const LogicalType& dataType) const;

private:
    void set(const std::vector<uint8_t*>& parameters, LogicalTypeID childTypeId);

public:
    uint64_t size;
    uint64_t overflowPtr;
};

} // namespace common
} // namespace lbug
