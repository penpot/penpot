#pragma once

#include "column_reader.h"
#include "parquet_reader.h"
#include "templated_column_reader.h"

namespace lbug {
namespace processor {

template<class PARQUET_PHYSICAL_TYPE, class KU_PHYSICAL_TYPE,
    KU_PHYSICAL_TYPE (*FUNC)(const PARQUET_PHYSICAL_TYPE& input)>
class CallbackColumnReader
    : public TemplatedColumnReader<KU_PHYSICAL_TYPE,
          CallbackParquetValueConversion<PARQUET_PHYSICAL_TYPE, KU_PHYSICAL_TYPE, FUNC>> {
    using BaseType = TemplatedColumnReader<KU_PHYSICAL_TYPE,
        CallbackParquetValueConversion<PARQUET_PHYSICAL_TYPE, KU_PHYSICAL_TYPE, FUNC>>;

public:
    static constexpr const common::PhysicalTypeID TYPE = common::PhysicalTypeID::ANY;

public:
    CallbackColumnReader(ParquetReader& reader, common::LogicalType type_p,
        const lbug_parquet::format::SchemaElement& schema_p, uint64_t file_idx_p,
        uint64_t max_define_p, uint64_t max_repeat_p)
        : TemplatedColumnReader<KU_PHYSICAL_TYPE,
              CallbackParquetValueConversion<PARQUET_PHYSICAL_TYPE, KU_PHYSICAL_TYPE, FUNC>>(reader,
              std::move(type_p), schema_p, file_idx_p, max_define_p, max_repeat_p) {}

protected:
    void dictionary(const std::shared_ptr<ResizeableBuffer>& dictionaryData,
        uint64_t numEntries) override {
        BaseType::allocateDict(numEntries * sizeof(KU_PHYSICAL_TYPE));
        auto dictPtr = (KU_PHYSICAL_TYPE*)this->dict->ptr;
        for (auto i = 0u; i < numEntries; i++) {
            dictPtr[i] = FUNC(dictionaryData->read<PARQUET_PHYSICAL_TYPE>());
        }
    }
};

} // namespace processor
} // namespace lbug
