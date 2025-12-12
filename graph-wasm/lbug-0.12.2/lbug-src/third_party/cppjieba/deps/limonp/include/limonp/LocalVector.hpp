#ifndef LIMONP_LOCAL_VECTOR_HPP
#define LIMONP_LOCAL_VECTOR_HPP

#include <iostream>
#include <stdlib.h>
#include <assert.h>
#include <string.h>

namespace limonp {
using namespace std;
/*
 * LocalVector<T> : T must be primitive type (char , int, size_t), if T is struct or class, LocalVector<T> may be dangerous..
 * LocalVector<T> is simple and not well-tested.
 */
const size_t LOCAL_VECTOR_BUFFER_SIZE = 16;
template <class T>
class LocalVector {
 public:
  typedef const T* const_iterator ;
  typedef T value_type;
  typedef size_t size_type;
 private:
  T buffer_[LOCAL_VECTOR_BUFFER_SIZE];
  T * ptr_;
  size_t size_;
  size_t capacity_;
 public:
  LocalVector() {
    init_();
  };
  LocalVector(const LocalVector<T>& vec) {
    init_();
    *this = vec;
  }
  LocalVector(const_iterator  begin, const_iterator end) { // TODO: make it faster
    init_();
    while(begin != end) {
      push_back(*begin++);
    }
  }
  LocalVector(size_t size, const T& t) { // TODO: make it faster
    init_();
    while(size--) {
      push_back(t);
    }
  }
  ~LocalVector() {
    if(ptr_ != buffer_) {
      free(ptr_);
    }
  };
 public:
  LocalVector<T>& operator = (const LocalVector<T>& vec) {
    clear();
    size_ = vec.size();
    capacity_ = vec.capacity();
    if(vec.buffer_ == vec.ptr_) {
      memcpy(static_cast<void*>(buffer_), vec.buffer_, sizeof(T) * size_);
      ptr_ = buffer_;
    } else {
      ptr_ = (T*) malloc(vec.capacity() * sizeof(T));
      assert(ptr_);
      memcpy(static_cast<void*>(ptr_), vec.ptr_, vec.size() * sizeof(T));
    }
    return *this;
  }
 private:
  void init_() {
    ptr_ = buffer_;
    size_ = 0;
    capacity_ = LOCAL_VECTOR_BUFFER_SIZE;
  }
 public:
  T& operator [] (size_t i) {
    return ptr_[i];
  }
  const T& operator [] (size_t i) const {
    return ptr_[i];
  }
  void push_back(const T& t) {
    if(size_ == capacity_) {
      assert(capacity_);
      reserve(capacity_ * 2);
    }
    ptr_[size_ ++ ] = t;
  }
  void reserve(size_t size) {
    if(size <= capacity_) {
      return;
    }
    T * next =  (T*)malloc(sizeof(T) * size);
    assert(next);
    T * old = ptr_;
    ptr_ = next;
    memcpy(static_cast<void*>(ptr_), old, sizeof(T) * capacity_);
    capacity_ = size;
    if(old != buffer_) {
      free(old);
    }
  }
  bool empty() const {
    return 0 == size();
  }
  size_t size() const {
    return size_;
  }
  size_t capacity() const {
    return capacity_;
  }
  const_iterator begin() const {
    return ptr_;
  }
  const_iterator end() const {
    return ptr_ + size_;
  }
  void clear() {
    if(ptr_ != buffer_) {
      free(ptr_);
    }
    init_();
  }
};

template <class T>
ostream & operator << (ostream& os, const LocalVector<T>& vec) {
  if(vec.empty()) {
    return os << "[]";
  }
  os<<"[\""<<vec[0];
  for(size_t i = 1; i < vec.size(); i++) {
    os<<"\", \""<<vec[i];
  }
  os<<"\"]";
  return os;
}

}

#endif
