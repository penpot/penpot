#pragma once

#include "storage/table/column_chunk_data.h"
#include "transaction/transaction.h"

namespace lbug {
namespace storage {

class LBUG_API CachedColumn : public transaction::LocalCacheObject {
public:
    static std::string getKey(common::table_id_t tableID, common::property_id_t propertyID) {
        return common::stringFormat("{}-{}", tableID, propertyID);
    }
    explicit CachedColumn(common::table_id_t tableID, common::property_id_t propertyID)
        : LocalCacheObject{getKey(tableID, propertyID)}, columnChunks{} {}
    DELETE_BOTH_COPY(CachedColumn);

    std::vector<std::unique_ptr<ColumnChunkData>> columnChunks;
};

} // namespace storage
} // namespace lbug
