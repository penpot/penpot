/*
  From https://github.com/p-ranav/glob
  Single header version
  Commit 8cd16218ae648addf166977d7e41b914768bcb05
  With commit 615c56cc2c59e5521ab8f20c3695e8fea92f669f reverted (broke tilde expansion)

  MIT License

  Copyright (c) 2019 Pranav

  Permission is hereby granted, free of charge, to any person obtaining a copy
  of this software and associated documentation files (the "Software"), to deal
  in the Software without restriction, including without limitation the rights
  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  copies of the Software, and to permit persons to whom the Software is
  furnished to do so, subject to the following conditions:

  The above copyright notice and this permission notice shall be included in all
  copies or substantial portions of the Software.

  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
  SOFTWARE.
*/


#pragma once
#include <cassert>
#include <functional>
#include <iostream>
#include <map>
#include <regex>
#include <string>
#include <vector>

#ifdef GLOB_USE_GHC_FILESYSTEM
#include <ghc/filesystem.hpp>
#else
#include <filesystem>
#endif

#include "re2.h"

namespace glob {

#ifdef GLOB_USE_GHC_FILESYSTEM
namespace fs = ghc::filesystem;
#else
namespace fs = std::filesystem;
#endif

namespace {

static inline 
bool string_replace(std::string &str, const std::string &from, const std::string &to) {
  std::size_t start_pos = str.find(from);
  if (start_pos == std::string::npos)
    return false;
  str.replace(start_pos, from.length(), to);
  return true;
}

static inline 
std::string translate(const std::string &pattern) {
  std::size_t i = 0, n = pattern.size();
  std::string result_string;

  while (i < n) {
    auto c = pattern[i];
    i += 1;
    if (c == '*') {
      result_string += ".*";
    } else if (c == '?') {
      result_string += ".";
    } else if (c == '[') {
      auto j = i;
      if (j < n && pattern[j] == '!') {
        j += 1;
      }
      if (j < n && pattern[j] == ']') {
        j += 1;
      }
      while (j < n && pattern[j] != ']') {
        j += 1;
      }
      if (j >= n) {
        result_string += "\\[";
      } else {
        auto stuff = std::string(pattern.begin() + i, pattern.begin() + j);
        if (stuff.find("--") == std::string::npos) {
          string_replace(stuff, std::string{"\\"}, std::string{R"(\\)"});
        } else {
          std::vector<std::string> chunks;
          std::size_t k = 0;
          if (pattern[i] == '!') {
            k = i + 2;
          } else {
            k = i + 1;
          }

          while (true) {
            k = pattern.find("-", k, j);
            if (k == std::string::npos) {
              break;
            }
            chunks.push_back(std::string(pattern.begin() + i, pattern.begin() + k));
            i = k + 1;
            k = k + 3;
          }

          chunks.push_back(std::string(pattern.begin() + i, pattern.begin() + j));
          // Escape backslashes and hyphens for set difference (--).
          // Hyphens that create ranges shouldn't be escaped.
          bool first = false;
          for (auto &s : chunks) {
            string_replace(s, std::string{"\\"}, std::string{R"(\\)"});
            string_replace(s, std::string{"-"}, std::string{R"(\-)"});
            if (first) {
              stuff += s;
              first = false;
            } else {
              stuff += "-" + s;
            }
          }
        }

        // Escape set operations (&&, ~~ and ||).
        std::string result;
        for (const auto& i: stuff) {
          if (i == '&' || i == '~' || i == '|') {
            result.push_back('\\');
          }
          result.push_back(i);
        }
        stuff = result;
        i = j + 1;
        if (stuff[0] == '!') {
          stuff = "^" + std::string(stuff.begin() + 1, stuff.end());
        } else if (stuff[0] == '^' || stuff[0] == '[') {
          stuff = "\\\\" + stuff;
        }
        result_string = result_string + "[" + stuff + "]";
      }
    } else {
      // SPECIAL_CHARS
      // closing ')', '}' and ']'
      // '-' (a range in character set)
      // '&', '~', (extended character set operations)
      // '#' (comment) and WHITESPACE (ignored) in verbose mode
      static std::string special_characters = "()[]{}?*+-|^$\\.&~# \t\n\r\v\f";
      static std::map<int, std::string> special_characters_map;
      if (special_characters_map.empty()) {
        for (auto &sc : special_characters) {
          special_characters_map.insert(
              std::make_pair(static_cast<int>(sc), std::string{"\\"} + std::string(1, sc)));
        }
      }

      if (special_characters.find(c) != std::string::npos) {
        result_string += special_characters_map[static_cast<int>(c)];
      } else {
        result_string += c;
      }
    }
  }
  return std::string{"(("} + result_string + std::string{R"()|[\r\n])$)"};
}

static inline 
bool fnmatch(const fs::path &name, const std::string &pattern) {
  return RE2::FullMatch(name.string(), translate(pattern));
}

static inline 
std::vector<fs::path> filter(const std::vector<fs::path> &names,
                             const std::string &pattern) {
  // std::cout << "Pattern: " << pattern << "\n";
  std::vector<fs::path> result;
  for (auto &name : names) {
    // std::cout << "Checking for " << name.string() << "\n";
    if (fnmatch(name, pattern)) {
      result.push_back(name);
    }
  }
  return result;
}

static inline 
fs::path expand_tilde(fs::path path) {
  if (path.empty()) return path;
#ifdef _WIN32
  char* home;
  size_t sz;
  _dupenv_s(&home, &sz, "USERPROFILE");
#else
  const char * home = std::getenv("HOME");
#endif
  if (home == nullptr) {
    throw std::invalid_argument("error: Unable to expand `~` - HOME environment variable not set.");
  }

  std::string s = path.string();
  if (s[0] == '~') {
    s = std::string(home) + s.substr(1, s.size() - 1);
    return fs::path(s);
  } else {
    return path;
  }
}

static inline 
bool has_magic(const std::string &pathname) {
  static const auto magic_check = RE2("([*?[])");
  return RE2::PartialMatch(pathname, magic_check);
}

static inline 
bool is_hidden(const std::string &pathname) {
  static const auto check = RE2("^(.*\\/)*\\.[^\\.\\/]+\\/*$");
  return RE2::FullMatch(pathname, check);
}

static inline 
bool is_recursive(const std::string &pattern) { return pattern == "**"; }

static inline 
std::vector<fs::path> iter_directory(const fs::path &dirname, bool dironly) {
  std::vector<fs::path> result;

  auto current_directory = dirname;
  if (current_directory.empty()) {
    current_directory = fs::current_path();
  }

  if (fs::exists(current_directory)) {
    try {
      for (auto &entry : fs::directory_iterator(
              current_directory, fs::directory_options::follow_directory_symlink |
                                      fs::directory_options::skip_permission_denied)) {
        if (!dironly || entry.is_directory()) {
          if (dirname.is_absolute()) {
            result.push_back(entry.path());
          } else {
            result.push_back(fs::relative(entry.path()));
          }
        }
      }
    } catch (std::exception&) {
      // not a directory
      // do nothing
    }
  }

  return result;
}

// Recursively yields relative pathnames inside a literal directory.
static inline 
std::vector<fs::path> rlistdir(const fs::path &dirname, bool dironly) {
  std::vector<fs::path> result;
  auto names = iter_directory(dirname, dironly);
  for (auto &x : names) {
    if (!is_hidden(x.string())) {
      result.push_back(x);
      for (auto &y : rlistdir(x, dironly)) {
        result.push_back(y);
      }
    }
  }
  return result;
}

// This helper function recursively yields relative pathnames inside a literal
// directory.
static inline 
std::vector<fs::path> glob2(const fs::path &dirname, [[maybe_unused]] const std::string &pattern,
                            bool dironly) {
  // std::cout << "In glob2\n";
  std::vector<fs::path> result;
  assert(is_recursive(pattern));
  for (auto &dir : rlistdir(dirname, dironly)) {
    result.push_back(dir);
  }
  return result;
}

// These 2 helper functions non-recursively glob inside a literal directory.
// They return a list of basenames.  _glob1 accepts a pattern while _glob0
// takes a literal basename (so it only has to check for its existence).
static inline 
std::vector<fs::path> glob1(const fs::path &dirname, const std::string &pattern,
                            bool dironly) {
  // std::cout << "In glob1\n";
  auto names = iter_directory(dirname, dironly);
  std::vector<fs::path> filtered_names;
  for (auto &n : names) {
    if (!is_hidden(n.string())) {
      filtered_names.push_back(n.filename());
      // if (n.is_relative()) {
      //   // std::cout << "Filtered (Relative): " << n << "\n";
      //   filtered_names.push_back(fs::relative(n));
      // } else {
      //   // std::cout << "Filtered (Absolute): " << n << "\n";
      //   filtered_names.push_back(n.filename());
      // }
    }
  }
  return filter(filtered_names, pattern);
}

static inline 
std::vector<fs::path> glob0(const fs::path &dirname, const fs::path &basename,
                            bool /*dironly*/) {
  // std::cout << "In glob0\n";
  std::vector<fs::path> result;
  if (basename.empty()) {
    // 'q*x/' should match only directories.
    if (fs::is_directory(dirname)) {
      result = {basename};
    }
  } else {
    if (fs::exists(dirname / basename)) {
      result = {basename};
    }
  }
  return result;
}

static inline 
std::vector<fs::path> glob(const std::string &pathname, bool recursive = false,
                           bool dironly = false) {
  std::vector<fs::path> result;

  auto path = fs::path(pathname);

  if (pathname[0] == '~') {
    // expand tilde
    path = expand_tilde(path);
  }

  auto dirname = path.parent_path();
  const auto basename = path.filename();

  if (!has_magic(pathname)) {
    assert(!dironly);
    if (!basename.empty()) {
      if (fs::exists(path)) {
        result.push_back(path);
      }
    } else {
      // Patterns ending with a slash should match only directories
      if (fs::is_directory(dirname)) {
        result.push_back(path);
      }
    }
    return result;
  }

  if (dirname.empty()) {
    if (recursive && is_recursive(basename.string())) {
      return glob2(dirname, basename.string(), dironly);
    } else {
      return glob1(dirname, basename.string(), dironly);
    }
  }

  std::vector<fs::path> dirs;
  if (dirname != fs::path(pathname) && has_magic(dirname.string())) {
    dirs = glob(dirname.string(), recursive, true);
  } else {
    dirs = {dirname};
  }

  std::function<std::vector<fs::path>(const fs::path &, const std::string &, bool)>
      glob_in_dir;
  if (has_magic(basename.string())) {
    if (recursive && is_recursive(basename.string())) {
      glob_in_dir = glob2;
    } else {
      glob_in_dir = glob1;
    }
  } else {
    glob_in_dir = glob0;
  }

  for (auto &d : dirs) {
    for (auto &name : glob_in_dir(d, basename.string(), dironly)) {
      fs::path subresult = name;
      if (name.parent_path().empty()) {
        subresult = d / name;
      }
      result.push_back(subresult);
    }
  }

  return result;
}

} // namespace end

static inline 
std::vector<fs::path> glob(const std::string &pathname) {
  return glob(pathname, false);
}

static inline 
std::vector<fs::path> rglob(const std::string &pathname) {
  return glob(pathname, true);
}

static inline 
std::vector<fs::path> glob(const std::vector<std::string> &pathnames) {
  std::vector<fs::path> result;
  for (auto &pathname : pathnames) {
    for (auto &match : glob(pathname, false)) {
      result.push_back(std::move(match));
    }
  }
  return result;
}

static inline 
std::vector<fs::path> rglob(const std::vector<std::string> &pathnames) {
  std::vector<fs::path> result;
  for (auto &pathname : pathnames) {
    for (auto &match : glob(pathname, true)) {
      result.push_back(std::move(match));
    }
  }
  return result;
}

static inline 
std::vector<fs::path>
glob(const std::initializer_list<std::string> &pathnames) {
  return glob(std::vector<std::string>(pathnames));
}

static inline 
std::vector<fs::path>
rglob(const std::initializer_list<std::string> &pathnames) {
  return rglob(std::vector<std::string>(pathnames));
}

} // namespace glob
