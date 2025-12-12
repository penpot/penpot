#pragma once

namespace lbug {
namespace common {
struct DatabaseLifeCycleManager {
    bool isDatabaseClosed = false;
    void checkDatabaseClosedOrThrow() const;
};
} // namespace common
} // namespace lbug
