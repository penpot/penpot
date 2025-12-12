/************************************
 * file enc : utf8
 * author   : wuyanyi09@gmail.com
 ************************************/
#ifndef LIMONP_CONFIG_H
#define LIMONP_CONFIG_H

#include <map>
#include <fstream>
#include <iostream>
#include <assert.h>
#include "StringUtil.hpp"

namespace limonp {

using namespace std;

class Config {
 public:
  explicit Config(const string& filePath) {
    LoadFile(filePath);
  }

  operator bool () {
    return !map_.empty();
  }

  string Get(const string& key, const string& defaultvalue) const {
    map<string, string>::const_iterator it = map_.find(key);
    if(map_.end() != it) {
      return it->second;
    }
    return defaultvalue;
  }
  int Get(const string& key, int defaultvalue) const {
    string str = Get(key, "");
    if("" == str) {
      return defaultvalue;
    }
    return atoi(str.c_str());
  }
  const char* operator [] (const char* key) const {
    if(NULL == key) {
      return NULL;
    }
    map<string, string>::const_iterator it = map_.find(key);
    if(map_.end() != it) {
      return it->second.c_str();
    }
    return NULL;
  }

  string GetConfigInfo() const {
    string res;
    res << *this;
    return res;
  }

 private:
  void LoadFile(const string& filePath) {
    ifstream ifs(filePath.c_str());
    assert(ifs);
    string line;
    vector<string> vecBuf;
    size_t lineno = 0;
    while(getline(ifs, line)) {
      lineno ++;
      Trim(line);
      if(line.empty() || StartsWith(line, "#")) {
        continue;
      }
      vecBuf.clear();
      Split(line, vecBuf, "=");
      if(2 != vecBuf.size()) {
        fprintf(stderr, "line[%s] illegal.\n", line.c_str());
        assert(false);
        continue;
      }
      string& key = vecBuf[0];
      string& value = vecBuf[1];
      Trim(key);
      Trim(value);
      if(!map_.insert(make_pair(key, value)).second) {
        fprintf(stderr, "key[%s] already exits.\n", key.c_str());
        assert(false);
        continue;
      }
    }
    ifs.close();
  }

  friend ostream& operator << (ostream& os, const Config& config);

  map<string, string> map_;
}; // class Config

inline ostream& operator << (ostream& os, const Config& config) {
  return os << config.map_;
}

} // namespace limonp

#endif // LIMONP_CONFIG_H
