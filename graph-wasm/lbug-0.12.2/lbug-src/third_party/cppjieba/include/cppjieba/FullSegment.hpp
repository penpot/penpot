#ifndef CPPJIEBA_FULLSEGMENT_H
#define CPPJIEBA_FULLSEGMENT_H

#include <algorithm>
#include <set>
#include <cassert>
#include "limonp/Logging.hpp"
#include "DictTrie.hpp"
#include "SegmentBase.hpp"
#include "Unicode.hpp"

namespace cppjieba {
class FullSegment: public SegmentBase {
 public:
  FullSegment(const string& dictPath) {
    dictTrie_ = new DictTrie(dictPath);
    isNeedDestroy_ = true;
  }
  FullSegment(const DictTrie* dictTrie)
    : dictTrie_(dictTrie), isNeedDestroy_(false) {
    assert(dictTrie_);
  }
  ~FullSegment() {
    if (isNeedDestroy_) {
      delete dictTrie_;
    }
  }
  void Cut(const string& sentence, 
        vector<string>& words) const {
    vector<Word> tmp;
    Cut(sentence, tmp);
    GetStringsFromWords(tmp, words);
  }
  void Cut(const string& sentence, 
        vector<Word>& words) const {
    PreFilter pre_filter(symbols_, sentence);
    PreFilter::Range range;
    vector<WordRange> wrs;
    wrs.reserve(sentence.size()/2);
    while (pre_filter.HasNext()) {
      range = pre_filter.Next();
      Cut(range.begin, range.end, wrs);
    }
    words.clear();
    words.reserve(wrs.size());
    GetWordsFromWordRanges(sentence, wrs, words);
  }
  void Cut(RuneStrArray::const_iterator begin, 
        RuneStrArray::const_iterator end, 
        vector<WordRange>& res) const {
    // result of searching in trie tree
    LocalVector<pair<size_t, const DictUnit*> > tRes;

    // max index of res's words
    size_t maxIdx = 0;

    // always equals to (uItr - begin)
    size_t uIdx = 0;

    // tmp variables
    size_t wordLen = 0;
    assert(dictTrie_);
    vector<struct Dag> dags;
    dictTrie_->Find(begin, end, dags);
    for (size_t i = 0; i < dags.size(); i++) {
      for (size_t j = 0; j < dags[i].nexts.size(); j++) {
        size_t nextoffset = dags[i].nexts[j].first;
        assert(nextoffset < dags.size());
        const DictUnit* du = dags[i].nexts[j].second;
        if (du == NULL) {
          if (dags[i].nexts.size() == 1 && maxIdx <= uIdx) {
            WordRange wr(begin + i, begin + nextoffset);
            res.push_back(wr);
          }
        } else {
          wordLen = du->word.size();
          if (wordLen >= 2 || (dags[i].nexts.size() == 1 && maxIdx <= uIdx)) {
            WordRange wr(begin + i, begin + nextoffset);
            res.push_back(wr);
          }
        }
        maxIdx = uIdx + wordLen > maxIdx ? uIdx + wordLen : maxIdx;
      }
      uIdx++;
    }
  }
 private:
  const DictTrie* dictTrie_;
  bool isNeedDestroy_;
};
}

#endif
