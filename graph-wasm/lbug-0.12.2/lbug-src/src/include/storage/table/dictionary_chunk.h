#pragma once

#include "storage/enums/residency_state.h"
#include "storage/table/column_chunk_data.h"

namespace lbug {
namespace storage {
class MemoryManager;

class DictionaryChunk {
public:
    using string_offset_t = uint64_t;
    using string_index_t = uint32_t;

    DictionaryChunk(MemoryManager& mm, uint64_t capacity, bool enableCompression,
        ResidencyState residencyState);
    // A pointer to the dictionary chunk is stored in the StringOps for the indexTable
    // and can't be modified easily. Moving would invalidate that pointer
    DictionaryChunk(DictionaryChunk&& other) = delete;

    void setToInMemory() {
        stringDataChunk->setToInMemory();
        offsetChunk->setToInMemory();
        indexTable.clear();
    }
    void resetToEmpty();

    uint64_t getStringLength(string_index_t index) const;

    string_index_t appendString(std::string_view val);

    std::string_view getString(string_index_t index) const;

    ColumnChunkData* getStringDataChunk() const { return stringDataChunk.get(); }
    ColumnChunkData* getOffsetChunk() const { return offsetChunk.get(); }
    void setOffsetChunk(std::unique_ptr<ColumnChunkData> chunk) { offsetChunk = std::move(chunk); }
    void setStringDataChunk(std::unique_ptr<ColumnChunkData> chunk) {
        stringDataChunk = std::move(chunk);
    }

    void resetNumValuesFromMetadata();

    bool sanityCheck() const;

    uint64_t getEstimatedMemoryUsage() const;

    void serialize(common::Serializer& serializer) const;
    static std::unique_ptr<DictionaryChunk> deserialize(MemoryManager& memoryManager,
        common::Deserializer& deSer);

    void flush(PageAllocator& pageAllocator);

private:
    bool enableCompression;
    // String data is stored as a UINT8 chunk, using the numValues in the chunk to track the number
    // of characters stored.
    std::unique_ptr<ColumnChunkData> stringDataChunk;
    std::unique_ptr<ColumnChunkData> offsetChunk;

    struct DictionaryEntry {
        string_index_t index;

        std::string_view get(const DictionaryChunk& dict) const { return dict.getString(index); }
    };

    struct StringOps {
        explicit StringOps(const DictionaryChunk* dict) : dict(dict) {}
        const DictionaryChunk* dict;
        using hash_type = std::hash<std::string_view>;
        using is_transparent = void;

        std::size_t operator()(const DictionaryEntry& entry) const {
            return std::hash<std::string_view>()(entry.get(*dict));
        }
        std::size_t operator()(const char* str) const { return hash_type{}(str); }
        std::size_t operator()(std::string_view str) const { return hash_type{}(str); }
        std::size_t operator()(std::string const& str) const { return hash_type{}(str); }

        bool operator()(const DictionaryEntry& lhs, const DictionaryEntry& rhs) const {
            return lhs.get(*dict) == rhs.get(*dict);
        }
        bool operator()(const DictionaryEntry& lhs, std::string_view rhs) const {
            return lhs.get(*dict) == rhs;
        }
        bool operator()(std::string_view lhs, const DictionaryEntry& rhs) const {
            return lhs == rhs.get(*dict);
        }
    };

    std::unordered_set<DictionaryEntry, StringOps /*hash*/, StringOps /*equals*/> indexTable;
};
} // namespace storage
} // namespace lbug
