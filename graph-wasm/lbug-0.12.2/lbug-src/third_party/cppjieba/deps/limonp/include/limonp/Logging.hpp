#ifndef LIMONP_LOGGING_HPP
#define LIMONP_LOGGING_HPP

#include <sstream>
#include <iostream>
#include <cassert>
#include <cstdlib>
#include <ctime>

#ifdef XLOG
#error "XLOG has been defined already"
#endif // XLOG
#ifdef XCHECK
#error "XCHECK has been defined already"
#endif // XCHECK

#define XLOG(level) limonp::Logger(limonp::LL_##level, __FILE__, __LINE__).Stream() 
#define XCHECK(exp) if(!(exp)) XLOG(FATAL) << "exp: ["#exp << "] false. "

namespace limonp {

enum {
  LL_DEBUG = 0, 
  LL_INFO = 1, 
  LL_WARNING = 2, 
  LL_ERROR = 3, 
  LL_FATAL = 4,
}; // enum

static const char * LOG_LEVEL_ARRAY[] = {"DEBUG","INFO","WARN","ERROR","FATAL"};
static const char * LOG_TIME_FORMAT = "%Y-%m-%d %H:%M:%S";

class Logger {
 public:
  Logger(size_t level, const char* filename, int lineno)
   : level_(level) {
#ifdef LOGGING_LEVEL
     if (level_ < LOGGING_LEVEL) {
       return;
     }
#endif
    assert(level_ <= sizeof(LOG_LEVEL_ARRAY)/sizeof(*LOG_LEVEL_ARRAY));
    
    char buf[32];
    
    time_t timeNow;
    time(&timeNow);

    struct tm tmNow;

    #if defined(_WIN32) || defined(_WIN64)
    errno_t e = localtime_s(&tmNow, &timeNow);
    assert(e == 0);
    #else
    struct tm * tm_tmp = localtime_r(&timeNow, &tmNow);
    (void)tm_tmp;
    assert(tm_tmp != nullptr);
    #endif

    strftime(buf, sizeof(buf), LOG_TIME_FORMAT, &tmNow);

    stream_ << buf 
      << " " << filename 
      << ":" << lineno 
      << " " << LOG_LEVEL_ARRAY[level_] 
      << " ";
  }
  ~Logger() {
#ifdef LOGGING_LEVEL
     if (level_ < LOGGING_LEVEL) {
       return;
     }
#endif
    std::cerr << stream_.str() << std::endl;
    if (level_ == LL_FATAL) {
      abort();
    }
  }

  std::ostream& Stream() {
    return stream_;
  }

 private:
  std::ostringstream stream_;
  size_t level_;
}; // class Logger

} // namespace limonp

#endif // LIMONP_LOGGING_HPP
