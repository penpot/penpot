#pragma once

#include <type_traits>

#include "common/assert.h"
#include "common/types/blob.h"
#include "common/types/date_t.h"
#include "common/types/int128_t.h"
#include "common/types/interval_t.h"
#include "common/types/ku_string.h"
#include "common/types/timestamp_t.h"
#include "common/types/types.h"
#include "common/types/uint128_t.h"
#include "common/types/uuid.h"
#include "common/vector/value_vector.h"

namespace lbug {
namespace common {

class ValueVector;

template<class... Funcs>
struct overload : Funcs... {
    explicit overload(Funcs... funcs) : Funcs(funcs)... {}
    using Funcs::operator()...;
};

class TypeUtils {
public:
    template<typename Func, typename... Types, size_t... indices>
    static void paramPackForEachHelper(const Func& func, std::index_sequence<indices...>,
        Types&&... values) {
        ((func(indices, values)), ...);
    }

    template<typename Func, typename... Types>
    static void paramPackForEach(const Func& func, Types&&... values) {
        paramPackForEachHelper(func, std::index_sequence_for<Types...>(),
            std::forward<Types>(values)...);
    }

    static std::string entryToString(const LogicalType& dataType, const uint8_t* value,
        ValueVector* vector);

    template<typename T>
    static inline std::string toString(const T& val, void* /*valueVector*/ = nullptr) {
        if constexpr (std::is_same_v<T, std::string>) {
            return val;
        } else if constexpr (std::is_same_v<T, ku_string_t>) {
            return val.getAsString();
        } else {
            static_assert(std::is_same<T, int64_t>::value || std::is_same<T, int32_t>::value ||
                          std::is_same<T, int16_t>::value || std::is_same<T, int8_t>::value ||
                          std::is_same<T, uint64_t>::value || std::is_same<T, uint32_t>::value ||
                          std::is_same<T, uint16_t>::value || std::is_same<T, uint8_t>::value ||
                          std::is_same<T, double>::value || std::is_same<T, float>::value);
            return std::to_string(val);
        }
    }
    static std::string nodeToString(const struct_entry_t& val, ValueVector* vector);
    static std::string relToString(const struct_entry_t& val, ValueVector* vector);

    static inline void encodeOverflowPtr(uint64_t& overflowPtr, page_idx_t pageIdx,
        uint32_t pageOffset) {
        memcpy(&overflowPtr, &pageIdx, 4);
        memcpy(((uint8_t*)&overflowPtr) + 4, &pageOffset, 4);
    }
    static inline void decodeOverflowPtr(uint64_t overflowPtr, page_idx_t& pageIdx,
        uint32_t& pageOffset) {
        pageIdx = 0;
        memcpy(&pageIdx, &overflowPtr, 4);
        memcpy(&pageOffset, ((uint8_t*)&overflowPtr) + 4, 4);
    }

    template<typename T>
    static inline constexpr common::PhysicalTypeID getPhysicalTypeIDForType() {
        if constexpr (std::is_same_v<T, int64_t>) {
            return common::PhysicalTypeID::INT64;
        } else if constexpr (std::is_same_v<T, int32_t>) {
            return common::PhysicalTypeID::INT32;
        } else if constexpr (std::is_same_v<T, int16_t>) {
            return common::PhysicalTypeID::INT16;
        } else if constexpr (std::is_same_v<T, int8_t>) {
            return common::PhysicalTypeID::INT8;
        } else if constexpr (std::is_same_v<T, uint64_t>) {
            return common::PhysicalTypeID::UINT64;
        } else if constexpr (std::is_same_v<T, uint32_t>) {
            return common::PhysicalTypeID::UINT32;
        } else if constexpr (std::is_same_v<T, uint16_t>) {
            return common::PhysicalTypeID::UINT16;
        } else if constexpr (std::is_same_v<T, uint8_t>) {
            return common::PhysicalTypeID::UINT8;
        } else if constexpr (std::is_same_v<T, float>) {
            return common::PhysicalTypeID::FLOAT;
        } else if constexpr (std::is_same_v<T, double>) {
            return common::PhysicalTypeID::DOUBLE;
        } else if constexpr (std::is_same_v<T, int128_t>) {
            return common::PhysicalTypeID::INT128;
        } else if constexpr (std::is_same_v<T, interval_t>) {
            return common::PhysicalTypeID::INTERVAL;
        } else if constexpr (std::is_same_v<T, uint128_t>) {
            return common::PhysicalTypeID::UINT128;
        } else if constexpr (std::same_as<T, ku_string_t> || std::same_as<T, std::string> ||
                             std::same_as<T, std::string_view>) {
            return common::PhysicalTypeID::STRING;
        } else {
            KU_UNREACHABLE;
        }
    }

