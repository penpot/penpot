#pragma once

#include <cstdint>
#include <string>
#ifdef _WIN32
#include <time.h>

#include <windows.h>

time_t convertTmToTime(struct tm tm);

int32_t convertTimeToTm(time_t time, struct tm* out_tm);
#endif

char* convertToOwnedCString(const std::string& str);
