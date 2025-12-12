#include "common/system_message.h"

#ifdef _WIN32
#include "windows.h"
#else
#include <dlfcn.h>
#endif

namespace lbug {
namespace common {

std::string dlErrMessage() {
#ifdef _WIN32
    DWORD errorMessageID = GetLastError();
    if (errorMessageID == 0) {
        return std::string();
    }

    LPSTR messageBuffer = nullptr;
    auto size = FormatMessageA(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM |
                                   FORMAT_MESSAGE_IGNORE_INSERTS,
        NULL, errorMessageID, MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT), (LPSTR)&messageBuffer, 0,
        NULL);

    std::string message(messageBuffer, size);

    // Free the buffer.
    LocalFree(messageBuffer);

    return message;
#else
    return dlerror(); // NOLINT(concurrency-mt-unsafe): load can only be executed in single thread.
#endif
}

} // namespace common
} // namespace lbug