    /*
     * TypeUtils::visit can be used to call generic code on all or some Logical and Physical type
     * variants with access to type information.
     *
     * E.g.
     *
     *  std::string result;
     *  visit(dataType, [&]<typename T>(T) {
     *      if constexpr(std::is_same_v<T, ku_string_t>()) {
     *          result = vector->getValue<ku_string_t>(0).getAsString();
     *      } else if (std::integral<T>) {
     *          result = std::to_string(vector->getValue<T>(0));
     *      } else {
     *          KU_UNREACHABLE;
     *      }
     *  });
     *
     * or
     *  std::string result;
     *  visit(dataType,
     *      [&](ku_string_t) {
     *          result = vector->getValue<ku_string_t>(0);
     *      },
     *      [&]<std::integral T>(T) {
     *          result = std::to_string(vector->getValue<T>(0));
     *      },
     *      [](auto) { KU_UNREACHABLE; }
     *  );
     *
     * Note that when multiple functions are provided, at least one function must match all data
     * types.
     *
     * Also note that implicit conversions may occur with the multi-function variant
     * if you don't include a generic auto function to cover types which aren't explicitly included.
     * See https://en.cppreference.com/w/cpp/utility/variant/visit
     */
    template<typename... Fs>
    static inline auto visit(const LogicalType& dataType, Fs... funcs) {
        // Note: arguments are used only for type deduction and have no meaningful value.
        // They should be optimized out by the compiler
        auto func = overload(funcs...);
        switch (dataType.getLogicalTypeID()) {
        /* NOLINTBEGIN(bugprone-branch-clone)*/
        case LogicalTypeID::INT8:
            return func(int8_t());
        case LogicalTypeID::UINT8:
            return func(uint8_t());
        case LogicalTypeID::INT16:
            return func(int16_t());
        case LogicalTypeID::UINT16:
            return func(uint16_t());
        case LogicalTypeID::INT32:
            return func(int32_t());
        case LogicalTypeID::UINT32:
            return func(uint32_t());
        case LogicalTypeID::SERIAL:
        case LogicalTypeID::INT64:
            return func(int64_t());
        case LogicalTypeID::UINT64:
            return func(uint64_t());
        case LogicalTypeID::BOOL:
            return func(bool());
        case LogicalTypeID::INT128:
            return func(int128_t());
        case LogicalTypeID::DOUBLE:
            return func(double());
        case LogicalTypeID::FLOAT:
            return func(float());
        case LogicalTypeID::DECIMAL:
            switch (dataType.getPhysicalType()) {
            case PhysicalTypeID::INT16:
                return func(int16_t());
            case PhysicalTypeID::INT32:
                return func(int32_t());
            case PhysicalTypeID::INT64:
                return func(int64_t());
            case PhysicalTypeID::INT128:
                return func(int128_t());
            default:
                KU_UNREACHABLE;
            }
        case LogicalTypeID::INTERVAL:
            return func(interval_t());
        case LogicalTypeID::INTERNAL_ID:
            return func(internalID_t());
        case LogicalTypeID::UINT128:
            return func(uint128_t());
        case LogicalTypeID::STRING:
            return func(ku_string_t());
        case LogicalTypeID::DATE:
            return func(date_t());
        case LogicalTypeID::TIMESTAMP_NS:
            return func(timestamp_ns_t());
        case LogicalTypeID::TIMESTAMP_MS:
            return func(timestamp_ms_t());
        case LogicalTypeID::TIMESTAMP_SEC:
            return func(timestamp_sec_t());
        case LogicalTypeID::TIMESTAMP_TZ:
            return func(timestamp_tz_t());
        case LogicalTypeID::TIMESTAMP:
            return func(timestamp_t());
        case LogicalTypeID::BLOB:
            return func(blob_t());
        case LogicalTypeID::UUID:
            return func(ku_uuid_t());
        case LogicalTypeID::ARRAY:
        case LogicalTypeID::LIST:
            return func(list_entry_t());
        case LogicalTypeID::MAP:
            return func(map_entry_t());
        case LogicalTypeID::NODE:
        case LogicalTypeID::REL:
        case LogicalTypeID::RECURSIVE_REL:
        case LogicalTypeID::STRUCT:
            return func(struct_entry_t());
        case LogicalTypeID::UNION:
            return func(union_entry_t());
        /* NOLINTEND(bugprone-branch-clone)*/
        default:
            // Unsupported type
            KU_UNREACHABLE;
        }
    }

