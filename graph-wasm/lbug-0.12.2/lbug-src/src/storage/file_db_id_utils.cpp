#include "storage/file_db_id_utils.h"

#include "common/exception/runtime.h"

namespace lbug::storage {
void FileDBIDUtils::verifyDatabaseID(const common::FileInfo& fileInfo,
    common::ku_uuid_t expectedDatabaseID, common::ku_uuid_t databaseID) {
    if (expectedDatabaseID.value != databaseID.value) {
        throw common::RuntimeException(common::stringFormat(
            "Database ID for temporary file '{}' does not match the current database. This file "
            "may have been left behind from a previous database with the same name. If it is safe "
            "to do so, please delete this file and restart the database.",
            fileInfo.path));
    }
}

void FileDBIDUtils::writeDatabaseID(common::Serializer& ser, common::ku_uuid_t databaseID) {
    ser.write(databaseID);
}
} // namespace lbug::storage
