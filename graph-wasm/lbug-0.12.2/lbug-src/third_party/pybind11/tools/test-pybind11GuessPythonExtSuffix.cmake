cmake_minimum_required(VERSION 3.5)

# Tests for pybind11_guess_python_module_extension
# Run using `cmake -P tools/test-pybind11GuessPythonExtSuffix.cmake`

include("${CMAKE_CURRENT_LIST_DIR}/pybind11GuessPythonExtSuffix.cmake")

macro(expect_streq actual expected)
  if(NOT "${actual}" STREQUAL "${expected}")
    message(SEND_ERROR "Fail\n *** actual:   '${actual}'\n *** expected: '${expected}'")
  endif()
endmacro()

macro(expect_false actual)
  if("${actual}")
    message(SEND_ERROR "Fail\n *** actual:   '${actual}'\n *** expected: false")
  endif()
endmacro()

macro(expect_true actual)
  if(NOT "${actual}")
    message(SEND_ERROR "Fail\n *** actual:   '${actual}'\n *** expected: true")
  endif()
endmacro()

# Windows
set(CMAKE_SYSTEM_NAME "Windows")
set(CMAKE_SHARED_MODULE_SUFFIX ".dll")

set(Python3_SOABI "")
pybind11_guess_python_module_extension("Python3")
expect_streq("${PYTHON_MODULE_EXTENSION}" ".pyd")
expect_streq("${PYTHON_MODULE_DEBUG_POSTFIX}" "")
expect_false("${PYTHON_IS_DEBUG}")
unset(PYTHON_MODULE_EXT_SUFFIX)
unset(PYTHON_MODULE_EXT_SUFFIX CACHE)

set(Python3_SOABI "cp311-win_arm64")
pybind11_guess_python_module_extension("Python3")
expect_streq("${PYTHON_MODULE_EXTENSION}" ".cp311-win_arm64.pyd")
expect_streq("${PYTHON_MODULE_DEBUG_POSTFIX}" "")
expect_false("${PYTHON_IS_DEBUG}")
unset(PYTHON_MODULE_EXT_SUFFIX)
unset(PYTHON_MODULE_EXT_SUFFIX CACHE)

set(Python3_SOABI "cp311d-win_arm64")
pybind11_guess_python_module_extension("Python3")
expect_streq("${PYTHON_MODULE_EXTENSION}" ".cp311d-win_arm64.pyd")
expect_streq("${PYTHON_MODULE_DEBUG_POSTFIX}" "")
expect_true("${PYTHON_IS_DEBUG}")
unset(PYTHON_MODULE_EXT_SUFFIX)
unset(PYTHON_MODULE_EXT_SUFFIX CACHE)

set(Python3_SOABI "pypy310-pp73-win_amd64")
pybind11_guess_python_module_extension("Python3")
expect_streq("${PYTHON_MODULE_EXTENSION}" ".pypy310-pp73-win_amd64.pyd")
expect_streq("${PYTHON_MODULE_DEBUG_POSTFIX}" "")
expect_false("${PYTHON_IS_DEBUG}")
unset(PYTHON_MODULE_EXT_SUFFIX)
unset(PYTHON_MODULE_EXT_SUFFIX CACHE)

set(Python3_SOABI "_d.cp311-win_amd64.pyd") # This is a quirk of FindPython3
pybind11_guess_python_module_extension("Python3")
expect_streq("${PYTHON_MODULE_EXTENSION}" ".cp311-win_amd64.pyd")
expect_streq("${PYTHON_MODULE_DEBUG_POSTFIX}" "_d")
expect_true("${PYTHON_IS_DEBUG}")
unset(PYTHON_MODULE_EXT_SUFFIX)
unset(PYTHON_MODULE_EXT_SUFFIX CACHE)

unset(Python3_SOABI)
set(ENV{SETUPTOOLS_EXT_SUFFIX} ".cp39-win_arm64.pyd") # Set by cibuildwheel
pybind11_guess_python_module_extension("Python3")
expect_streq("${PYTHON_MODULE_EXTENSION}" ".cp39-win_arm64.pyd")
expect_streq("${PYTHON_MODULE_DEBUG_POSTFIX}" "")
expect_false("${PYTHON_IS_DEBUG}")
unset(PYTHON_MODULE_EXT_SUFFIX)
unset(PYTHON_MODULE_EXT_SUFFIX CACHE)
unset(ENV{SETUPTOOLS_EXT_SUFFIX})

set(Python3_SOABI "cp311-win_arm64")
set(ENV{SETUPTOOLS_EXT_SUFFIX} "") # Should not be used
pybind11_guess_python_module_extension("Python3")
expect_streq("${PYTHON_MODULE_EXTENSION}" ".cp311-win_arm64.pyd")
expect_streq("${PYTHON_MODULE_DEBUG_POSTFIX}" "")
expect_false("${PYTHON_IS_DEBUG}")
unset(PYTHON_MODULE_EXT_SUFFIX)
unset(PYTHON_MODULE_EXT_SUFFIX CACHE)
unset(ENV{SETUPTOOLS_EXT_SUFFIX})

