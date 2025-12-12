#pragma once

#include "common/types/uuid.h"

namespace lbug {
namespace function {

struct GenRandomUUID {
    static void operation(common::ku_uuid_t& input, void* dataPtr);
};

} // namespace function
} // namespace lbug
