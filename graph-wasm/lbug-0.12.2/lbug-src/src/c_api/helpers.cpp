#include "c_api/helpers.h"

#include <cstring>

#ifdef _WIN32
const uint64_t NS_TO_SEC = 10000000ULL;
const uint64_t SEC_TO_UNIX_EPOCH = 11644473600ULL;

time_t convertTmToTime(struct tm tm) {
    SYSTEMTIME st;
    st.wYear = tm.tm_year + 1900;
    st.wMonth = tm.tm_mon + 1;
    st.wDay = tm.tm_mday;
    st.wHour = tm.tm_hour;
    st.wMinute = tm.tm_min;
    st.wSecond = tm.tm_sec;
    st.wMilliseconds = 0;
    FILETIME ft;
    if (!SystemTimeToFileTime(&st, &ft)) {
        return -1;
    }
    ULARGE_INTEGER ull;
    ull.LowPart = ft.dwLowDateTime;
    ull.HighPart = ft.dwHighDateTime;
    return static_cast<time_t>((ull.QuadPart / NS_TO_SEC) - SEC_TO_UNIX_EPOCH);
}

int32_t convertTimeToTm(time_t time, struct tm* out_tm) {
    ULARGE_INTEGER ull;
    ull.QuadPart = (time + SEC_TO_UNIX_EPOCH) * NS_TO_SEC;
    FILETIME ft;
    ft.dwLowDateTime = ull.LowPart;
    ft.dwHighDateTime = ull.HighPart;
    SYSTEMTIME st;
    if (!FileTimeToSystemTime(&ft, &st)) {
        return -1;
    }
    out_tm->tm_year = st.wYear - 1900;
    out_tm->tm_mon = st.wMonth - 1;
    out_tm->tm_mday = st.wDay;
    out_tm->tm_hour = st.wHour;
    out_tm->tm_min = st.wMinute;
    out_tm->tm_sec = st.wSecond;
    return 0;
}
#endif

char* convertToOwnedCString(const std::string& str) {
    size_t src_len = str.size();
    auto* c_str = (char*)malloc(sizeof(char) * (src_len + 1));
    memcpy(c_str, str.c_str(), src_len);
    c_str[src_len] = '\0';
    return c_str;
}
