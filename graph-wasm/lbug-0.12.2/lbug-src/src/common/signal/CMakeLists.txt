if (ENABLE_BACKTRACES)
    add_library(register_backtrace_signal_handler OBJECT register.cpp)
    target_link_libraries(register_backtrace_signal_handler cpptrace::cpptrace)
endif()
