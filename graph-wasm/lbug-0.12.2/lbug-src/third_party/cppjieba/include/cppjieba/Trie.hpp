#ifndef CPPJIEBA_TRIE_HPP
#define CPPJIEBA_TRIE_HPP

#include <vector>
#include <queue>
#include "limonp/StdExtension.hpp"
#include "Unicode.hpp"

namespace cppjieba {

using namespace std;

const size_t MAX_WORD_LENGTH = 512;

struct DictUnit {
  Unicode word;
  double weight;
  string tag;
}; // struct DictUnit

// for debugging
// inline ostream & operator << (ostream& os, const DictUnit& unit) {
//   string s;
//   s << unit.word;
//   return os << StringFormat("%s %s %.3lf", s.c_str(), unit.tag.c_str(), unit.weight);
// }

struct Dag {
  RuneStr runestr;
  // [offset, nexts.first]
  limonp::LocalVector<pair<size_t, const DictUnit*> > nexts;
  const DictUnit * pInfo;
  double weight;
  size_t nextPos; // TODO
  Dag():runestr(), pInfo(NULL), weight(0.0), nextPos(0) {
  }
}; // struct Dag

typedef Rune TrieKey;

class TrieNode {
 public :
  TrieNode(): next(NULL), ptValue(NULL) {
  }
 public:
  typedef unordered_map<TrieKey, TrieNode*> NextMap;
  NextMap *next;
  const DictUnit *ptValue;
};

class Trie {
 public:
  Trie(const vector<Unicode>& keys, const vector<const DictUnit*>& valuePointers)
   : root_(new TrieNode) {
    CreateTrie(keys, valuePointers);
  }
  ~Trie() {
    DeleteNode(root_);
  }

  const DictUnit* Find(RuneStrArray::const_iterator begin, RuneStrArray::const_iterator end) const {
    if (begin == end) {
      return NULL;
    }

    const TrieNode* ptNode = root_;
    TrieNode::NextMap::const_iterator citer;
    for (RuneStrArray::const_iterator it = begin; it != end; it++) {
      if (NULL == ptNode->next) {
        return NULL;
      }
      citer = ptNode->next->find(it->rune);
      if (ptNode->next->end() == citer) {
        return NULL;
      }
      ptNode = citer->second;
    }
    return ptNode->ptValue;
  }

  void Find(RuneStrArray::const_iterator begin,
        RuneStrArray::const_iterator end,
        vector<struct Dag>&res,
        size_t max_word_len = MAX_WORD_LENGTH) const {
    assert(root_ != NULL);
    res.resize(end - begin);

    const TrieNode *ptNode = NULL;
    TrieNode::NextMap::const_iterator citer;
    for (size_t i = 0; i < size_t(end - begin); i++) {
      res[i].runestr = *(begin + i);

      if (root_->next != NULL && root_->next->end() != (citer = root_->next->find(res[i].runestr.rune))) {
        ptNode = citer->second;
      } else {
        ptNode = NULL;
      }
      if (ptNode != NULL) {
        res[i].nexts.push_back(pair<size_t, const DictUnit*>(i, ptNode->ptValue));
      } else {
        res[i].nexts.push_back(pair<size_t, const DictUnit*>(i, static_cast<const DictUnit*>(NULL)));
      }

      for (size_t j = i + 1; j < size_t(end - begin) && (j - i + 1) <= max_word_len; j++) {
        if (ptNode == NULL || ptNode->next == NULL) {
          break;
        }
        citer = ptNode->next->find((begin + j)->rune);
        if (ptNode->next->end() == citer) {
          break;
        }
        ptNode = citer->second;
        if (NULL != ptNode->ptValue) {
          res[i].nexts.push_back(pair<size_t, const DictUnit*>(j, ptNode->ptValue));
        }
      }
    }
  }

  void InsertNode(const Unicode& key, const DictUnit* ptValue) {
    if (key.begin() == key.end()) {
      return;
    }

    TrieNode::NextMap::const_iterator kmIter;
    TrieNode *ptNode = root_;
    for (Unicode::const_iterator citer = key.begin(); citer != key.end(); ++citer) {
      if (NULL == ptNode->next) {
        ptNode->next = new TrieNode::NextMap;
      }
      kmIter = ptNode->next->find(*citer);
      if (ptNode->next->end() == kmIter) {
        TrieNode *nextNode = new TrieNode;

        ptNode->next->insert(make_pair(*citer, nextNode));
        ptNode = nextNode;
      } else {
        ptNode = kmIter->second;
      }
    }
    assert(ptNode != NULL);
    ptNode->ptValue = ptValue;
  }
  void DeleteNode(const Unicode& key, const DictUnit* /*ptValue*/) {
      if (key.begin() == key.end()) {
        return;
      }
      //定义一个NextMap迭代器
      TrieNode::NextMap::const_iterator kmIter;
      //定义一个指向root的TrieNode指针
      TrieNode *ptNode = root_;
      for (Unicode::const_iterator citer = key.begin(); citer != key.end(); ++citer) {
        //链表不存在元素
        if (NULL == ptNode->next) {
          return;
        }
        kmIter = ptNode->next->find(*citer);
        //如果map中不存在,跳出循环
        if (ptNode->next->end() == kmIter) {
              break;
        }
        //从unordered_map中擦除该项
        ptNode->next->erase(*citer);
        //删除该node
        ptNode = kmIter->second;
        delete ptNode;
        break;
      }
      return;
 }
 private:
  void CreateTrie(const vector<Unicode>& keys, const vector<const DictUnit*>& valuePointers) {
    if (valuePointers.empty() || keys.empty()) {
      return;
    }
    assert(keys.size() == valuePointers.size());

    for (size_t i = 0; i < keys.size(); i++) {
      InsertNode(keys[i], valuePointers[i]);
    }
  }

  void DeleteNode(TrieNode* node) {
    if (NULL == node) {
      return;
    }
    if (NULL != node->next) {
      for (TrieNode::NextMap::iterator it = node->next->begin(); it != node->next->end(); ++it) {
        DeleteNode(it->second);
      }
      delete node->next;
    }
    delete node;
  }

  TrieNode* root_;
}; // class Trie
} // namespace cppjieba

#endif // CPPJIEBA_TRIE_HPP
