#include "c_api/lbug.h"
#include "common/exception/exception.h"
#include "main/lbug.h"
using namespace lbug::main;
using namespace lbug::common;

lbug_state lbug_database_init(const char* database_path, lbug_system_config config,
    lbug_database* out_database) {
    try {
        std::string database_path_str = database_path;
        auto systemConfig = SystemConfig(config.buffer_pool_size, config.max_num_threads,
            config.enable_compression, config.read_only, config.max_db_size, config.auto_checkpoint,
            config.checkpoint_threshold);

#if defined(__APPLE__)
        systemConfig.threadQos = config.thread_qos;
#endif
        out_database->_database = new Database(database_path_str, systemConfig);
    } catch (Exception& e) {
        out_database->_database = nullptr;
        return LbugError;
    }
    return LbugSuccess;
}

void lbug_database_destroy(lbug_database* database) {
    if (database == nullptr) {
        return;
    }
    if (database->_database != nullptr) {
        delete static_cast<Database*>(database->_database);
    }
}

lbug_system_config lbug_default_system_config() {
    SystemConfig config = SystemConfig();
    auto cSystemConfig = lbug_system_config();
    cSystemConfig.buffer_pool_size = config.bufferPoolSize;
    cSystemConfig.max_num_threads = config.maxNumThreads;
    cSystemConfig.enable_compression = config.enableCompression;
    cSystemConfig.read_only = config.readOnly;
    cSystemConfig.max_db_size = config.maxDBSize;
    cSystemConfig.auto_checkpoint = config.autoCheckpoint;
    cSystemConfig.checkpoint_threshold = config.checkpointThreshold;
#if defined(__APPLE__)
    cSystemConfig.thread_qos = config.threadQos;
#endif
    return cSystemConfig;
}
