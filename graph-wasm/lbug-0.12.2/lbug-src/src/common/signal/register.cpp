#ifdef LBUG_BACKTRACE
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <iostream>

#include <cpptrace/cpptrace.hpp>

namespace {

void handler(int signo) {
    // Not safe. Safe method would be writing directly to stderr with a pre-defined string
    // But since the below isn't safe either...
    std::cerr << "Fatal signal " << signo << std::endl;
    // This is not safe, however the safe version, described at the link below,
    // was causing hangs when the tracer program can't be found.
    // Since this is only used in CI, the occasional failure/hang is probably acceptable.
    // https://github.com/jeremy-rifkin/cpptrace/blob/main/docs/signal-safe-tracing.md
    cpptrace::generate_trace(1 /*skip this function's frame*/).print();
    std::_Exit(1);
}

int register_signal_handlers() noexcept {
    std::signal(SIGSEGV, handler);
    std::signal(SIGFPE, handler);
    cpptrace::register_terminate_handler();
    return 0;
}

static int ignore = register_signal_handlers();
}; // namespace
#endif
