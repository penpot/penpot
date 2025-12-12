#ifndef THRIFT_EXPORT_H
#define THRIFT_EXPORT_H

#ifdef THRIFT_STATIC_DEFINE
#  define THRIFT_EXPORT
#elif defined(_MSC_VER )
#  ifndef THRIFT_EXPORT
#    ifdef thrift_EXPORTS
          /* We are building this library */
#      define THRIFT_EXPORT __declspec(dllexport)
#    else
          /* We are using this library */
#      define THRIFT_EXPORT __declspec(dllimport)
#    endif
#  endif
#else
#  define THRIFT_EXPORT
#endif

#endif /* THRIFT_EXPORT_H */
