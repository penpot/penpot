# CHANGELOG

## v1.0.1

+ [CI] Update GitHub Actions configurations
  - Add stale issues workflow
  - Update checkout action from v2 to v4
  - Update macOS test environments (remove macOS-12, add macOS-15)
+ [dep] Update googletest to release-1.12.1
+ [doc] Add build instructions to README.md

## v1.0.0

+ rm thread pool demo
+ deleted:    include/limonp/BlockingQueue.hpp
+ deleted:    include/limonp/BoundedBlockingQueue.hpp
+ deleted:    include/limonp/BoundedQueue.hpp
+ deleted:    include/limonp/MutexLock.hpp
+ deleted:    include/limonp/Thread.hpp
+ deleted:    include/limonp/ThreadPool.hpp
+ deleted:    test/unittest/TBlockingQueue.cpp
+ deleted:    test/unittest/TBoundedQueue.cpp
+ deleted:    test/unittest/TMutexLock.cpp
+ deleted:    test/unittest/TThread.cpp
+ deleted:    test/unittest/TThreadPool.cpp
+ rm FileLock
+ rm Md5.hpp

## v0.9.0

+ [c++20] compatibility
+ [c++17] compatibility

## v0.8.1

+ [CI] fix windows gtest thread link error
+ [submodule] rm test/googletest
+ [CMake] FetchContent googletest
+ [CMake] required 3.5 -> 3.14

## v0.8.0

+ [StringUtil] Fix windows assert typo
+ [CMake] find_package(Threads REQUIRED); target_link_libraries(... Threads::Threads)
+ [CMAKE][CI] windows: 2019,2022
+ [CMAKE][CI] matrix.build_type[Release, Debug]
+ [unittest] disable #TMd5.cpp
+ [unittest] disable #TFileLock.cpp
+ [unittest] disable #TBoundedQueue.cpp 
+ [unittest] disable #TMutexLock.cpp 
+ [unittest] disable #TBlockingQueue.cpp 
+ [unittest] disable #TThread.cpp 
+ [unittest] disable #TThreadPool.cpp

## v0.7.2

+ [CI] ubuntu version from 20 to 22, macos version from 12 to 14
+ [test/unittes] uint->size_t
+ [googletest] v1.6.0->v1.10.0
+ [CMake] version required 3.0 -> 3.5

## v0.7.1

+ [CMake] fix CMAKE_CXX_STANDARD passed from github/actions and [c++11, c++14] only

## v0.7.0

+ [CI] Added os.macos and cpp_version=[c++98, c++03, c++11, c++14, c++17, c++20]
+ [git submodule] Added googletest-release-v1.6.0

## v0.6.7

+ Merged [pr35](https://github.com/yanyiwu/limonp/pull/35)
+ Merged [pr33](https://github.com/yanyiwu/limonp/pull/33)
+ Merged [pr32](https://github.com/yanyiwu/limonp/pull/32)

## v0.6.6

+ Merged [pr-31 To be compatible with cpp17 and later, use lambda instead of std::not1 & std::bind2nd #31](https://github.com/yanyiwu/limonp/pull/31)

## v0.6.5

+ Merged [pr-25 Update cmake.yml](https://github.com/yanyiwu/limonp/pull/25)
+ Merged [pr-26 fix license for end of line sequence and remove useless signature](https://github.com/yanyiwu/limonp/pull/26)
+ Merged [pr-27 Update cmake.yml](https://github.com/yanyiwu/limonp/pull/27)
+ Merged [pr-28 add a target to be ready to support installation](https://github.com/yanyiwu/limonp/pull/28)
+ Merged [pr-29 Installable by cmake](https://github.com/yanyiwu/limonp/pull/29)
+ Merged [pr-30 Replace localtime with localtime_s on Windows and localtime_r on Linux](https://github.com/yanyiwu/limonp/pull/30)

## v0.6.4

+ merge [fixup gcc8 warnings](https://github.com/yanyiwu/gojieba/pull/70)

## v0.6.3

+ remove compiler conplained macro

## v0.6.2

+ merge [pr-18](https://github.com/yanyiwu/limonp/pull/18/files)

## v0.6.1

add Specialized template for vector<string>

when it is `vector<string>`, print like this: ["hello", "world"]; (special case)
when it is `vector<int>`, print like this: [1, 10, 1000]; (common cases)

## v0.6.0

+ remove Trim out of Split.

## v0.5.6

+ fix hidden trouble.

## v0.5.5

+ macro name LOG and CHECK in Logging.hpp is so easy to confict with other lib, so I have to rename them to XLOG and XCHECK for avoiding those macro name conflicts.

## v0.5.4

+ add ForcePublic.hpp
+ Add Utf8ToUnicode32 and Unicode32ToUtf8 in StringUtil.hpp

## v0.5.3

+ Fix incompatibility problem about 'time.h' in Windows.

## v0.5.2

+ Fix incompatibility problem about `enum {INFO ...}` name conflicts in Windows .
+ So from this version begin: the compile flags: `-DLOGGING_LEVEL=WARNING` must be changed to `-DLOGGING_LEVEL=LL_WARNING`

## v0.5.1

+ add `ThreadPool::Stop()` to wait util all the threads finished. 
If Stop() has not been called, it will be called when the ThreadPool destructing.

## v0.5.0

+ Reorganized directories: include/ -> include/limonp/ ... and so on.
+ Add `NewClosure` in Closure.hpp, 0~3 arguments have been supported.
+ Update ThreadPool, use `NewClosure` instead of `CreateTask`

## v0.4.1

+ `CHECK(exp) << "log message"` supported;

## v0.4.0

+ add test/demo.cc as example.
+ move `print` macro to StdExtension.hpp
+ BigChange: rewrite `log` module, use `LOG(INFO) << "xxx" ` instead `LogInfo` .
+ remove HandyMacro.hpp, add CHECK in Logging.hpp instead.

## v0.3.0

+ remove 'MysqlClient.hpp', 'InitOnOff.hpp', 'CastFloat.hpp'
+ add 'Closure.hpp'
+ uniform code style

## v0.2.0

+ `namespace limonp`, not `Limonp` .

## v0.1.0

+ Basic functions