    template<typename... Fs>
    static inline auto visit(PhysicalTypeID dataType, Fs&&... funcs) {
        // Note: arguments are used only for type deduction and have no meaningful value.
        // They should be optimized out by the compiler
        auto func = overload(funcs...);
        switch (dataType) {
        /* NOLINTBEGIN(bugprone-branch-clone)*/
        case PhysicalTypeID::INT8:
            return func(int8_t());
        case PhysicalTypeID::UINT8:
            return func(uint8_t());
        case PhysicalTypeID::INT16:
            return func(int16_t());
        case PhysicalTypeID::UINT16:
            return func(uint16_t());
        case PhysicalTypeID::INT32:
            return func(int32_t());
        case PhysicalTypeID::UINT32:
            return func(uint32_t());
        case PhysicalTypeID::INT64:
            return func(int64_t());
        case PhysicalTypeID::UINT64:
            return func(uint64_t());
        case PhysicalTypeID::BOOL:
            return func(bool());
        case PhysicalTypeID::INT128:
            return func(int128_t());
        case PhysicalTypeID::DOUBLE:
            return func(double());
        case PhysicalTypeID::FLOAT:
            return func(float());
        case PhysicalTypeID::INTERVAL:
            return func(interval_t());
        case PhysicalTypeID::INTERNAL_ID:
            return func(internalID_t());
        case PhysicalTypeID::UINT128:
            return func(uint128_t());
        case PhysicalTypeID::STRING:
            return func(ku_string_t());
        case PhysicalTypeID::ARRAY:
        case PhysicalTypeID::LIST:
            return func(list_entry_t());
        case PhysicalTypeID::STRUCT:
            return func(struct_entry_t());
        /* NOLINTEND(bugprone-branch-clone)*/
        case PhysicalTypeID::ANY:
        case PhysicalTypeID::POINTER:
        case PhysicalTypeID::ALP_EXCEPTION_DOUBLE:
        case PhysicalTypeID::ALP_EXCEPTION_FLOAT:
            // Unsupported type
            KU_UNREACHABLE;
            // Needed for return type deduction to work
            return func(uint8_t());
        default:
            KU_UNREACHABLE;
        }
    }
};

// Forward declaration of template specializations.
template<>
std::string TypeUtils::toString(const int128_t& val, void* valueVector);
template<>
std::string TypeUtils::toString(const uint128_t& val, void* valueVector);
template<>
std::string TypeUtils::toString(const bool& val, void* valueVector);
template<>
std::string TypeUtils::toString(const internalID_t& val, void* valueVector);
template<>
std::string TypeUtils::toString(const date_t& val, void* valueVector);
template<>
std::string TypeUtils::toString(const timestamp_ns_t& val, void* valueVector);
template<>
std::string TypeUtils::toString(const timestamp_ms_t& val, void* valueVector);
template<>
std::string TypeUtils::toString(const timestamp_sec_t& val, void* valueVector);
template<>
std::string TypeUtils::toString(const timestamp_tz_t& val, void* valueVector);
template<>
std::string TypeUtils::toString(const timestamp_t& val, void* valueVector);
template<>
std::string TypeUtils::toString(const interval_t& val, void* valueVector);
template<>
std::string TypeUtils::toString(const ku_string_t& val, void* valueVector);
template<>
std::string TypeUtils::toString(const blob_t& val, void* valueVector);
template<>
std::string TypeUtils::toString(const ku_uuid_t& val, void* valueVector);
template<>
std::string TypeUtils::toString(const list_entry_t& val, void* valueVector);
template<>
std::string TypeUtils::toString(const map_entry_t& val, void* valueVector);
template<>
std::string TypeUtils::toString(const struct_entry_t& val, void* valueVector);
template<>
std::string TypeUtils::toString(const union_entry_t& val, void* valueVector);

} // namespace common
} // namespace lbug
