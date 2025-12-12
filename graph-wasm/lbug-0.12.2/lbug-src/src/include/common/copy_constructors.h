#pragma once
#include <map>
#include <memory>
#include <unordered_map>
#include <vector>
// This file defines many macros for controlling copy constructors and move constructors on classes.

// NOLINTBEGIN(bugprone-macro-parentheses): Although this is a good check in general, here, we
// cannot add parantheses around the arguments, for it would be invalid syntax.
#define DELETE_COPY_CONSTRUCT(Object) Object(const Object& other) = delete
#define DELETE_COPY_ASSN(Object) Object& operator=(const Object& other) = delete

#define DELETE_MOVE_CONSTRUCT(Object) Object(Object&& other) = delete
#define DELETE_MOVE_ASSN(Object) Object& operator=(Object&& other) = delete

#define DELETE_BOTH_COPY(Object)                                                                   \
    DELETE_COPY_CONSTRUCT(Object);                                                                 \
    DELETE_COPY_ASSN(Object)

#define DELETE_BOTH_MOVE(Object)                                                                   \
    DELETE_MOVE_CONSTRUCT(Object);                                                                 \
    DELETE_MOVE_ASSN(Object)

#define DEFAULT_MOVE_CONSTRUCT(Object) Object(Object&& other) = default
#define DEFAULT_MOVE_ASSN(Object) Object& operator=(Object&& other) = default

#define DEFAULT_BOTH_MOVE(Object)                                                                  \
    DEFAULT_MOVE_CONSTRUCT(Object);                                                                \
    DEFAULT_MOVE_ASSN(Object)

#define EXPLICIT_COPY_METHOD(Object)                                                               \
    Object copy() const {                                                                          \
        return *this;                                                                              \
    }

// EXPLICIT_COPY_DEFAULT_MOVE should be the default choice. It expects a PRIVATE copy constructor to
// be defined, which will be used by an explicit `copy()` method. For instance:
//
//   private:
//     MyClass(const MyClass& other) : field(other.field.copy()) {}
//
//   public:
//     EXPLICIT_COPY_DEFAULT_MOVE(MyClass);
//
// Now:
//
// MyClass o1;
// MyClass o2 = o1; // Compile error, copy assignment deleted.
// MyClass o2 = o1.copy(); // OK.
// MyClass o2(o1); // Compile error, copy constructor is private.
#define EXPLICIT_COPY_DEFAULT_MOVE(Object)                                                         \
    DELETE_COPY_ASSN(Object);                                                                      \
    DEFAULT_BOTH_MOVE(Object);                                                                     \
    EXPLICIT_COPY_METHOD(Object)

// NO_COPY should be used for objects that for whatever reason, should never be copied, but can be
// moved.
#define DELETE_COPY_DEFAULT_MOVE(Object)                                                           \
    DELETE_BOTH_COPY(Object);                                                                      \
    DEFAULT_BOTH_MOVE(Object)

// NO_MOVE_OR_COPY exists solely for explicitness, when an object cannot be moved nor copied. Any
// object containing a lock cannot be moved or copied.
#define DELETE_COPY_AND_MOVE(Object)                                                               \
    DELETE_BOTH_COPY(Object);                                                                      \
    DELETE_BOTH_MOVE(Object)
// NOLINTEND(bugprone-macro-parentheses):

template<typename T>
static std::vector<T> copyVector(const std::vector<T>& objects) {
    std::vector<T> result;
    result.reserve(objects.size());
    for (auto& object : objects) {
        result.push_back(object.copy());
    }
    return result;
}

template<typename T>
static std::vector<std::shared_ptr<T>> copyVector(const std::vector<std::shared_ptr<T>>& objects) {
    std::vector<std::shared_ptr<T>> result;
    result.reserve(objects.size());
    for (auto& object : objects) {
        T& ob = *object;
        result.push_back(ob.copy());
    }
    return result;
}

template<typename T>
static std::vector<std::unique_ptr<T>> copyVector(const std::vector<std::unique_ptr<T>>& objects) {
    std::vector<std::unique_ptr<T>> result;
    result.reserve(objects.size());
    for (auto& object : objects) {
        T& ob = *object;
        result.push_back(ob.copy());
    }
    return result;
}

template<typename K, typename V>
static std::unordered_map<K, V> copyUnorderedMap(const std::unordered_map<K, V>& objects) {
    std::unordered_map<K, V> result;
    for (auto& [k, v] : objects) {
        result.insert({k, v.copy()});
    }
    return result;
}

template<typename K, typename V>
static std::map<K, V> copyMap(const std::map<K, V>& objects) {
    std::map<K, V> result;
    for (auto& [k, v] : objects) {
        result.insert({k, v.copy()});
    }
    return result;
}