# macOS
set(CMAKE_SYSTEM_NAME "Darwin")
set(CMAKE_SHARED_MODULE_SUFFIX ".so")

set(Python3_SOABI "")
pybind11_guess_python_module_extension("Python3")
expect_streq("${PYTHON_MODULE_EXTENSION}" ".so")
expect_streq("${PYTHON_MODULE_DEBUG_POSTFIX}" "")
expect_false("${PYTHON_IS_DEBUG}")
unset(PYTHON_MODULE_EXT_SUFFIX)
unset(PYTHON_MODULE_EXT_SUFFIX CACHE)

set(Python3_SOABI "cpython-312-darwin")
pybind11_guess_python_module_extension("Python3")
expect_streq("${PYTHON_MODULE_EXTENSION}" ".cpython-312-darwin.so")
expect_streq("${PYTHON_MODULE_DEBUG_POSTFIX}" "")
expect_false("${PYTHON_IS_DEBUG}")
unset(PYTHON_MODULE_EXT_SUFFIX)
unset(PYTHON_MODULE_EXT_SUFFIX CACHE)

set(Python3_SOABI "cpython-312d-darwin")
pybind11_guess_python_module_extension("Python3")
expect_streq("${PYTHON_MODULE_EXTENSION}" ".cpython-312d-darwin.so")
expect_streq("${PYTHON_MODULE_DEBUG_POSTFIX}" "")
expect_true("${PYTHON_IS_DEBUG}")
unset(PYTHON_MODULE_EXT_SUFFIX)
unset(PYTHON_MODULE_EXT_SUFFIX CACHE)

# Linux
set(CMAKE_SYSTEM_NAME "Linux")
set(CMAKE_SHARED_MODULE_SUFFIX ".so")

set(Python3_SOABI "")
pybind11_guess_python_module_extension("Python3")
expect_streq("${PYTHON_MODULE_EXTENSION}" ".so")
expect_streq("${PYTHON_MODULE_DEBUG_POSTFIX}" "")
expect_false("${PYTHON_IS_DEBUG}")
unset(PYTHON_MODULE_EXT_SUFFIX)
unset(PYTHON_MODULE_EXT_SUFFIX CACHE)

set(Python3_SOABI "cpython-312-arm-linux-gnueabihf")
pybind11_guess_python_module_extension("Python3")
expect_streq("${PYTHON_MODULE_EXTENSION}" ".cpython-312-arm-linux-gnueabihf.so")
expect_streq("${PYTHON_MODULE_DEBUG_POSTFIX}" "")
expect_false("${PYTHON_IS_DEBUG}")
unset(PYTHON_MODULE_EXT_SUFFIX)
unset(PYTHON_MODULE_EXT_SUFFIX CACHE)

set(Python3_SOABI "cpython-312d-arm-linux-gnueabihf")
pybind11_guess_python_module_extension("Python3")
expect_streq("${PYTHON_MODULE_EXTENSION}" ".cpython-312d-arm-linux-gnueabihf.so")
expect_streq("${PYTHON_MODULE_DEBUG_POSTFIX}" "")
expect_true("${PYTHON_IS_DEBUG}")
unset(PYTHON_MODULE_EXT_SUFFIX)
unset(PYTHON_MODULE_EXT_SUFFIX CACHE)

set(Python3_SOABI "pypy310-pp73-x86_64-linux-gnu")
pybind11_guess_python_module_extension("Python3")
expect_streq("${PYTHON_MODULE_EXTENSION}" ".pypy310-pp73-x86_64-linux-gnu.so")
expect_streq("${PYTHON_MODULE_DEBUG_POSTFIX}" "")
expect_false("${PYTHON_IS_DEBUG}")
unset(PYTHON_MODULE_EXT_SUFFIX)
unset(PYTHON_MODULE_EXT_SUFFIX CACHE)

set(Python3_SOABI "pypy310d-pp73-x86_64-linux-gnu")
# TODO: I'm not sure if this is the right SOABI for PyPy debug builds
pybind11_guess_python_module_extension("Python3")
expect_streq("${PYTHON_MODULE_EXTENSION}" ".pypy310d-pp73-x86_64-linux-gnu.so")
expect_streq("${PYTHON_MODULE_DEBUG_POSTFIX}" "")
expect_true("${PYTHON_IS_DEBUG}")
unset(PYTHON_MODULE_EXT_SUFFIX)
unset(PYTHON_MODULE_EXT_SUFFIX CACHE)
