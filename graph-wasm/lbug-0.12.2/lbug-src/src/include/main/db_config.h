#pragma once

#include <string>

#include "common/types/value/value.h"

namespace lbug {
namespace common {
enum class LogicalTypeID : uint8_t;
} // namespace common

namespace main {

class ClientContext;
struct SystemConfig;

typedef void (*set_context)(ClientContext* context, const common::Value& parameter);
typedef common::Value (*get_setting)(const ClientContext* context);

enum class OptionType : uint8_t { CONFIGURATION = 0, EXTENSION = 1 };

struct Option {
    std::string name;
    common::LogicalTypeID parameterType;
    OptionType optionType;
    bool isConfidential;

    Option(std::string name, common::LogicalTypeID parameterType, OptionType optionType,
        bool isConfidential)
        : name{std::move(name)}, parameterType{parameterType}, optionType{optionType},
          isConfidential{isConfidential} {}

    virtual ~Option() = default;
};

struct ConfigurationOption final : Option {
    set_context setContext;
    get_setting getSetting;

    ConfigurationOption(std::string name, common::LogicalTypeID parameterType,
        set_context setContext, get_setting getSetting)
        : Option{std::move(name), parameterType, OptionType::CONFIGURATION,
              false /* isConfidential */},
          setContext{setContext}, getSetting{getSetting} {}
};

struct ExtensionOption final : Option {
    common::Value defaultValue;

    ExtensionOption(std::string name, common::LogicalTypeID parameterType,
        common::Value defaultValue, bool isConfidential)
        : Option{std::move(name), parameterType, OptionType::EXTENSION, isConfidential},
          defaultValue{std::move(defaultValue)} {}
};

struct DBConfig {
    uint64_t bufferPoolSize;
    uint64_t maxNumThreads;
    bool enableCompression;
    bool readOnly;
    uint64_t maxDBSize;
    bool enableMultiWrites;
    bool autoCheckpoint;
    uint64_t checkpointThreshold;
    bool forceCheckpointOnClose;
    bool throwOnWalReplayFailure;
    bool enableChecksums;
    bool enableSpillingToDisk;
#if defined(__APPLE__)
    uint32_t threadQos;
#endif

    explicit DBConfig(const SystemConfig& systemConfig);

    static ConfigurationOption* getOptionByName(const std::string& optionName);
    LBUG_API static bool isDBPathInMemory(const std::string& dbPath);
};

} // namespace main
} // namespace lbug
