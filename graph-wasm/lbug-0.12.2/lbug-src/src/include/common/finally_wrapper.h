#pragma once

#include <concepts>

namespace lbug::common {
// RAII wrapper that calls an enclosed function when this class goes out of scope
// Should be used for any cleanup code that must be executed even if exceptions occur
template<std::invocable Func>
struct FinallyWrapper {
    explicit FinallyWrapper(Func&& func) : func(func) {}
    ~FinallyWrapper() { func(); }
    Func func;
};
} // namespace lbug::common
