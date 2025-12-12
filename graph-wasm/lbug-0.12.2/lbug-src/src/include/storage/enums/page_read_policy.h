#pragma once

#include <cstdint>

namespace lbug {
namespace storage {

enum class PageReadPolicy : uint8_t { READ_PAGE = 0, DONT_READ_PAGE = 1 };

} // namespace storage
} // namespace lbug
