/************************************
 ************************************/
#ifndef LIMONP_NONCOPYABLE_H
#define LIMONP_NONCOPYABLE_H

namespace limonp {

class NonCopyable {
 protected:
  NonCopyable() {
  }
  ~NonCopyable() {
  }
 private:
  NonCopyable(const NonCopyable& );
  const NonCopyable& operator=(const NonCopyable& );
}; // class NonCopyable

} // namespace limonp

#endif // LIMONP_NONCOPYABLE_H
