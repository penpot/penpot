#pragma once

#include <functional>
#include <map>
#include <memory>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "common/assert.h"
#include "common/serializer/reader.h"

namespace lbug {
namespace common {

class LBUG_API Deserializer {
public:
    explicit Deserializer(std::unique_ptr<Reader> reader) : reader(std::move(reader)) {}

    bool finished() const { return reader->finished(); }

    template<typename T>
        requires std::is_trivially_destructible_v<T> || std::is_same_v<std::string, T>
    void deserializeValue(T& value) {
        reader->read(reinterpret_cast<uint8_t*>(&value), sizeof(T));
    }

    void read(uint8_t* data, uint64_t size) const { reader->read(data, size); }

    Reader* getReader() const { return reader.get(); }

    void validateDebuggingInfo(std::string& value, const std::string& expectedVal);

    template<typename T>
    void deserializeOptionalValue(std::unique_ptr<T>& value) {
        bool isNull = false;
        deserializeValue(isNull);
        if (!isNull) {
            value = T::deserialize(*this);
        }
    }

    template<typename T1, typename T2>
    void deserializeMap(std::map<T1, T2>& values) {
        uint64_t mapSize = 0;
        deserializeValue<uint64_t>(mapSize);
        for (auto i = 0u; i < mapSize; i++) {
            T1 key;
            deserializeValue<T1>(key);
            auto val = T2::deserialize(*this);
            values.emplace(key, std::move(val));
        }
    }

    template<typename T1, typename T2>
    void deserializeUnorderedMap(std::unordered_map<T1, T2>& values) {
        uint64_t mapSize = 0;
        deserializeValue<uint64_t>(mapSize);
        for (auto i = 0u; i < mapSize; i++) {
            T1 key;
            deserializeValue<T1>(key);
            T2 val;
            deserializeValue(val);
            values.emplace(key, std::move(val));
        }
    }

    template<typename T1, typename T2>
    void deserializeUnorderedMapOfPtrs(std::unordered_map<T1, std::unique_ptr<T2>>& values) {
        uint64_t mapSize = 0;
        deserializeValue<uint64_t>(mapSize);
        values.reserve(mapSize);
        for (auto i = 0u; i < mapSize; i++) {
            T1 key;
            deserializeValue<T1>(key);
            auto val = T2::deserialize(*this);
            values.emplace(key, std::move(val));
        }
    }

    template<typename T>
    void deserializeVector(std::vector<T>& values) {
        uint64_t vectorSize = 0;
        deserializeValue(vectorSize);
        values.resize(vectorSize);
        for (auto& value : values) {
            if constexpr (requires(Deserializer& deser) { T::deserialize(deser); }) {
                value = T::deserialize(*this);
            } else {
                deserializeValue(value);
            }
        }
    }

    template<typename T, uint64_t ARRAY_SIZE>
    void deserializeArray(std::array<T, ARRAY_SIZE>& values) {
        KU_ASSERT(values.size() == ARRAY_SIZE);
        for (auto& value : values) {
            if constexpr (requires(Deserializer& deser) { T::deserialize(deser); }) {
                value = T::deserialize(*this);
            } else {
                deserializeValue(value);
            }
        }
    }

    template<typename T>
    void deserializeVectorOfPtrs(std::vector<std::unique_ptr<T>>& values) {
        uint64_t vectorSize = 0;
        deserializeValue(vectorSize);
        values.resize(vectorSize);
        for (auto i = 0u; i < vectorSize; i++) {
            values[i] = T::deserialize(*this);
        }
    }

    template<typename T>
    void deserializeVectorOfPtrs(std::vector<std::unique_ptr<T>>& values,
        std::function<std::unique_ptr<T>(Deserializer&)> deserializeFunc) {
        uint64_t vectorSize = 0;
        deserializeValue(vectorSize);
        values.resize(vectorSize);
        for (auto i = 0u; i < vectorSize; i++) {
            values[i] = deserializeFunc(*this);
        }
    }

    template<typename T>
    void deserializeUnorderedSet(std::unordered_set<T>& values) {
        uint64_t setSize = 0;
        deserializeValue(setSize);
        for (auto i = 0u; i < setSize; i++) {
            T value;
            deserializeValue<T>(value);
            values.insert(value);
        }
    }

private:
    std::unique_ptr<Reader> reader;
};

template<>
void Deserializer::deserializeValue(std::string& value);

} // namespace common
} // namespace lbug
