#ifndef CPPJIEBA_PRE_FILTER_H
#define CPPJIEBA_PRE_FILTER_H

#include "Trie.hpp"
#include "limonp/Logging.hpp"

namespace cppjieba {

class PreFilter {
 public:
  //TODO use WordRange instead of Range
  struct Range {
    RuneStrArray::const_iterator begin;
    RuneStrArray::const_iterator end;
  }; // struct Range

  PreFilter(const unordered_set<Rune>& symbols, 
        const string& sentence)
    : symbols_(symbols) {
    if (!DecodeUTF8RunesInString(sentence, sentence_)) {
      XLOG(ERROR) << "UTF-8 decode failed for input sentence"; 
    }
    cursor_ = sentence_.begin();
  }
  ~PreFilter() {
  }
  bool HasNext() const {
    return cursor_ != sentence_.end();
  }
  Range Next() {
    Range range;
    range.begin = cursor_;
    while (cursor_ != sentence_.end()) {
      if (IsIn(symbols_, cursor_->rune)) {
        if (range.begin == cursor_) {
          cursor_ ++;
        }
        range.end = cursor_;
        return range;
      }
      cursor_ ++;
    }
    range.end = sentence_.end();
    return range;
  }
 private:
  RuneStrArray::const_iterator cursor_;
  RuneStrArray sentence_;
  const unordered_set<Rune>& symbols_;
}; // class PreFilter

} // namespace cppjieba

#endif // CPPJIEBA_PRE_FILTER_H
